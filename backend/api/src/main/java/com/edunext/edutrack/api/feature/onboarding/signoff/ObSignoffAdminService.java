package com.edunext.edutrack.api.feature.onboarding.signoff;

import com.edunext.edutrack.api.feature.auth.Digests;
import com.edunext.edutrack.api.feature.onboarding.clients.ObClientScope;
import com.edunext.edutrack.common.pagination.Cursor;
import com.edunext.edutrack.common.pagination.CursorPage;
import com.edunext.edutrack.common.pagination.PageLimit;
import com.edunext.edutrack.domain.onboarding.ObJourneyStep;
import com.edunext.edutrack.domain.onboarding.ObJourneyStepRepository;
import com.edunext.edutrack.domain.onboarding.ObSignoff;
import com.edunext.edutrack.domain.onboarding.ObSignoffKind;
import com.edunext.edutrack.domain.onboarding.ObSignoffRepository;
import com.edunext.edutrack.domain.onboarding.ObSignoffStatus;
import com.edunext.edutrack.domain.onboarding.outbox.ObChannel;
import com.edunext.edutrack.domain.onboarding.outbox.ObNotification;
import com.edunext.edutrack.domain.onboarding.outbox.ObNotificationEvent;
import com.edunext.edutrack.domain.onboarding.outbox.ObOutboxEnqueuer;
import com.edunext.edutrack.domain.onboarding.outbox.ObRecipient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The staff half of §8 — asking a client to sign off, chasing it, withdrawing
 * it, and reading the decision back.
 *
 * <h2>What this closes</h2>
 *
 * <p>{@code POST /onboarding/journeys/&#123;journeyId&#125;/signoffs} has been in the
 * contract since A-118 and had no controller until now, and everything
 * downstream of it has been quietly broken as a result. {@code SignoffPanel}
 * rendered a Request button whose 404 the panel could not even report properly;
 * {@code DevSignoffSimulationService} exists <em>because</em> of this gap and
 * says so in its own javadoc ("the staff-side request route is in the contract
 * but has no controller yet ... so nothing can even create the PENDING row a
 * client would accept"); {@code PortalSignoffDecisionService} and CP-05's list
 * screen were built to decide rows that nothing could create, and both left a
 * note saying so. A flagged step could not be completed against the real backend
 * by any route at all — only by the demo simulator, on two profiles out of five.
 *
 * <h2>The token is minted here and is never readable again</h2>
 *
 * <p>Only the SHA-256 is stored, so the plaintext exists for exactly as long as
 * it takes to put it in the outbox payload. That is A-107's design and the
 * contract states the consequence plainly: the token is in the response "to
 * <b>nobody</b> — not to the requester either. A staff member who could read it
 * could sign on the client's behalf, which is the one thing this record exists
 * to make impossible." Nothing in this class returns it, logs it, or hands it to
 * anything but {@link ObOutboxEnqueuer}.
 *
 * <p>The same fact makes {@link #resend} a <em>mint</em> rather than a re-send:
 * there is nothing to send again. That is not a workaround for the hash, it is
 * the behaviour the contract specifies for its own reasons — "a reissue that
 * left the old hash live would leave two working links to one decision with
 * nothing to say which was used".
 *
 * <h2>Nothing here decides a sign-off</h2>
 *
 * <p>No method writes {@code SIGNED} or {@code OBJECTED}. Those belong to
 * {@link ObSignoffAcceptService} and {@link ObSignoffObjectService}, reached by
 * the client through the public link or the portal, and PHASE-2-BUILD-PLAN §3 #4
 * already ruled against a second path that enforces different rules. The
 * statuses this class writes are the two the organisation owns: {@code PENDING},
 * when it asks, and {@code CANCELLED}, when it withdraws.
 */
@Service
class ObSignoffAdminService {

    /** The contract's own figure for how long a mailed link stays usable. */
    private static final Duration TOKEN_TTL = Duration.ofDays(14);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    /**
     * What {@code step_title} says on a go-live mail.
     *
     * <p>{@code SIGNOFF_REQUESTED} declares that variable required, and a
     * {@code GO_LIVE} row has no step to name — {@code ObSignoffPageReader}
     * returns null for it, correctly. A required variable rendering as nothing
     * would leave the sentence reading "Please sign off:" with a blank after the
     * colon, which is the failure D-029's fallback rule exists to prevent. This
     * is what the client is actually being asked to sign off.
     */
    private static final String GO_LIVE_TITLE = "Go-live";

    private final ObSignoffRepository signoffs;
    private final ObSignoffAdminRepository reads;
    private final ObJourneyStepRepository steps;
    private final ObSignoffPageReader pages;
    private final ObOutboxEnqueuer outbox;
    private final Clock clock;

    /**
     * <p><b>{@code @Autowired} is load-bearing, not decoration.</b> This class
     * has two constructors and no {@code Clock} bean exists in the context, so
     * without an explicit choice Spring falls back to a no-arg constructor that
     * is not here and the whole context fails at startup — the footgun
     * {@link ObSignoffAcceptService} documents and {@code ObDelayedProjectsService}
     * actually shipped.
     */
    @Autowired
    ObSignoffAdminService(ObSignoffRepository signoffs,
                          ObSignoffAdminRepository reads,
                          ObJourneyStepRepository steps,
                          ObSignoffPageReader pages,
                          ObOutboxEnqueuer outbox) {
        this(signoffs, reads, steps, pages, outbox, Clock.systemUTC());
    }

    /** Test seam — an expiry cannot be asserted against a clock that only moves forwards. */
    ObSignoffAdminService(ObSignoffRepository signoffs,
                          ObSignoffAdminRepository reads,
                          ObJourneyStepRepository steps,
                          ObSignoffPageReader pages,
                          ObOutboxEnqueuer outbox,
                          Clock clock) {
        this.signoffs = signoffs;
        this.reads = reads;
        this.steps = steps;
        this.pages = pages;
        this.outbox = outbox;
        this.clock = clock;
    }

    // ─────────────────────────────────────────────────────────────── reads

    @Transactional(readOnly = true)
    ObSignoffAdminDtos.ObSignoffListResponse list(ObClientScope scope, Long obClientId, Long journeyId,
                                                  ObSignoffKind kind, ObSignoffStatus status,
                                                  String cursor, Integer requestedLimit) {

        int limit = PageLimit.clamp(requestedLimit);
        List<ObSignoffAdminRepository.Row> rows = reads.list(
                scope, obClientId, journeyId, kind, status,
                Cursor.decode(cursor), PageLimit.fetchSize(limit));

        CursorPage<ObSignoffAdminRepository.Row> page = CursorPage.of(
                rows, limit, row -> new Cursor(row.requestedAt().toString(), row.id()));

        return new ObSignoffAdminDtos.ObSignoffListResponse(
                page.data().stream().map(ObSignoffAdminService::summaryOf).toList(),
                page.meta());
    }

    @Transactional(readOnly = true)
    ObSignoffAdminDtos.ObSignoffDetail get(ObClientScope scope, long signoffId) {
        return detailOf(reads.find(scope, signoffId)
                .orElseThrow(() -> ObSignoffNotFoundException.signoff(signoffId)));
    }

    // ────────────────────────────────────────────────────────────── writes

    /**
     * Ask a client to sign off a service, or the go-live.
     *
     * <p>The order of the checks is deliberate and is the order the contract
     * lists the refusals in: the caller may not see this journey (404) before
     * the body is coherent (400), before the subject may be signed off at all
     * (422), before somebody is already being asked (409). Doing the 409 first
     * would report "already pending" for a request that was malformed anyway.
     */
    @Transactional
    ObSignoffAdminDtos.ObSignoff request(ObClientScope scope, long journeyId,
                                         ObSignoffAdminDtos.ObSignoffRequestBody body,
                                         Long actorUserId) {

        ObSignoffAdminRepository.SignoffJourney journey = reads.findJourney(scope, journeyId)
                .orElseThrow(() -> ObSignoffNotFoundException.journey(journeyId));

        Long stepId = resolveStepId(journeyId, body.kind(), body.stepId());

        if (body.kind() == ObSignoffKind.GO_LIVE) {
            long outstanding = reads.unfinishedStepsOn(journeyId);
            if (outstanding > 0) {
                throw new ObSignoffJourneyIncompleteException(journeyId, outstanding);
            }
        }

        if (!reads.isActiveContactOf(journey.obClientId(), body.sentToContactId())) {
            throw new ObSignoffContactInvalidException(body.sentToContactId(), journey.obClientId());
        }

        existingPending(journeyId, body.kind(), stepId).ifPresent(open -> {
            throw new ObSignoffAlreadyPendingException(open.getId());
        });

        Instant now = clock.instant();
        String token = mintToken();

        ObSignoff signoff = new ObSignoff();
        signoff.setObClientId(journey.obClientId());
        signoff.setJourneyId(journeyId);
        signoff.setStepId(stepId);
        signoff.setKind(body.kind());
        signoff.setStatus(ObSignoffStatus.PENDING);
        signoff.setTokenHash(Digests.sha256Hex(token));
        signoff.setTokenExpiresAt(now.plus(TOKEN_TTL));
        signoff.setRequestedBy(actorUserId);
        signoff.setRequestedAt(now);
        signoff.setSentToContactId(body.sentToContactId());

        ObSignoff saved = signoffs.saveAndFlush(signoff);
        mail(saved, token);

        return summaryOf(reads.find(scope, saved.getId())
                .orElseThrow(() -> ObSignoffNotFoundException.signoff(saved.getId())));
    }

    /**
     * Mint a fresh link and kill the previous one, in one transaction.
     *
     * <p>{@code EXPIRED} is resendable and returns the row to {@code PENDING},
     * which is the whole point of the operation: a link that timed out in a
     * mailbox is the commonest reason to need a new one, and leaving the status
     * at {@code EXPIRED} beside a live token would put the row in a state
     * {@code ObSignoffTokens} refuses — it checks status <em>and</em> expiry, so
     * the client would follow a good link to a generic 401.
     *
     * <p>The OTP attempt counter resets with the token, in the contract's own
     * words: "a contact who mistyped a code three times and asked for a new link
     * should not inherit their own lockout — the lockout exists to slow an
     * attacker guessing at a link they hold, and the link they held is now
     * dead."
     */
    @Transactional
    ObSignoffAdminDtos.ObSignoff resend(ObClientScope scope, long signoffId) {
        ObSignoff signoff = requireVisible(scope, signoffId);

        if (signoff.getStatus() != ObSignoffStatus.PENDING
                && signoff.getStatus() != ObSignoffStatus.EXPIRED) {
            throw new ObSignoffSettledException(signoffId, signoff.getStatus(), "sent again");
        }

        String token = mintToken();
        signoff.setStatus(ObSignoffStatus.PENDING);
        signoff.setTokenHash(Digests.sha256Hex(token));
        signoff.setTokenExpiresAt(clock.instant().plus(TOKEN_TTL));
        // The OTP belonged to the dead link. Clearing the hash and the expiry
        // together keeps this row in the state a freshly requested one is in,
        // rather than in a fourth state only a resend can produce.
        signoff.setOtpHash(null);
        signoff.setOtpExpiresAt(null);
        signoff.setOtpAttempts(0);
        signoffs.saveAndFlush(signoff);

        mail(signoff, token);

        return summaryOf(reads.find(scope, signoffId)
                .orElseThrow(() -> ObSignoffNotFoundException.signoff(signoffId)));
    }

    /**
     * Withdraw a request that should not have been sent.
     *
     * <p>The row survives and the token stops working, because
     * {@code ObSignoffTokens} treats anything but {@code PENDING} as unusable —
     * so the status change <em>is</em> the revocation and no second mechanism is
     * needed. A {@code CANCELLED} sign-off "must read exactly like a token that
     * never existed", which it does, generically.
     *
     * <p>Cancelling an already-cancelled one is allowed and simply re-stamps the
     * record. There is nothing to protect: the link is already dead, and
     * refusing would mean an operator correcting the reason they typed has no
     * way to do it.
     */
    @Transactional
    ObSignoffAdminDtos.ObSignoff cancel(ObClientScope scope, long signoffId, String reason, Long actorUserId) {
        ObSignoff signoff = requireVisible(scope, signoffId);

        if (signoff.getStatus() == ObSignoffStatus.SIGNED
                || signoff.getStatus() == ObSignoffStatus.OBJECTED) {
            throw new ObSignoffSettledException(signoffId, signoff.getStatus(), "withdrawn");
        }

        signoff.setStatus(ObSignoffStatus.CANCELLED);
        signoff.setCancelledAt(clock.instant());
        signoff.setCancelledBy(actorUserId);
        signoff.setCancellationReason(reason);
        signoffs.saveAndFlush(signoff);

        return summaryOf(reads.find(scope, signoffId)
                .orElseThrow(() -> ObSignoffNotFoundException.signoff(signoffId)));
    }

    // ─────────────────────────────────────────────────────────────── guts

    /**
     * {@code ck_ob_signoffs_step_matches_kind} in Java, checked before the insert
     * so a mistake is named rather than surfacing as a constraint violation.
     *
     * <p>The {@code requires_signoff} check reads the step's own snapshot rather
     * than the live template — C-104's precedent, and the same field the
     * completion gate reads, so a request can never be accepted for a step the
     * gate will not consult.
     */
    private Long resolveStepId(long journeyId, ObSignoffKind kind, Long stepId) {
        if (kind == ObSignoffKind.GO_LIVE) {
            if (stepId != null) {
                throw ObSignoffKindMismatchException.stepForbidden();
            }
            return null;
        }
        if (stepId == null) {
            throw ObSignoffKindMismatchException.stepRequired();
        }

        ObJourneyStep step = steps.findById(stepId)
                .orElseThrow(() -> ObSignoffNotFoundException.step(stepId));

        if (!step.getJourneyId().equals(journeyId)) {
            // 404 rather than 422, on the row-scoping rule's own reasoning: a
            // caller who guessed an id belonging to another journey learns
            // nothing from this about whether it exists.
            throw ObSignoffNotFoundException.step(stepId);
        }
        if (!step.isRequiresSignoff()) {
            throw new ObSignoffNotSignoffableException(stepId);
        }
        return stepId;
    }

    private java.util.Optional<ObSignoff> existingPending(long journeyId, ObSignoffKind kind, Long stepId) {
        return kind == ObSignoffKind.GO_LIVE
                ? signoffs.findFirstByJourneyIdAndKindAndStatusOrderByIdAsc(
                        journeyId, kind, ObSignoffStatus.PENDING)
                : signoffs.findFirstByStepIdAndKindAndStatusOrderByIdAsc(
                        stepId, kind, ObSignoffStatus.PENDING);
    }

    private ObSignoff requireVisible(ObClientScope scope, long signoffId) {
        // Asked through the scoped reader first, so an out-of-scope row is a 404
        // before it is ever loaded as an entity. Reading it by id and then
        // checking would work too and would be one query cheaper; it would also
        // be one refactor away from somebody acting on the entity before the
        // check, which is the shape this deliberately does not have.
        reads.find(scope, signoffId)
                .orElseThrow(() -> ObSignoffNotFoundException.signoff(signoffId));
        return signoffs.findById(signoffId)
                .orElseThrow(() -> ObSignoffNotFoundException.signoff(signoffId));
    }

    /** 256 bits, URL-safe and unpadded, so it survives a query string unescaped. */
    private static String mintToken() {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        return ENCODER.encodeToString(raw);
    }

    /**
     * Queue the mail carrying the one link that works.
     *
     * <p>The dedupe key names the token rather than the step, deliberately, and
     * {@code ClientAccountAdminService} made the same call for the same reason:
     * {@link ObNotification#aboutStep} would dedupe on (event, channel, step,
     * contact), so a resend while the first mail was still queued would be
     * swallowed — and the mail that got swallowed is the one carrying the only
     * live token. Two links, two mails; the second is the one that works.
     */
    private void mail(ObSignoff signoff, String token) {
        ObSignoffPageReader.Page page = pages.read(signoff);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("client_name", page.clientName());
        payload.put("step_title", signoff.getKind() == ObSignoffKind.GO_LIVE
                ? GO_LIVE_TITLE
                : page.stepTitle());
        // A relative path, on `ObMailLinks`' convention and
        // `ClientAccountAdminService`'s precedent: the origin belongs to
        // whatever renders the mail, not to a service with no idea what host it
        // is reachable on. `/signoff` is `PublicSignoffPage`'s own route.
        payload.put("action_url", "/signoff?token=" + token);
        if (page.productName() != null) {
            payload.put("product_name", page.productName());
        }

        outbox.enqueue(new ObNotification(
                ObNotificationEvent.SIGNOFF_REQUESTED.name(),
                ObChannel.EMAIL,
                new ObRecipient.Client(signoff.getSentToContactId()),
                signoff.getObClientId(),
                signoff.getJourneyId(),
                signoff.getStepId(),
                payload,
                ObNotificationEvent.SIGNOFF_REQUESTED.name()
                        + ":" + signoff.getId()
                        + ":" + signoff.getTokenExpiresAt().toEpochMilli()));
    }

    /**
     * One row, as the contract's {@code ObSignoff} — built from the scoped
     * reader's projection rather than from the entity, so that no write path can
     * accidentally return a field the read path is careful not to select.
     */
    private static ObSignoffAdminDtos.ObSignoff summaryOf(ObSignoffAdminRepository.Row row) {
        return new ObSignoffAdminDtos.ObSignoff(
                row.id(),
                row.obClientId(),
                row.journeyId(),
                row.stepId(),
                row.kind(),
                row.status(),
                ObSignoffAdminDtos.UserRef.of(row.requestedBy(), row.requestedByName()),
                row.requestedAt(),
                row.sentToContact(),
                row.tokenExpiresAt(),
                row.signedAt(),
                row.objectedAt(),
                row.hasCertificate());
    }

    private static ObSignoffAdminDtos.ObSignoffDetail detailOf(ObSignoffAdminRepository.Row row) {
        return new ObSignoffAdminDtos.ObSignoffDetail(
                summaryOf(row),
                row.signedByContact(),
                row.signedIp(),
                row.signedUserAgent(),
                row.objectionNote());
    }
}
