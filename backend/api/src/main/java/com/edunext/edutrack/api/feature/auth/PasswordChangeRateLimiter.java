package com.edunext.edutrack.api.feature.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * A-074 · the throttle on {@code currentPassword} guesses at
 * {@code PATCH /me/password}.
 *
 * <p>The last of the three holes {@code PasswordChangeService} recorded and
 * parked here. Its javadoc states the attack in one sentence: <i>"someone who
 * has stolen a token can guess the current password to escalate a session into
 * a full takeover"</i>. Until now that guessing was unbounded.
 *
 * <h2>Why A-021's lockout could not simply be reused</h2>
 *
 * <p>{@code PasswordChangeService} is explicit that failures here are
 * deliberately <b>not</b> counted towards the login lockout, and that decision
 * stands rather than being quietly reversed. Feeding this into the per-account
 * counter would hand an attacker holding a stolen token a way to lock the real
 * owner out of logging in at all — turning a session compromise into a denial
 * of service against the victim, on demand. A separate budget that refuses the
 * <i>change</i> and leaves <i>login</i> alone is the only shape that does not
 * create a new weapon out of the defence.
 *
 * <h2>One dimension, not two, and why this differs from the login throttle</h2>
 *
 * <p>{@link LoginRateLimiter} needs two dimensions because it is keyed on an
 * unauthenticated, unverified string: it cannot tell one caller from another
 * except by source address, so it needs a spray counter to catch one password
 * tried against many names. None of that applies here. This endpoint is behind
 * {@code isAuthenticated()}, so the caller has already presented a token we
 * issued and <b>the account is known before the first guess is scored</b>. The
 * budget is therefore keyed on the user id, which is the thing actually under
 * attack.
 *
 * <p>That also makes it immune to the NAT problem that caused A-021's per-IP
 * limiter to be withdrawn — an office behind one address shares no budget here,
 * because each person spends only their own.
 *
 * <h2>Five in fifteen minutes</h2>
 *
 * <p>Tighter than login's seven, and it can afford to be. Somebody changing
 * their own password knows it; a second attempt is a typo and a third is a
 * genuinely bad day. There is no equivalent of login's "five failures to apply
 * the lock, a sixth carrying the correct password so the 423 can be reported"
 * sequence to clear, which is what forced that number upwards.
 *
 * <p>The budget is spent on <b>failures only</b>. A successful change costs
 * nothing and clears the count, so somebody who mistypes twice and then succeeds
 * starts tomorrow with a full budget rather than a partly spent one. Counting
 * successes would mean an administrator rotating several passwords in a sitting
 * could refuse themselves.
 *
 * <h2>Checked before the KDF, like login's</h2>
 *
 * <p>Verifying {@code currentPassword} is an Argon2id comparison at ~175 ms and
 * 64 MB. Consulting a counter first means a caller who has exhausted their
 * budget cannot keep spending that, which is the same reasoning
 * {@link LoginRateLimiter} gives — and it is safe in the same way, because the
 * key is the authenticated user id and reveals nothing that the caller's own
 * token did not already establish.
 *
 * <h2>Fails open</h2>
 *
 * <p>If Redis is unreachable the change proceeds. Refusing every password change
 * while the cache is degraded would block the one action a user takes when they
 * believe they have been compromised, to defend against an attacker who must
 * already hold a valid access token. Same direction {@link LoginRateLimiter},
 * {@link PasswordResetRateLimiter} and {@code RefreshTokenIssuer} all take.
 */
@Component
class PasswordChangeRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(PasswordChangeRateLimiter.class);

    static final String KEY_PREFIX = "edutrack:password-change-rate:user:";

    /**
     * Five wrong guesses per window. See the class javadoc — deliberately
     * tighter than login's seven, because none of the reasons that number had to
     * clear the lockout sequence apply to a caller changing a password they
     * already know.
     */
    static final int MAX_FAILURES = 5;

    static final Duration WINDOW = Duration.ofMinutes(15);

    private final StringRedisTemplate redis;

    PasswordChangeRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Whether this user may attempt another {@code currentPassword} check.
     *
     * <p><b>Reads without spending.</b> The count is incremented by
     * {@link #recordFailure} once the guess is known to be wrong, not here —
     * which is what makes a correct change free. The alternative, spending on
     * every attempt, would refuse somebody their sixth <i>legitimate</i>
     * password change in a quarter of an hour, and the whole budget exists to
     * bound wrong guesses rather than to ration the feature.
     *
     * @param userId the authenticated caller — never a submitted string
     * @return empty when the attempt may proceed; otherwise how long until the
     *         budget frees up, for {@code Retry-After}
     */
    Optional<Duration> check(long userId) {
        try {
            String value = redis.opsForValue().get(key(userId));
            if (value == null) {
                return Optional.empty();
            }
            long failures;
            try {
                failures = Long.parseLong(value);
            } catch (NumberFormatException e) {
                // A value we did not write. Treat as no budget spent rather than
                // refusing on a key somebody else's tooling created.
                log.warn("auth: unreadable password-change counter for user {} — allowing", userId);
                return Optional.empty();
            }
            if (failures < MAX_FAILURES) {
                return Optional.empty();
            }
            Long ttl = redis.getExpire(key(userId));
            return Optional.of(Duration.ofSeconds(Math.max(ttl == null || ttl < 0 ? WINDOW.toSeconds() : ttl, 1)));
        } catch (DataAccessException e) {
            log.warn("auth: password-change rate limiting unavailable — allowing the attempt", e);
            return Optional.empty();
        }
    }

    /**
     * Records one wrong {@code currentPassword}.
     *
     * <p>{@code INCR} then {@code EXPIRE} only on the first hit, so the window is
     * fixed from the first failure rather than sliding forward with each one —
     * the same idiom, and the same reason, as {@link PasswordResetRateLimiter}.
     * A sliding window would let a caller sitting exactly at the limit hold the
     * key alive indefinitely.
     */
    void recordFailure(long userId) {
        try {
            Long count = redis.opsForValue().increment(key(userId));
            Long ttl = redis.getExpire(key(userId));
            if (count != null && (count == 1 || ttl == null || ttl < 0)) {
                redis.expire(key(userId), WINDOW);
            }
        } catch (DataAccessException e) {
            log.warn("auth: could not record a failed password-change attempt for user {}", userId, e);
        }
    }

    /**
     * Clears the budget after a successful change.
     *
     * <p>The person has just proved they know the current password, so the
     * evidence the counter represents is spent. Leaving it would mean two typos
     * this morning still counted against a genuine change this afternoon.
     */
    void recordSuccess(long userId) {
        try {
            redis.delete(key(userId));
        } catch (DataAccessException e) {
            log.warn("auth: could not clear the password-change counter for user {}", userId, e);
        }
    }

    private static String key(long userId) {
        return KEY_PREFIX + userId;
    }
}
