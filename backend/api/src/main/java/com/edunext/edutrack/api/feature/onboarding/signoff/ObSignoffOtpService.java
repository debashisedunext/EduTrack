package com.edunext.edutrack.api.feature.onboarding.signoff;

import com.edunext.edutrack.domain.onboarding.ObSignoff;
import com.edunext.edutrack.domain.onboarding.ObSignoffKind;
import com.edunext.edutrack.domain.onboarding.ObSignoffRepository;
import com.edunext.edutrack.domain.onboarding.outbox.ObChannel;
import com.edunext.edutrack.domain.onboarding.outbox.ObNotification;
import com.edunext.edutrack.domain.onboarding.outbox.ObNotificationEvent;
import com.edunext.edutrack.domain.onboarding.outbox.ObOutboxEnqueuer;
import com.edunext.edutrack.domain.onboarding.outbox.ObRecipient;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A-121 · issuing the one-time code and exchanging it for a session.
 *
 * <h2>The second factor exists because the link is not one</h2>
 *
 * <p>Plan §8: a sign-off link proves possession of a mailbox at some point in
 * the past. It does not prove that the person holding it now is the contact the
 * organisation asked, and a link forwarded, quoted in a reply chain or read out
 * of a shared inbox is the ordinary way that goes wrong. The OTP is what turns
 * possession into identity, and every decision below follows from that being
 * its only job.
 *
 * <h2>The recipient comes off the row, never out of the request</h2>
 *
 * <p>The contract says it in as many words: "the contact on the row, never an
 * address in the request. A body that could name its own recipient would let
 * anyone holding a leaked link redirect the code to themselves, which would
 * make the second factor a formality." {@link #issue} therefore takes no
 * address, and there is no overload that does.
 *
 * <h2>Issuing answers 202 whatever happens</h2>
 *
 * <p>Including for a token that never existed. That is the enumeration oracle
 * the whole public surface is built to close, and it is why this is written on
 * {@code resolveQuietly} rather than on {@code require} with a caught
 * exception — a caught exception is one {@code log.info} away from putting the
 * distinction back into a file somebody greps.
 *
 * <h2>Verifying counts attempts on the row, and exhausting them kills the token</h2>
 *
 * <p>A-107's DDL argues the storage: "a lockout that resets when the process
 * restarts is not a lockout". The contract argues the consequence: a fresh code
 * needs the staff-side resend, "which is a human deciding to re-ask rather than
 * a counter healing itself".
 */
@Service
public class ObSignoffOtpService {

    /**
     * Three wrong codes and the link is spent.
     *
     * <p>Six digits is a million possibilities and the rate limiter already
     * bounds the rate; this bounds the total, which is the part a limiter
     * cannot. Three is what a person reading a code off a screen needs — the
     * honest failure is a transposed digit, not a fourth attempt.
     */
    static final int MAX_ATTEMPTS = 3;

    /**
     * Ten minutes. Long enough to switch to a mail client, find the message and
     * type six digits; short enough that a code sitting in an inbox overnight
     * is not a standing key to the client's acceptance.
     */
    static final Duration OTP_TTL = Duration.ofMinutes(10);

    private final ObSignoffRepository signoffs;
    private final PublicSignoffAccess access;
    private final ObSignoffSessions sessions;
    private final ObSignoffPageReader pages;
    private final ObOutboxEnqueuer outbox;
    private final Clock clock;

    @Autowired
    ObSignoffOtpService(ObSignoffRepository signoffs,
                        PublicSignoffAccess access,
                        ObSignoffSessions sessions,
                        ObSignoffPageReader pages,
                        ObOutboxEnqueuer outbox) {
        this(signoffs, access, sessions, pages, outbox, Clock.systemUTC());
    }

