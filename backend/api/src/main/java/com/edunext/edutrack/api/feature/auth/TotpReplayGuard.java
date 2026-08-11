package com.edunext.edutrack.api.feature.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * A-029 · stops a TOTP code being used twice. RFC 6238 §5.2 asks for exactly
 * this: "the verifier MUST NOT accept the second attempt of the OTP after the
 * successful validation has been issued for the first OTP".
 *
 * <h2>The gap this closes</h2>
 *
 * <p>A code is valid for a whole 30-second step — and with a drift window of
 * one, for ninety seconds in total. Without this guard, anyone who observes a
 * code as it is used can replay it for the remainder of that window: over a
 * shoulder, from a screen-share, out of a phishing proxy that relays the
 * victim's login in real time. That last case is the realistic one, and it is
 * precisely what a second factor is supposed to defeat.
 *
 * <h2>Keyed on the time step, not the code</h2>
 *
 * <p>The stored key is {@code (userId, step)}, never the code itself. A code is
 * a live credential for the length of its window, and Redis is snapshotted,
 * backed up and inspected during incidents — the same reasoning that makes
 * {@code RefreshTokenStore} key on a hash rather than a token value, reached
 * here by noting that the step alone is enough to answer the only question
 * asked: "has this user already spent this step?"
 *
 * <p>The TTL is the window's own remaining life, so the guard is self-limiting
 * and needs no sweeper — an entry only has to outlive the step it protects.
 *
 * <h2>Failure direction</h2>
 *
 * <p><b>An unreachable Redis refuses the login.</b> This is the opposite of the
 * degrade-open choice {@code RefreshTokenIssuer} and
 * {@code PasswordResetRateLimiter} make, and deliberately so: those degrade a
 * convenience, whereas letting this one fail open silently removes a replay
 * defence at exactly the moment an attacker would most like it gone. A user
 * whose second factor cannot be checked properly should be asked to try again,
 * not waved through.
 */
@Component
class TotpReplayGuard {

    private static final Logger log = LoggerFactory.getLogger(TotpReplayGuard.class);

    static final String KEY_PREFIX = "edutrack:totp-used:";

    /** Presence is the whole signal; the value is never read. */
    private static final String USED = "1";

    private final StringRedisTemplate redis;

    TotpReplayGuard(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Claims one {@code (user, step)} pair, atomically.
     *
     * <p>{@code SET … NX} rather than a read-then-write: two requests carrying
     * the same code can arrive together, and a check followed by a separate
     * store is exactly the gap the second one fits through. Redis answers
     * whether <i>this</i> caller created the key, so precisely one can ever be
     * told it won — the same primitive {@code RefreshTokenStore#claim} relies on.
     *
     * @param ttl how long the entry must outlive — the remainder of the
     *            acceptance window
     * @return true if the step was unused and is now claimed; false if it had
     *         already been spent
     * @throws DataAccessException if the store cannot answer, which the caller
     *                             must treat as a refusal rather than a pass
     */
    boolean claim(long userId, long timeStep, Duration ttl) {
        Boolean claimed = redis.opsForValue().setIfAbsent(keyFor(userId, timeStep), USED, ttl);
        if (Boolean.FALSE.equals(claimed)) {
            // WARN rather than INFO: a correct code arriving twice is either a
            // double-submitted form or somebody replaying an observed code, and
            // the second is worth being able to find in a log.
            log.warn("auth: TOTP replay refused for user {} — time step {} had already been used",
                    userId, timeStep);
        }
        return Boolean.TRUE.equals(claimed);
    }

    /** Exposed so tests assert on the real key rather than a second copy of the rule. */
    static String keyFor(long userId, long timeStep) {
        return KEY_PREFIX + userId + ":" + timeStep;
    }
}
