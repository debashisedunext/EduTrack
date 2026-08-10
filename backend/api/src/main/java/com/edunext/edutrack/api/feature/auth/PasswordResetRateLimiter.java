package com.edunext.edutrack.api.feature.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * A-027 · the {@code 429} on {@code POST /auth/forgot-password}, which the
 * contract requires and nothing in this application previously implemented.
 *
 * <h2>Why this endpoint needs one when login does not yet have one</h2>
 *
 * <p>{@code AuthenticationService} documents that §10.1's "10 attempts / 15 min
 * / IP" was deliberately deferred to A-074, and that a purely per-IP limit is
 * the wrong shape because it puts a whole office behind one NAT address into a
 * shared budget. That reasoning is about <i>guessing</i>, where the attacker
 * needs many attempts and the honest user needs a few.
 *
 * <p>This endpoint has the opposite profile, and cannot wait for A-074. An
 * unlimited "email this address a password-reset link" endpoint is a mail
 * cannon: anyone on the internet can point it at a colleague and fill their
 * inbox, or sweep it across a list of addresses to find which ones the
 * organisation employs. Neither needs a single correct guess. The honest user
 * needs it once, maybe twice if the first mail went to spam — so a limit low
 * enough to stop abuse costs the real user nothing, which is rarely true of
 * rate limits and is what makes this one safe to ship now.
 *
 * <h2>Two dimensions, because each catches what the other misses</h2>
 *
 * <ul>
 *   <li><b>Per address.</b> Stops one victim being mail-bombed from many
 *       sources. An attacker rotating IPs still cannot exceed the per-address
 *       budget for the person they are targeting.</li>
 *   <li><b>Per client address.</b> Stops one source sweeping many addresses —
 *       the enumeration case, where no single email ever reaches its own
 *       limit.</li>
 * </ul>
 *
 * <p>Neither alone is sufficient, which is why the check is two-dimensional in
 * the way A-074's login limit will also have to be.
 *
 * <h2>The counter is keyed on what was submitted, never on what was found</h2>
 *
 * <p><b>This is the subtle one.</b> Counting only requests that matched a real
 * account would make the 429 itself an oracle: an address that can be requested
 * forever does not exist, one that starts refusing does. The budget is spent by
 * the <i>request</i>, before anything is looked up, so a known and an unknown
 * address are throttled identically — the same discipline
 * {@code AuthenticationService} applies to timing, applied to counting.
 *
 * <p>The address is hashed into the key rather than stored raw, for the reason
 * {@code RefreshTokenStore} hashes tokens: Redis is snapshotted, backed up and
 * read over shoulders during incidents, and a keyspace listing every address
 * that ever forgot its password is PII nobody needs to hold.
 *
 * <h2>Failure direction</h2>
 *
 * <p><b>An unreachable Redis lets the request through.</b> The alternative —
 * refusing every password reset while the cache is down — locks users out of
 * account recovery at exactly the moment the system is already unhealthy, to
 * defend against a nuisance. Sending a few unthrottled mails is the cheaper
 * failure, and it is the same direction {@code RefreshTokenIssuer} chose when
 * its store is unreachable: degrade the protection, never the person.
 */
@Component
class PasswordResetRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetRateLimiter.class);

    static final String EMAIL_PREFIX = "edutrack:pwreset-rate:email:";
    static final String CLIENT_PREFIX = "edutrack:pwreset-rate:client:";

    /**
     * Three links per address per fifteen minutes. Fixed rather than
     * configurable, for {@code PasswordHashing}'s reason: a tunable invites
     * someone to raise it during an incident caused by it being too high.
     */
    static final int MAX_PER_EMAIL = 3;
    static final Duration EMAIL_WINDOW = Duration.ofMinutes(15);

    /**
     * Forty per source per 30 minutes. Deliberately far above any honest use and
     * still far below a useful sweep — this bound exists to make enumeration
     * slow, not to be felt by anyone legitimate. A shared office address
     * genuinely running forty password resets in half an hour is an event worth
     * the one refusal it earns.
     */
    static final int MAX_PER_CLIENT = 40;
    static final Duration CLIENT_WINDOW = Duration.ofMinutes(30);

    private final StringRedisTemplate redis;

    PasswordResetRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Spends one unit of both budgets and reports whether either is exhausted.
     *
     * <p><b>Both counters are always incremented, including on a request that is
     * being refused.</b> Charging only the requests that get through would let a
     * caller sit exactly at the limit forever, topping up as the window slides;
     * charging every attempt means sustained abuse keeps the door shut rather
     * than reopening it once per window.
     *
     * @param email     the address as submitted — normalised, never resolved
     * @param clientKey the caller's IP, or any stable per-source identifier
     * @return empty when the request may proceed; otherwise how long until the
     *         binding budget frees up, for the {@code Retry-After} header
     */
    Optional<Duration> checkAndSpend(String email, String clientKey) {
        try {
            Optional<Duration> emailRetry = spend(
                    EMAIL_PREFIX + Digests.sha256Hex(email), MAX_PER_EMAIL, EMAIL_WINDOW);
            Optional<Duration> clientRetry = spend(
                    CLIENT_PREFIX + Digests.sha256Hex(clientKey), MAX_PER_CLIENT, CLIENT_WINDOW);

            // Both are spent before either is judged — see above. The longer of
            // the two is reported, because retrying sooner than that is refused
            // again and an accurate Retry-After is the difference between a
            // client that backs off and one that hammers.
            if (emailRetry.isEmpty()) {
                return clientRetry;
            }
            if (clientRetry.isEmpty()) {
                return emailRetry;
            }
            return Optional.of(
                    emailRetry.get().compareTo(clientRetry.get()) >= 0 ? emailRetry.get() : clientRetry.get());
        } catch (DataAccessException e) {
            log.warn("auth: password-reset rate limiting unavailable — allowing the request", e);
            return Optional.empty();
        }
    }

    /**
     * {@code INCR} then, only on the first hit, {@code EXPIRE}.
     *
     * <p>The order matters and the conditional matters. {@code INCR} creates the
     * key at 1 with no TTL, so the expiry has to be set after — and setting it
     * on <i>every</i> call would slide the window forward with each request,
     * meaning a caller who keeps knocking is never released. Setting it only
     * when the counter reads 1 pins the window to the first request in it, which
     * is what "3 per 15 minutes" means.
     *
     * <p>The gap between the two commands is real: a process dying in between
     * leaves a counter with no TTL, which would refuse that address forever. A
     * {@code TTL == -1} check repairs it on the next call rather than leaving a
     * permanent lockout behind a crash.
     */
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
}
