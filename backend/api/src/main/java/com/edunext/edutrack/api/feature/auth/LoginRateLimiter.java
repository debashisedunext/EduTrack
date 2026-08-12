package com.edunext.edutrack.api.feature.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * A-076 · the throttle on {@code POST /auth/login}, closing the two gaps
 * {@code AuthenticationService} recorded when A-021's per-IP limiter was built
 * and then deliberately removed.
 *
 * <h2>Why a plain per-IP limit was the wrong shape</h2>
 *
 * <p>Keying purely on IP puts an entire office behind one NAT address into a
 * single shared budget, so one colleague's typos lock out everyone around them.
 * That is worse than the gap it closes, which is why the earlier attempt was
 * withdrawn rather than shipped. The two gaps it left are both real:
 *
 * <ul>
 *   <li><b>Cost.</b> A locked account still runs the full Argon2id verify before
 *       the lock is consulted — ~175 ms and 64 MB per attempt, measured — so
 *       login is the cheapest denial-of-service target in the system.</li>
 *   <li><b>Volume.</b> {@code failed_attempts} stops repeated guessing at
 *       <i>one</i> account. It does nothing about one password sprayed across a
 *       thousand usernames, where no single account ever reaches five
 *       failures.</li>
 * </ul>
 *
 * <h2>Two dimensions, each catching what the other misses</h2>
 *
 * <ul>
 *   <li><b>Attempts per {@code (client, username)}.</b> Bounds the cost of
 *       guessing at one account from one source. Because the pair includes the
 *       username, a clumsy colleague only ever spends their own budget — the
 *       NAT problem does not arise.</li>
 *   <li><b>Distinct <i>failed</i> usernames per client.</b> The spray signature.
 *       One password against many names never trips the per-account counter, but
 *       it is unmistakable in the count of different names one source failed
 *       against.</li>
 * </ul>
 *
 * <h2>The spray set counts failures only, and that is what makes it safe</h2>
 *
 * <p><b>This is the decision that keeps the NAT problem from coming back.</b> A
 * cap on distinct usernames <i>attempted</i> per source would punish exactly the
 * shared-office case again: two hundred people signing in through one gateway
 * are two hundred distinct usernames before anything has gone wrong. Counting
 * only the names that <i>failed</i> means honest traffic contributes nothing at
 * all, however much of it there is, while a spray — which by construction fails
 * on nearly every name it tries — reaches the bound almost immediately.
 *
 * <h2>Checked before the KDF, and why that leaks nothing</h2>
 *
 * <p>The reason to check early is to avoid spending 175 ms and 64 MB on a
 * request already destined to be refused; a limiter that runs after the hash
 * saves nothing and leaves the DoS gap open. Running early looks like it should
 * be an enumeration oracle, and is not: <b>the budget is keyed on what was
 * submitted, never on what was found.</b> No lookup happens first, so a 429 for
 * a real username and a 429 for one that has never existed are emitted on
 * identical terms. The response is a statement about the caller, never about the
 * account.
 *
 * <p>That is precisely the distinction A-021 could not make. Its {@code 423} is
 * a statement about the account, so it has to come <i>after</i> the password
 * check or it confirms the account exists. This one does not, so it can come
 * first.
 *
 * <p>Identifiers are lower-cased before hashing. The username and email columns
 * are both {@code utf8mb4_0900_ai_ci}, so {@code Nikhil.Bansal} and
 * {@code nikhil.bansal} are one account to the database — keying on the raw
 * string would hand out a fresh budget per capitalisation and make the limit
 * trivially evadable.
 *
 * <p>Both keys are hashed for {@code PasswordResetRateLimiter}'s reason: Redis
 * is snapshotted, backed up and read over shoulders during incidents, and a
 * keyspace listing every username anyone ever failed to log in as is an
 * attacker's shortlist.
 *
 * <h2>Failure direction</h2>
 *
 * <p><b>An unreachable Redis lets the attempt through.</b> Refusing every login
 * while the cache is down turns a degraded cache into a total outage, to defend
 * against guessing that the per-account lockout still bounds. Same direction
 * {@link PasswordResetRateLimiter} and {@code RefreshTokenIssuer} take: degrade
 * the protection, never the person.
 */
