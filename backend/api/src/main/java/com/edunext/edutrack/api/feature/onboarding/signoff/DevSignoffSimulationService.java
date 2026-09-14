package com.edunext.edutrack.api.feature.onboarding.signoff;

import com.edunext.edutrack.api.feature.auth.Digests;
import com.edunext.edutrack.domain.onboarding.ObJourney;
import com.edunext.edutrack.domain.onboarding.ObJourneyRepository;
import com.edunext.edutrack.domain.onboarding.ObJourneyStep;
import com.edunext.edutrack.domain.onboarding.ObJourneyStepRepository;
import com.edunext.edutrack.domain.onboarding.ObSignoff;
import com.edunext.edutrack.domain.onboarding.ObSignoffKind;
import com.edunext.edutrack.domain.onboarding.ObSignoffRepository;
import com.edunext.edutrack.domain.onboarding.ObSignoffStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * A stand-in for the client, so a journey with a sign-off gate in the middle of
 * it can be driven end to end without one.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>{@code ObJourneyStepLifecycleService}'s completion gate refuses a step
 * whose {@code requires_signoff} snapshot is set until {@code ob_signoffs}
 * holds a {@code SIGNED STEP} row against it, and the only thing that writes
 * one is a customer following a mailed link and an OTP. On a demo box there is
 * no customer and no mailbox, so the ribbon stops at the first flagged service
 * and everything behind it stays {@code PENDING} — including the
 * {@code GO_LIVE} sign-off that flips the client to Live-Green, which cannot be
 * reached at all.
 *
 * <p>Two further facts make that a hard stop rather than an inconvenience. The
 * staff-side request route ({@code POST /onboarding/journeys/&#123;id&#125;/signoffs}) is in
 * the contract but <b>has no controller yet</b> — {@code SignoffPanel}'s own
 * note records the 404 — so nothing can even create the {@code PENDING} row a
 * client would accept. And {@code OnboardingFixture} seeds a
 * {@code SIGNOFF_REQUESTED} history line but no {@code ob_signoffs} row, so the
 * table is empty in the seeded corpus. Today a flagged step cannot be completed
 * against the real backend by any route at all.
 *
 * <h2>The gate is satisfied, never bypassed</h2>
 *
 * <p>This does not weaken the completion gate and does not reach around it. It
 * writes the same {@code PENDING} row the staff route will write, mints the
 * same Redis session a verified OTP mints, and hands it to the same
 * {@link ObSignoffAcceptService#accept} the public page calls. So the
 * unanswered-item and missing-document halves of the gate still refuse a step
 * that is genuinely unfinished, and they answer in the contract's own
 * {@code gateFailures} codes rather than by quietly succeeding.
 *
 * <p>That is the whole reason it goes the long way round. A shortcut that set
 * {@code status = SIGNED} directly, or a flag that made the gate skip its
 * sign-off clause, would be a second completion path enforcing different rules
 * — which is the bug PHASE-2-BUILD-PLAN §3 #4 already found in the prototype
 * and ruled against. One gate, one accept.
 *
 * <h2>It cannot exist outside a demo</h2>
 *
 * <p>{@code @Profile} rather than a property, and the two profiles that already
 * mean "this box is not real": {@code dev-noauth}, which turns authentication
 * off, and {@code fixtures}, which loads a fabricated corpus. On any other
 * profile this bean is absent, its controller is never mapped, and the route
 * 404s — so a production sign-off still has exactly one source, which is a
 * client.
 *
 * <p>A property would have been the wrong fence for what this writes.
 * {@code ob_signoffs} is the legal record of acceptance — signed name, IP, user
 * agent, and an archived PDF certificate — and a flag left on after a demo is a
 * standing ability to forge one. A profile has to be chosen at boot, by whoever
 * starts the process, and it is printed in the startup banner.
 */
@Service
@Profile({"dev-noauth", "fixtures"})
class DevSignoffSimulationService {

    /**
     * The fourteen days the contract gives a real link. Irrelevant to the
     * simulation — the row is accepted in the next statement — but a row
     * carrying a plausible expiry reads like a real one on OB-05, and one
     * expiring in the past would be a second thing to explain.
     */
    private static final Duration TOKEN_TTL = Duration.ofDays(14);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    /**
     * Who a simulated acceptance is recorded against when the caller does not
     * say. Deliberately not a plausible human name: this string lands in
     * {@code signed_name}, on the certificate PDF and in OB-05's evidence
     * panel, and anybody reading the row later should be able to tell at a
     * glance that no client typed it.
     */
    static final String DEFAULT_SIGNED_NAME = "Simulated client (demo)";

    static final String DEFAULT_NOTE =
            "Recorded by the demo sign-off simulator — no client was involved.";

    /**
     * Prefer the active primary SPOC, fall back to any active contact.
     *
     * <p>{@code sent_to_contact_id} is {@code NOT NULL} behind a foreign key,
     * so the row needs somebody. It is also who the acceptance ends up
     * attributed to: {@link ObSignoffAcceptService} copies that column into
     * {@code signed_by_contact_id} rather than trusting anything a caller
     * sends. The primary is the contact the real flow would have mailed.
     */
    private static final String ANY_ACTIVE_CONTACT = """
            SELECT id
              FROM ob_client_contacts
             WHERE ob_client_id = ?
               AND is_active = 1
             ORDER BY is_primary DESC, id ASC
             LIMIT 1
            """;

    private final ObSignoffRepository signoffs;
    private final ObJourneyRepository journeys;
    private final ObJourneyStepRepository steps;
    private final ObSignoffSessions sessions;
    private final ObSignoffAcceptService accepts;
    private final JdbcClient jdbc;
    private final Clock clock;

    /**
     * <p><b>{@code @Autowired} is load-bearing, for the reason
     * {@link ObSignoffAcceptService} spells out one class over.</b> This class
     * has two constructors and no {@code Clock} bean exists in the context, so
     * without an explicit choice Spring falls back to a no-arg constructor that
     * is not here and the context fails at startup.
     */
    @Autowired
    DevSignoffSimulationService(ObSignoffRepository signoffs,
                                ObJourneyRepository journeys,
                                ObJourneyStepRepository steps,
                                ObSignoffSessions sessions,
                                ObSignoffAcceptService accepts,
                                JdbcClient jdbc) {
        this(signoffs, journeys, steps, sessions, accepts, jdbc, Clock.systemUTC());
    }

    /** Test seam, on {@link ObSignoffAcceptService}'s own precedent one class over. */
    DevSignoffSimulationService(ObSignoffRepository signoffs,
                                ObJourneyRepository journeys,
                                ObJourneyStepRepository steps,
                                ObSignoffSessions sessions,
                                ObSignoffAcceptService accepts,
                                JdbcClient jdbc,
                                Clock clock) {
        this.signoffs = signoffs;
        this.journeys = journeys;
        this.steps = steps;
        this.sessions = sessions;
        this.accepts = accepts;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Requests a sign-off and accepts it in the client's place.
     *
     * <p>Returns the public surface's own {@code AcceptResult}, unchanged and
     * for the same reason it has the shape it has: a gate that refuses is
     * <b>not</b> an error here either. The acceptance is real and recorded, and
     * {@code gateFailures} names what our own side still owes — an unanswered
     * mandatory item, a required document nobody attached, a step that is not
     * {@code IN_PROGRESS}. Turning that into a 4xx would report "the simulation
     * failed" for the one outcome the simulation exists to reveal.
     */
    @Transactional
    PublicSignoffAcceptDtos.AcceptResult simulate(long journeyId,
                                                  ObSignoffKind kind,
                                                  Long stepId,
                                                  String signedName,
                                                  String note) {

        ObJourney journey = journeys.findById(journeyId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No journey " + journeyId));

        Long resolvedStepId = resolveStepId(journeyId, kind, stepId);
        long contactId = contactFor(journey.getObClientId());

        ObSignoff pending = createPending(journey, kind, resolvedStepId, contactId);

        // The same door OB-09 comes through: a session minted against this row,
        // spent by accept. Nothing here writes SIGNED itself.
        ObSignoffSessions.Minted session = sessions.mint(pending.getId());
        return accepts.accept(session.token(),
                blankTo(signedName, DEFAULT_SIGNED_NAME),
                blankTo(note, DEFAULT_NOTE),
                null);
    }

    /**
     * {@code ck_ob_signoffs_step_matches_kind} in Java, checked before the
     * insert so a mistake is named rather than surfacing as a constraint
     * violation on a route nobody is debugging.
     *
     * <p>The {@code requires_signoff} check reads the step's own snapshot
     * rather than the live template — C-104's precedent, and the same field the
     * gate itself reads. Simulating against a step that was never flagged would
     * write a row the gate never asks about: harmless, but an acceptance in the
     * record for something no client was ever going to be shown.
     */
    private Long resolveStepId(long journeyId, ObSignoffKind kind, Long stepId) {
        if (kind == ObSignoffKind.GO_LIVE) {
            // Ignored rather than refused. A caller sending both is describing
            // a journey-wide acceptance while naming the step they happened to
            // be looking at, and the CHECK is what actually has to hold.
            return null;
        }
        if (stepId == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "A STEP sign-off names its step; stepId is required.");
        }
        ObJourneyStep step = steps.findById(stepId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No step " + stepId));

        if (!step.getJourneyId().equals(journeyId)) {
            // 404 rather than 422, on the row-scoping rule's own reasoning: a
            // caller who guessed an id belonging to another journey learns
            // nothing from this about whether it exists.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No step " + stepId);
        }
        if (!step.isRequiresSignoff()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Step " + stepId + " is not flagged for client sign-off, so nothing gates on one.");
        }
        return stepId;
    }

    /**
     * @throws ResponseStatusException 422 when the client has no active
     *                                 contact. The foreign key would refuse the
     *                                 insert anyway; "add a SPOC first" is the
     *                                 actionable version of that failure
     */
    private long contactFor(long obClientId) {
        return jdbc.sql(ANY_ACTIVE_CONTACT)
                .param(obClientId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Client " + obClientId + " has no active contact to record a sign-off "
                                + "against. Add a SPOC on the client first."));
    }

    /**
     * The row the staff request route will one day write.
     *
     * <p>The token is generated and immediately discarded, which is not dead
     * code: {@code token_hash} is {@code NOT NULL} and {@code UNIQUE}, so a
     * constant would collide on the second simulation. Hashing a fresh random
     * value keeps the column meaning what it means everywhere else — and keeps
     * this row, like every other, unable to yield a working link.
     */
    private ObSignoff createPending(ObJourney journey, ObSignoffKind kind, Long stepId, long contactId) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);

        Instant now = clock.instant();

        ObSignoff signoff = new ObSignoff();
        signoff.setObClientId(journey.getObClientId());
        signoff.setJourneyId(journey.getId());
        signoff.setStepId(stepId);
        signoff.setKind(kind);
        signoff.setStatus(ObSignoffStatus.PENDING);
        signoff.setTokenHash(Digests.sha256Hex(ENCODER.encodeToString(raw)));
        signoff.setTokenExpiresAt(now.plus(TOKEN_TTL));
        // requested_by stays null. It is a users foreign key, and no staff
        // member asked for this one; naming whoever clicked the button would
        // record a request that was never made.
        signoff.setRequestedAt(now);
        signoff.setSentToContactId(contactId);

        // Flushed rather than saved: accept() looks the row up by id, and the
        // id only exists once the IDENTITY insert has actually run.
        return signoffs.saveAndFlush(signoff);
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
