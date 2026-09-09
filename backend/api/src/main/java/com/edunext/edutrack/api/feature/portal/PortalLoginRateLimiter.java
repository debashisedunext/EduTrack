package com.edunext.edutrack.api.feature.portal;

import com.edunext.edutrack.api.feature.auth.Digests;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * C-121 · the throttle on {@code POST /portal/auth/login} and {@code
 * /portal/auth/redeem} — {@code LoginRateLimiter}'s two-dimensional shape
 * (attempts per pair, distinct-failures-per-source spray detection), its own
 * Redis keyspace so a portal attacker's budget and a staff one never share a
 * counter.
 *
 * <p>The portal is internet-facing in a way the staff login already is, so
 * the same bound applies unchanged: seven attempts per (client, identifier)
 * per fifteen minutes, twenty distinct failed identifiers per source per
 * fifteen minutes. Not reused directly because {@code LoginRateLimiter} is
 * package-private in {@code feature.auth}.
 */
@Component
class PortalLoginRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(PortalLoginRateLimiter.class);

    static final String PAIR_PREFIX = "edutrack:portal-login-rate:pair:";
    static final String SPRAY_PREFIX = "edutrack:portal-login-rate:spray:";

    static final int MAX_PER_PAIR = 7;
    static final Duration PAIR_WINDOW = Duration.ofMinutes(15);
    static final int MAX_DISTINCT_FAILED_IDENTIFIERS = 20;
    static final Duration SPRAY_WINDOW = Duration.ofMinutes(15);

    private final StringRedisTemplate redis;

    PortalLoginRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

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
            return Optional.of(pairRetry.get().compareTo(sprayRetry.get()) >= 0 ? pairRetry.get() : sprayRetry.get());
        } catch (DataAccessException e) {
            log.warn("portal auth: login rate limiting unavailable — allowing the attempt", e);
            return Optional.empty();
        }
    }

    void recordFailure(String identifier, String clientKey) {
        try {
            String key = SPRAY_PREFIX + Digests.sha256Hex(clientKey);
            redis.opsForSet().add(key, Digests.sha256Hex(normalise(identifier)));
            Long ttl = redis.getExpire(key);
            if (ttl == null || ttl < 0) {
                redis.expire(key, SPRAY_WINDOW);
            }
        } catch (DataAccessException e) {
            log.warn("portal auth: could not record a failed login for spray detection", e);
        }
    }

    void recordSuccess(String identifier, String clientKey) {
        try {
            redis.delete(pairKey(identifier, clientKey));
        } catch (DataAccessException e) {
            log.warn("portal auth: could not clear the login rate budget after a successful sign-in", e);
        }
    }

    private Optional<Duration> sprayBudget(String clientKey) {
        String key = SPRAY_PREFIX + Digests.sha256Hex(clientKey);
        Long distinct = redis.opsForSet().size(key);
        if (distinct == null || distinct <= MAX_DISTINCT_FAILED_IDENTIFIERS) {
            return Optional.empty();
        }
        Long ttl = redis.getExpire(key);
        return Optional.of(Duration.ofSeconds(Math.max(ttl == null || ttl < 0 ? SPRAY_WINDOW.toSeconds() : ttl, 1)));
    }

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

    private static String pairKey(String identifier, String clientKey) {
        return PAIR_PREFIX + Digests.sha256Hex(clientKey + "" + normalise(identifier));
    }

    private static String normalise(String identifier) {
        return identifier == null ? "" : identifier.trim().toLowerCase(Locale.ROOT);
    }
}
