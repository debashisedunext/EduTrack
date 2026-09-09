package com.edunext.edutrack.api.feature.onboarding.signoff;

import com.edunext.edutrack.api.feature.onboarding.instances.ObJourneyStepLifecycleService;
import com.edunext.edutrack.api.security.ClientAddress;
import com.edunext.edutrack.domain.onboarding.ObSignoff;
import com.edunext.edutrack.domain.onboarding.ObSignoffKind;
import com.edunext.edutrack.domain.onboarding.ObSignoffRepository;
import com.edunext.edutrack.domain.onboarding.ObSignoffStatus;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;

/**
 * B-115 · the client accepts, and our side finishes if it can.
 *
 * <h2>Two outcomes, and the second is not a failure</h2>
 *
 * <p>The contract is explicit that this operation has two successful shapes:
 * the gate is satisfied and the step completes, or the gate is not satisfied
 * and <b>the acceptance still stands</b> while the step stays
 * {@code IN_PROGRESS} with {@code gateFailures} naming what is missing. The
 * reasoning is the client's rather than ours: "the client did accept, they are
 * not the ones who left a document unattached, and asking them to click twice
 * for our own incomplete record is the version of this that loses a signature."
 *
 * <p>So the acceptance is written first and unconditionally, and the completion
 * is attempted afterwards. The order is the whole design — reversed, a gate
 * failure would roll back a signature we had already been given.
 *
 * <h2>The gate is not re-implemented here</h2>
 *
 * <p>{@link ObJourneyStepLifecycleService#completeOnClientAcceptance} is C-106's
 * gate, called rather than copied. PHASE-2-BUILD-PLAN §3 #4 found the prototype
 * with two completion paths enforcing different rules and ruled that there is
 * <em>one</em> gate; a second evaluation written in this package would be that
 * bug reintroduced with better manners.
 *
 * <h2>Single-use, enforced twice and in the right order</h2>
 *
 * <p>The status carries it: {@code ObSignoffTokens} treats anything but
 * {@code PENDING} as unusable, so a second accept on the same link resolves to
 * nothing and answers the same generic 401 an unknown token does. The Redis
 * session is invalidated as well, and deliberately <b>after the transaction
 * commits</b> — dropping the key first would leave a client whose transaction
 * then rolled back holding a session that no longer works and a sign-off that
 * was never accepted, with nothing to retry and no way to say so.
 */
@Service
public class ObSignoffAcceptService {

    private static final Logger log = LoggerFactory.getLogger(ObSignoffAcceptService.class);

    private final ObSignoffRepository signoffs;
    private final ObSignoffSessions sessions;
    private final ObSignoffContactReader contacts;
    private final ObJourneyStepLifecycleService stepLifecycle;
    private final ObSignoffCertificateService certificates;
    private final Clock clock;

    /**
     * <p><b>{@code @Autowired} is load-bearing, exactly as it is on
     * {@code ObSignoffTokens}.</b> This class has two constructors and no
     * {@code Clock} bean exists in the context, so without an explicit choice
     * Spring looks for a no-arg constructor that is not here and the whole
     * application context fails to start — taking every {@code @SpringBootTest}
     * with it, under a message that names the missing constructor rather than
     * the ambiguity that caused it to be looked for.
     */
    @Autowired
    ObSignoffAcceptService(ObSignoffRepository signoffs,
                           ObSignoffSessions sessions,
                           ObSignoffContactReader contacts,
                           ObJourneyStepLifecycleService stepLifecycle,
                           ObSignoffCertificateService certificates) {
        this(signoffs, sessions, contacts, stepLifecycle, certificates, Clock.systemUTC());
    }

    /**
     * Test seam, on {@code ObSignoffTokens}' precedent one class over: an
     * acceptance timestamp cannot be asserted against a clock that only moves
     * forwards.
     */
    ObSignoffAcceptService(ObSignoffRepository signoffs,
                           ObSignoffSessions sessions,
                           ObSignoffContactReader contacts,
                           ObJourneyStepLifecycleService stepLifecycle,
                           ObSignoffCertificateService certificates,
                           Clock clock) {
        this.signoffs = signoffs;
        this.sessions = sessions;
        this.contacts = contacts;
        this.stepLifecycle = stepLifecycle;
        this.certificates = certificates;
        this.clock = clock;
    }

    /**
     * Records the acceptance and puts the step through the completion gate.
     *
     * @throws InvalidSignoffTokenException for an unknown, expired or already
     *                                      spent session, and for a sign-off
     *                                      that is no longer {@code PENDING} —
     *                                      one generic 401 for all of them, the
     *                                      property {@link PublicSignoffAccess}
     *                                      holds for the rest of this surface
     */
    @Transactional
    public PublicSignoffAcceptDtos.AcceptResult accept(String sessionToken,
                                                       String acceptedName,
                                                       String note,
                                                       HttpServletRequest http) {

        ObSignoff signoff = requireSession(sessionToken);

        // The status check is not redundant with the session lookup. A session
        // is minted for fifteen minutes and the row can be cancelled by staff,
        // or decided by a second tab, inside that window.
        if (signoff.getStatus() != ObSignoffStatus.PENDING) {
            throw new InvalidSignoffTokenException();
        }

        recordAcceptance(signoff, acceptedName, note, http);
        signoffs.save(signoff);

        // B-116 · archived once the row is SIGNED and saved, never before —
        // the same "the acceptance is not a hostage to something downstream"
        // reasoning the completion gate below is built on. A rendering or
        // storage fault leaves pdfStorageKey null rather than losing the
        // signature; the certificate can be regenerated later, an
        // accepted-but-uncertified sign-off cannot be un-lost.
        signoff.setPdfStorageKey(archiveCertificateQuietly(signoff));

        List<String> gateFailures = completeStepIfAny(signoff);
        boolean stepCompleted = signoff.getKind() == ObSignoffKind.STEP && gateFailures.isEmpty();

        invalidateSessionAfterCommit(sessionToken);

        return new PublicSignoffAcceptDtos.AcceptResult(
                detailOf(signoff),
                stepCompleted,
                gateFailures,
                wentLive(signoff));
    }

