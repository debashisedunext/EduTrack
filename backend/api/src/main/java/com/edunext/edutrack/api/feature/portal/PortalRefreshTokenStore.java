package com.edunext.edutrack.api.feature.portal;

import com.edunext.edutrack.api.feature.auth.Digests;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * C-121 · the portal refresh token's home in Redis — {@code
 * edutrack:portal-refresh:<sha256>}, its own key prefix rather than a shared
 * one with {@code RefreshTokenStore}'s {@code edutrack:refresh:}.
 *
 * <p>{@code Digests.sha256Hex} is public and reused as-is (a hash of a
 * high-entropy value, exactly the case its own javadoc names). Nothing else
 * from {@code feature.auth}'s refresh machinery is imported: that class is
 * package-private, and this store is deliberately simpler than the staff one
 * it sits beside — <b>rotate-and-replace, no family or reuse-detection</b>.
 *
 * <p>That simplification is named rather than hidden. §10.1's device-bound
 * family/reuse-detection scheme exists to turn a stolen refresh token into a
 * detectable theft; the portal does not yet have it, so a copied portal
 * cookie is merely a live session rather than a raised alarm. Given the
 * mandatory Stream A security review this whole slice carries (plan OB5),
 * this is flagged for that review rather than quietly shipped as equivalent —
 * see this task's own summary.
 *
 * <p>What is kept from the staff design: the value is opaque (256 bits,
 * carries no claims), never leaves this class except inside a {@code
 * Set-Cookie} header, and the key is the token's hash rather than the token
 * itself, so a Redis snapshot yields no working session.
 */
@Component
class PortalRefreshTokenStore {

    private static final Logger log = LoggerFactory.getLogger(PortalRefreshTokenStore.class);

    static final String KEY_PREFIX = "edutrack:portal-refresh:";

    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    PortalRefreshTokenStore(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    void save(String tokenValue, StoredPortalRefreshToken token) {
        Duration ttl = Duration.between(Instant.now(), token.expiresAt());
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("portal refresh token expiresAt is not in the future");
        }
        redis.opsForValue().set(keyFor(tokenValue), write(token), ttl);
    }

    Optional<StoredPortalRefreshToken> find(String tokenValue) {
        String stored = redis.opsForValue().get(keyFor(tokenValue));
        if (stored == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(stored, StoredPortalRefreshToken.class));
        } catch (JsonProcessingException e) {
            log.warn("portal auth: stored refresh token could not be deserialised, treating as absent", e);
            return Optional.empty();
        }
    }

    /**
     * Takes the token out of circulation and reports whether this caller was
     * the one who did it — {@code RefreshTokenStore.claim}'s atomic-delete
     * idiom, so two simultaneous refreshes on one cookie cannot both rotate.
     */
    boolean claim(String tokenValue) {
        return Boolean.TRUE.equals(redis.delete(keyFor(tokenValue)));
    }

    void discard(String tokenValue) {
        redis.delete(keyFor(tokenValue));
    }

    private String write(StoredPortalRefreshToken token) {
        try {
            return json.writeValueAsString(token);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("portal refresh token could not be serialised", e);
        }
    }

    static String keyFor(String tokenValue) {
        return KEY_PREFIX + Digests.sha256Hex(tokenValue);
    }
}
