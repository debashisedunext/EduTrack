package com.edunext.edutrack.api.feature.auth;

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
 * A-023 · the refresh token's home in Redis. Blueprint §10.1, "store refresh
 * jti in Redis (device-bound)".
 *
 * <p><b>Redis and not MySQL,</b> for the reason the blueprint picks it: the key
 * carries its own TTL, so a token expires without anything having to sweep for
 * it. A {@code refresh_tokens} table would need a scheduled delete, and the day
 * that job fails is the day expired sessions quietly keep working — a failure
 * that is invisible until it is audited. Here, expiry is the storage layer's
 * job and cannot silently stop happening.
 *
 * <p><b>The key is {@code SHA-256(token)}, not the token.</b> Redis persists to
 * disk, is backed up, and is the thing most likely to be inspected with a
 * console open during an incident. Keying on the raw value would mean anyone
 * who ever sees that data holds seven days of live sessions. Hashing costs a
 * microsecond and removes the entire class of exposure — the same reasoning
 * that stops us storing passwords, applied to a credential that is every bit as
 * usable. See {@link Digests} for why this is SHA-256 and not Argon2id.
 *
 * <p><b>There is no {@code update}.</b> A refresh token is never edited; A-024
 * rotates by writing a new key and revoking the old one. The read half is here
 * because a store that cannot be read back cannot be proven to have written
 * anything — {@code RefreshTokenStoreIT} uses it, and A-024/A-025 are its
 * production callers.
 */
@Component
class RefreshTokenStore {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenStore.class);

    /**
     * Namespaced because this Redis is shared — D-012's realtime relay channel
     * and the worker's ShedLock keys live in the same keyspace, and an
     * un-prefixed key is how two subsystems come to collide over one name.
     */
    static final String KEY_PREFIX = "edutrack:refresh:";

    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    RefreshTokenStore(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    /**
     * Writes the token with an expiry taken from the record itself, so the
     * Redis TTL and {@link StoredRefreshToken#expiresAt()} cannot disagree.
     *
     * <p>Throws on an unreachable broker rather than swallowing it — whether a
     * failure to persist should fail the login is a policy decision, and it is
     * made in {@link RefreshTokenIssuer}, not here.
     */
    void save(String tokenValue, StoredRefreshToken token) {
        Duration ttl = Duration.between(Instant.now(), token.expiresAt());
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("refresh token expiresAt is not in the future");
        }
        redis.opsForValue().set(keyFor(tokenValue), write(token), ttl);
    }

    /** Empty when the token is unknown, already revoked, or has expired. */
    Optional<StoredRefreshToken> find(String tokenValue) {
        String stored = redis.opsForValue().get(keyFor(tokenValue));
        if (stored == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(stored, StoredRefreshToken.class));
        } catch (JsonProcessingException e) {
            // A record this process cannot read is a record it cannot honour.
            // Treated as absent — the caller re-authenticates — rather than
            // raised, so one malformed entry left by an older format during a
            // rolling deploy costs one login instead of a 500.
            log.warn("refresh: stored token could not be deserialised, treating as absent", e);
            return Optional.empty();
        }
    }

    /**
     * Exposed so tests can assert on the key shape without duplicating the
     * derivation and thereby proving only that two copies of the same mistake
     * agree.
     */
    static String keyFor(String tokenValue) {
        return KEY_PREFIX + Digests.sha256Hex(tokenValue);
    }

    private String write(StoredRefreshToken token) {
        try {
            return json.writeValueAsString(token);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("refresh token could not be serialised", e);
        }
    }
}