@Component
class LoginRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);

    static final String PAIR_PREFIX = "edutrack:login-rate:pair:";
    static final String SPRAY_PREFIX = "edutrack:login-rate:spray:";

    /**
     * Seven attempts per {@code (client, username)} per fifteen minutes.
     *
     * <p>Sits deliberately above A-021's five-failure lockout rather than below
     * it. The account lockout is the mechanism that stops guessing, and it must
     * be allowed to happen — if this limit bit first, an account would never
     * lock, and the admin would never be told. Seven is the tightest bound that
     * still clears that sequence: five failures to apply the lock, a sixth
     * carrying the correct password so the {@code 423} can actually be reported,
     * and one spare.
     *
     * <p><b>Six would be the floor and is not it.</b> At six there is no margin —
     * a single stray request, a double-submitted form or a retried fetch, and the
     * user is throttled before ever being told their account is locked, which
     * reads as the system being broken rather than as a security measure.
     *
     * <p>Fixed rather than configurable, for {@code PasswordHashing}'s reason: a
     * tunable invites someone to raise it during an incident caused by it being
     * too high.
     */
    static final int MAX_PER_PAIR = 7;
    static final Duration PAIR_WINDOW = Duration.ofMinutes(15);

    /**
     * Twenty distinct failed usernames per source per fifteen minutes.
     *
     * <p>Far above honest traffic — successful sign-ins never enter this set, so
     * a busy shared gateway contributes only its genuine typos — and far below a
     * useful sweep. A source that has failed against twenty different accounts in
     * a quarter of an hour is not someone mistyping their own name.
     */
    static final int MAX_DISTINCT_FAILED_USERNAMES = 20;
    static final Duration SPRAY_WINDOW = Duration.ofMinutes(15);

    private final StringRedisTemplate redis;

    LoginRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Spends one unit of the pair budget and reports whether either dimension is
     * exhausted. Call before verifying the password.
     *
     * <p><b>The pair counter is incremented even on an attempt being refused</b>,
     * for {@link PasswordResetRateLimiter}'s reason: charging only the attempts
     * that get through lets a caller sit exactly at the limit forever, topping up
     * as the window slides.
     *
     * @param identifier the username or email as submitted — never resolved
     * @param clientKey  the caller's IP, or any stable per-source identifier
     * @return empty when the attempt may proceed; otherwise how long until the
     *         binding budget frees up, for {@code Retry-After}
     */
    Optional<Duration> checkAndSpend(String identifier, String clientKey) {
        try {
            Optional<Duration> pairRetry = spend(pairKey(identifier, clientKey), MAX_PER_PAIR, PAIR_WINDOW);
            Optional<Duration> sprayRetry = sprayBudget(clientKey);

            if (pairRetry.isEmpty()) {
                return sprayRetry;
            }
            if (sprayRetry.isEmpty()) {
                return pairRetry;
            }
            // The longer of the two: retrying sooner is refused again, and an
            // accurate Retry-After is the difference between a client that backs
            // off and one that hammers.
            return Optional.of(
                    pairRetry.get().compareTo(sprayRetry.get()) >= 0 ? pairRetry.get() : sprayRetry.get());
        } catch (DataAccessException e) {
            log.warn("auth: login rate limiting unavailable — allowing the attempt", e);
            return Optional.empty();
        }
    }

    /**
     * Records that this source failed against this identifier.
     *
     * <p>Called only for {@link InvalidCredentialsException} — not for a locked
     * account, and not for a pending two-factor challenge. <b>Both of those mean
     * the password was correct</b>, which is the opposite of the spray signature;
     * counting them would let a user with 2FA enabled inflate their own office's
     * budget every time they signed in.
     */
    void recordFailure(String identifier, String clientKey) {
        try {
            String key = SPRAY_PREFIX + Digests.sha256Hex(clientKey);
            redis.opsForSet().add(key, Digests.sha256Hex(normalise(identifier)));

            // Pin the window to the first failure in it, and repair a set left
            // without a TTL by a process that died between the two commands —
            // otherwise that source is throttled until someone notices.
            //
            // Keyed off the TTL rather than off SADD's return value: that counts
            // elements *added*, so a repeat failure against a name already in the
            // set returns 0, and a missing TTL would never be repaired.
            Long ttl = redis.getExpire(key);
            if (ttl == null || ttl < 0) {
                redis.expire(key, SPRAY_WINDOW);
            }
        } catch (DataAccessException e) {
            log.warn("auth: could not record a failed login for spray detection", e);
        }
    }

    /**
     * Clears the pair budget after a successful sign-in.
     *
     * <p>Safe to do: a caller who can authenticate already holds the password, so
     * there is nothing left for this budget to protect on that pair. Without it,
     * someone legitimately signing in and out through the day would eventually be
     * refused for succeeding.
     *
     * <p>The spray set is deliberately <b>not</b> cleared. It is keyed on the
     * source, not the account, so one attacker who happens to know one password
     * would otherwise wipe the evidence of every name they failed against.
     */
    void recordSuccess(String identifier, String clientKey) {
        try {
            redis.delete(pairKey(identifier, clientKey));
        } catch (DataAccessException e) {
            log.warn("auth: could not clear the login rate budget after a successful sign-in", e);
        }
    }

    /**
     * Reads the spray set without adding to it — this dimension is spent by
     * {@link #recordFailure}, not by asking.
     */
    private Optional<Duration> sprayBudget(String clientKey) {
        String key = SPRAY_PREFIX + Digests.sha256Hex(clientKey);
        Long distinct = redis.opsForSet().size(key);
        if (distinct == null || distinct <= MAX_DISTINCT_FAILED_USERNAMES) {
            return Optional.empty();
        }
        Long ttl = redis.getExpire(key);
        return Optional.of(Duration.ofSeconds(Math.max(ttl == null || ttl < 0 ? SPRAY_WINDOW.toSeconds() : ttl, 1)));
    }

    /** {@code INCR}, then {@code EXPIRE} only on the first hit. See {@link PasswordResetRateLimiter#spend}. */
    private Optional<Duration> spend(String key, int limit, Duration window) {
        Long count = redis.opsForValue().increment(key);
        if (count == null) {
            return Optional.empty();
        }

        Long ttlSeconds = redis.getExpire(key);
        if (count == 1 || ttlSeconds == null || ttlSeconds < 0) {
            redis.expire(key, window);
            ttlSeconds = window.toSeconds();
        }

        if (count <= limit) {
            return Optional.empty();
        }
        return Optional.of(Duration.ofSeconds(Math.max(ttlSeconds, 1)));
    }

    /**
     * The pair is hashed as one value rather than concatenated into the key, so a
     * username containing the separator cannot be crafted to collide with a
     * different {@code (client, username)} pair.
     */
    private static String pairKey(String identifier, String clientKey) {
        return PAIR_PREFIX + Digests.sha256Hex(clientKey + "" + normalise(identifier));
    }

    private static String normalise(String identifier) {
        return identifier == null ? "" : identifier.trim().toLowerCase(Locale.ROOT);
    }
}