    private ObSignoff requireSession(String sessionToken) {
        OptionalLong signoffId = sessions.resolve(sessionToken);
        if (signoffId.isEmpty()) {
            throw new InvalidSignoffTokenException();
        }
        return signoffs.findById(signoffId.getAsLong())
                .orElseThrow(InvalidSignoffTokenException::new);
    }

    /**
     * The recorded-acceptance record, PHASE-2-BUILD-PLAN decision 5's v1 answer
     * to "would this stand up": who accepted, under what name, when, and from
     * where.
     *
     * <p>{@code signedByContactId} is {@code sentToContactId} and is not taken
     * from the request. The OTP was mailed to that contact and nothing else was
     * proved, so naming anybody else would be recording a fact we did not
     * establish — and the column has a foreign key that would happily accept a
     * caller-supplied id belonging to a different client.
     *
     * <p>The user agent is truncated rather than rejected. It is 500 characters
     * on the column and a browser is free to send more; refusing the acceptance
     * over the length of a header the client did not choose would lose a
     * signature to a detail nobody can act on.
     */
    private void recordAcceptance(ObSignoff signoff, String acceptedName, String note,
                                  HttpServletRequest http) {
        signoff.setStatus(ObSignoffStatus.SIGNED);
        signoff.setSignedByContactId(signoff.getSentToContactId());
        signoff.setSignedName(acceptedName.trim());
        signoff.setSignedAt(clock.instant());
        signoff.setSignedIp(http == null ? ClientAddress.UNKNOWN : ClientAddress.of(http));
        signoff.setSignedUserAgent(truncate(http == null ? null : http.getHeader("User-Agent"), 500));
        signoff.setAcceptanceNote(blankToNull(note));
    }

    /**
     * A {@code STEP} sign-off completes its step; a {@code GO_LIVE} one has no
     * step to complete.
     *
     * <p>{@code stepId} is null exactly when the kind is {@code GO_LIVE} —
     * {@code ck_ob_signoffs_step_matches_kind} guarantees it — but both are
     * checked rather than one inferred from the other, because a null passed to
     * the lifecycle service would be a 500 on an unauthenticated route.
     */
    private List<String> completeStepIfAny(ObSignoff signoff) {
        if (signoff.getKind() != ObSignoffKind.STEP || signoff.getStepId() == null) {
            return List.of();
        }
        return stepLifecycle.completeOnClientAcceptance(signoff.getStepId());
    }

    /**
     * {@link ObSignoffCertificateService#archive}, with every failure caught
     * rather than left to unwind this transaction. See the call site: a
     * signature the client just gave us is not something a PDF renderer or an
     * object-storage outage gets to take back.
     */
    private String archiveCertificateQuietly(ObSignoff signoff) {
        try {
            return certificates.archive(signoff);
        } catch (RuntimeException certificateFailed) {
            log.warn("ob-signoff {}: acceptance recorded, but the certificate could not be archived",
                    signoff.getId(), certificateFailed);
            return null;
        }
    }

    /**
     * <b>Always false, and honestly so: the go-live flip is B-118 and does not
     * exist yet.</b>
     *
     * <p>The contract describes this field as "this acceptance was the last
     * one, every journey is complete, and the client is Live-Green". No code in
     * the application flips a client to Live-Green today, so no acceptance can
     * be that one, and {@code false} is the accurate answer rather than a
     * placeholder — a page rendering it will correctly show nothing about going
     * live, because nothing did.
     *
     * <p>This is the one field on the response B-118 changes. It is a method
     * rather than a literal so that the change is a body rather than a hunt.
     */
    @SuppressWarnings("unused")
    private boolean wentLive(ObSignoff signoff) {
        return false;
    }

    /**
     * Invalidating the Redis session only once the row is committed.
     *
     * <p>{@code afterCommit} rather than inline: the transaction can still roll
     * back after this method returns, and a client left holding a dead session
     * against an un-accepted sign-off has no way to try again and no way to
     * know that is what happened. Leaving the session alive on a rollback costs
     * nothing — the row is still {@code PENDING}, so a retry is exactly what
     * should happen.
     *
     * <p>Outside a transaction (a unit test calling the method directly) the
     * synchronisation manager is not active, so the invalidation runs inline.
     */
    private void invalidateSessionAfterCommit(String sessionToken) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            sessions.invalidate(sessionToken);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sessions.invalidate(sessionToken);
            }
        });
    }

    private PublicSignoffAcceptDtos.SignoffDetail detailOf(ObSignoff signoff) {
        PublicSignoffAcceptDtos.Contact contact = contacts.find(signoff.getSentToContactId());
        return new PublicSignoffAcceptDtos.SignoffDetail(
                signoff.getId(),
                signoff.getObClientId(),
                signoff.getJourneyId(),
                signoff.getStepId(),
                signoff.getKind(),
                signoff.getStatus(),
                null,                       // requestedBy — see SignoffDetail's javadoc
                signoff.getRequestedAt(),
                contact,
                signoff.getTokenExpiresAt(),
                signoff.getSignedAt(),
                signoff.getObjectedAt(),
                signoff.getPdfStorageKey() != null,
                contact,                    // signedByContact — the same contact, now that they have
                signoff.getSignedIp(),
                signoff.getSignedUserAgent(),
                signoff.getObjectionNote());
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