    /**
     * Test seam, for {@code ObSignoffTokens}' reason: an expiry cannot be
     * asserted against a clock that only moves forwards.
     */
    ObSignoffOtpService(ObSignoffRepository signoffs,
                        PublicSignoffAccess access,
                        ObSignoffSessions sessions,
                        ObSignoffPageReader pages,
                        ObOutboxEnqueuer outbox,
                        Clock clock) {
        this.signoffs = signoffs;
        this.access = access;
        this.sessions = sessions;
        this.pages = pages;
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * Sends a code to the contact on the row, if the token names a usable one.
     *
     * <p>Returns nothing, deliberately. A boolean would be the oracle written
     * as a return type, and the controller would then have something to branch
     * on that it must not branch on.
     *
     * @throws SignoffRateLimitedException if the budget is spent — raised
     *                                     before the token is examined, so it
     *                                     says nothing about whether one exists
     */
    @Transactional
    public void issue(String token, HttpServletRequest request) {
        Optional<ObSignoff> resolved = access.resolveQuietly(token, request);
        if (resolved.isEmpty()) {
            return;
        }

        ObSignoff signoff = resolved.get();
        String code = ObSignoffOtpCodes.generate();
        Instant now = clock.instant();

        signoff.setOtpHash(ObSignoffOtpCodes.hash(code));
        signoff.setOtpExpiresAt(now.plus(OTP_TTL));
        // otp_attempts is deliberately NOT reset. A caller who has burnt two
        // attempts cannot buy three more by asking for another code — that
        // would make the persisted counter a formality and hand back the
        // unlimited grinding it exists to stop. It is cleared when a human
        // re-issues the sign-off from the staff side.
        signoffs.save(signoff);

        outbox.enqueue(new ObNotification(
                ObNotificationEvent.SIGNOFF_OTP.name(),
                ObChannel.EMAIL,
                new ObRecipient.Client(signoff.getSentToContactId()),
                signoff.getObClientId(),
                signoff.getJourneyId(),
                signoff.getStepId(),
                payload(code),
                dedupeKey(signoff, now)));
    }

    /**
     * Exchanges a proved code for a session and the page behind it.
     *
     * @throws InvalidSignoffTokenException for every failure — wrong code,
     *                                      expired code, expired token, unknown
     *                                      token, attempts exhausted, sign-off
     *                                      already settled. The contract: "a
     *                                      caller who can tell 'wrong code'
     *                                      from 'no such link' can enumerate
     *                                      links."
     */
    @Transactional
    public PublicSignoffOtpDtos.Session verify(String token, String otp, HttpServletRequest request) {
        ObSignoff signoff = access.require(token, request);
        Instant now = clock.instant();

        if (signoff.getOtpAttempts() >= MAX_ATTEMPTS
                || signoff.getOtpHash() == null
                || signoff.getOtpExpiresAt() == null
                || !signoff.getOtpExpiresAt().isAfter(now)) {
            throw new InvalidSignoffTokenException();
        }

        if (!ObSignoffOtpCodes.matches(otp, signoff.getOtpHash())) {
            // Counted before the refusal and inside the transaction, so a
            // caller who disconnects mid-request has still spent the attempt.
            signoff.setOtpAttempts(signoff.getOtpAttempts() + 1);
            signoffs.save(signoff);
            throw new InvalidSignoffTokenException();
        }

        // Spent on success too. The code is single-use — leaving the hash in
        // place would let the same six digits be replayed for as long as the
        // window lasts, from anywhere, which is the property the OTP is for.
        signoff.setOtpHash(null);
        signoff.setOtpExpiresAt(null);
        signoff.setOtpAttempts(0);
        signoffs.save(signoff);

        ObSignoffSessions.Minted minted = sessions.mint(signoff.getId());
        ObSignoffPageReader.Page page = pages.read(signoff);

        return new PublicSignoffOtpDtos.Session(
                minted.token(),
                now.plus(minted.ttl()),
                signoff.getKind(),
                page.clientName(),
                page.productName(),
                page.stepTitle(),
                page.checklist(),
                csatOffered(signoff));
    }

    /**
     * Whether OB-09 should render the survey question.
     *
     * <p><b>Answered from the kind alone today, and that is a known
     * shortfall.</b> The contract wants "true only on a {@code GO_LIVE} session
     * that has not already been surveyed", and there is no CSAT store to ask —
     * {@code submitObCsat} is unbuilt and Stream B owns it.
     *
     * <p>Erring true means a client who somehow surveyed twice sees the
     * question twice, and {@code submitObCsat} refuses the second with the
     * {@code 422} the contract already declares for exactly that case. Erring
     * false would hide the question from every legitimate first-time client.
     * Wrong in the direction a later route can correct rather than the one that
     * silently drops a feature. Revisit when the CSAT store lands.
     */
    private static boolean csatOffered(ObSignoff signoff) {
        return signoff.getKind() == ObSignoffKind.GO_LIVE;
    }

    /**
     * The mail's variables. {@code otp_code} is one of {@code SIGNOFF_OTP}'s
     * required pair.
     *
     * <p>{@code client_name} is not copied in here. The renderer resolves the
     * recipient and their client from the outbox row's own
     * {@code ob_client_id}, and a second copy in the payload is a second thing
     * to keep true.
     *
     * <p><b>How long the code stays readable in the queue is A-121's to
     * decide, and {@code ObNotificationEvent} says so.</b> The decision: it
     * stays. A sent row keeps its payload, so a support conversation can
     * establish what was sent and when — and the code is worthless ten minutes
     * later, single-use, and useless without the link it belongs to. Scrubbing
     * it would cost the audit trail to protect a value that has already
     * expired.
     */
    private static Map<String, Object> payload(String code) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("otp_code", code);
        payload.put("otp_expires_in", OTP_TTL.toMinutes() + " minutes");
        return payload;
    }

    /**
     * One queued code per sign-off per minute.
     *
     * <p>{@code uq_ob_outbox_queued} is unique over this while the row is
     * PENDING or SENDING, so a double-submitted form enqueues one mail rather
     * than two. The minute is what lets a client who genuinely did not receive
     * the first one ask again — the rate limiter bounds how often that can
     * happen, and this only stops the accidental duplicate.
     *
     * <p><b>The code is not in the key.</b> Every issue generates a fresh one,
     * so a key containing it would be unique every time and would dedupe
     * nothing at all.
     */
    private static String dedupeKey(ObSignoff signoff, Instant now) {
        return "SIGNOFF_OTP:" + signoff.getId() + ":" + now.getEpochSecond() / 60;
    }
}
