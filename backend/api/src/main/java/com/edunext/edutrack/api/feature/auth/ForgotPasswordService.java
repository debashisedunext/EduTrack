package com.edunext.edutrack.api.feature.auth;

import com.edunext.edutrack.domain.outbox.NewMail;
import com.edunext.edutrack.domain.outbox.OutboxEnqueuer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * A-027 · {@code POST /auth/forgot-password}. Blueprint §10.3, screen S-02.
 *
 * <h2>The one rule this class exists to hold</h2>
 *
 * <p><b>The response does not depend on whether the address exists.</b> The
 * contract states it outright — "always returns 202, whether or not the address
 * is known. A different response for unknown addresses is a user-enumeration
 * oracle" — and it is the same property {@code AuthenticationService} spends its
 * whole design protecting at login, arriving here by a different route.
 *
 * <p>It is easy to lose. Every one of these breaks it, and every one of them is
 * a natural thing to write:
 *
 * <ul>
 *   <li>Returning 404 for an unknown address. The obvious REST answer, and it
 *       turns the endpoint into a staff directory.</li>
 *   <li>Returning a different body — "we've sent a mail" versus "no account
 *       found" — which is the same leak wearing a friendlier face.</li>
 *   <li>Throwing on an unknown address and letting a handler decide. The
 *       decision then lives somewhere this class cannot see.</li>
 *   <li><b>Rate-limiting only real addresses.</b> Subtler and just as fatal: an
 *       address that can be requested forever does not exist, one that starts
 *       answering 429 does. {@link PasswordResetRateLimiter} is therefore spent
 *       <i>before</i> the lookup, by the caller.</li>
 * </ul>
 *
 * <p>Timing is the one channel not closed here, and that is a deliberate,
 * bounded call rather than an oversight. A known address costs an insert and an
 * outbox write; an unknown one returns after a single indexed {@code SELECT}.
 * The difference is a few milliseconds against a network, versus the ~175 ms
 * gulf A-020's decoy hash exists to close — and the fix (a decoy insert into a
 * table that would then need cleaning) trades a measurable oracle for a real
 * one. Named rather than hidden, as A-021 named its own.
 *
 * <h2>What is deliberately not done on a request</h2>
 *
 * <p><b>Outstanding tokens are not invalidated when a new one is issued.</b> The
 * tidier design retires the previous link so only the newest works. It also
 * hands anyone an eviction primitive: repeatedly requesting a reset for a
 * victim's address invalidates the link they are in the middle of clicking, and
 * account recovery becomes something a stranger can hold shut. The rate limiter
 * bounds how many can pile up; {@link ResetPasswordService} retires them all at
 * the moment one is redeemed, which is the point at which retiring them is
 * unambiguously right.
 */
@Service
class ForgotPasswordService {

    private static final Logger log = LoggerFactory.getLogger(ForgotPasswordService.class);

    /** 256 bits, the size {@code RefreshTokenIssuer} mints for the same reason. */
    private static final int TOKEN_BYTES = 32;

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    /** Not one of §4B.6's ticket events — that vocabulary is scoped to tickets. */
    private static final String EVENT_CODE = "PASSWORD_RESET";

    private final AuthUserRepository users;
    private final PasswordResetTokenRepository tokens;
    private final PasswordResetProperties properties;
    private final OutboxEnqueuer outbox;
    private final SecureRandom random = new SecureRandom();

    ForgotPasswordService(AuthUserRepository users,
                          PasswordResetTokenRepository tokens,
                          PasswordResetProperties properties,
                          OutboxEnqueuer outbox) {
        this.users = users;
        this.tokens = tokens;
        this.properties = properties;
        this.outbox = outbox;
    }

    /**
     * Issues a reset token and queues the mail, or does nothing at all — and
     * says the same thing either way.
     *
     * <p>Transactional so the token row and the outbox row commit together. A
     * token with no mail is a link nobody receives; a mail with no token is a
     * link that cannot work. {@code OutboxEnqueuer} joins the caller's
     * transaction precisely so both land or neither does.
     *
     * @param email the address as submitted
     */
    @Transactional
    void requestReset(String email) {
        Optional<AuthUserRow> found = users.findByEmail(email);

        if (found.isEmpty()) {
            // DEBUG and without the address, for A-020's reason: an INFO line per
            // request writes a list of addresses somebody probed into a file with
            // far wider read access than the users table.
            log.debug("auth: password reset requested for an address with no account");
            return;
        }

        AuthUserRow user = found.get();

        if (!user.active()) {
            // A deactivated account must not be recoverable by its former holder
            // — that is the entire point of deactivating it. Silent, and
            // indistinguishable from every other outcome.
            log.info("auth: password reset refused for user {} — account is deactivated", user.id());
            return;
        }

        String token = mint();
        Instant expiresAt = Instant.now().plus(properties.ttl());
        tokens.insert(user.id(), Digests.sha256Hex(token), expiresAt);

        // The raw token leaves this method exactly once, into the mail. It is
        // never logged, never returned, and never stored — the row above holds
        // only its SHA-256.
        outbox.enqueue(new NewMail(null, EVENT_CODE, null, user.id(), user.email(),
                resetSubjectCarryingTheLink(token)));

        log.info("auth: password reset link issued for user {}, valid for {} minutes",
                user.id(), properties.ttl().toMinutes());
    }

    /**
     * <b>A stand-in, and a real gap this method names rather than hides.</b>
     * {@code email_log} has no body column — {@code OutboxMessage}'s own javadoc
     * says the body is meant to come from {@code notification_templates} at send
     * time (D-029/D-030), which do not exist. That leaves the worker nothing to
     * render <i>from</i>: the raw token is minted here, hashed immediately, and
     * never stored anywhere the worker could later read it back. Without this,
     * the mail this method queues would carry no way to complete the reset at
     * all — not a cosmetic omission, a broken feature.
     *
     * <p>The subject is the only per-message channel {@code email_log} offers
     * today, so the link goes there. This is exactly the same stopgap
     * {@code SmtpMailTransport} already uses for the body — "the body is still
     * the subject line" — extended to carry a link instead of a label.
     *
     * <p><b>Delete this method the day D-029/D-030 land</b>, and give the reset
     * template a real body with the token passed through whatever templating
     * context they introduce. Nothing about the token's lifecycle changes: it is
     * still minted once, hashed at rest, and this is still the only place the
     * raw value is ever handed to something outside this method.
     */
    private String resetSubjectCarryingTheLink(String token) {
        return "[EduTrack] Reset your password: %s?token=%s".formatted(properties.baseUrl(), token);
    }

    /** Base64url so the value survives a query string without percent-encoding. */
    private String mint() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }
}
