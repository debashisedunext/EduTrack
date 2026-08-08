package com.edunext.edutrack.api.feature.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-023 · the refresh store against a real Redis.
 *
 * <p>{@code RefreshTokenIssuerTest} proves what gets written; this proves it
 * survives the round trip through an actual broker, which is where the
 * interesting failures are: {@code Instant} serialisation depends on the
 * application's {@code ObjectMapper} being configured with JSR-310 support (it
 * is, via {@code spring.jackson}), and a mock cannot notice when that changes.
 *
 * <p>JPA and Flyway stay excluded, the same way {@code RealtimeRelayIT} does
 * it — this touches no tables and must not need a MySQL.
 */
@Testcontainers
@SpringBootTest
@EnableAutoConfiguration(exclude = {
        HibernateJpaAutoConfiguration.class,
        JpaRepositoriesAutoConfiguration.class,
        FlywayAutoConfiguration.class
})
class RefreshTokenStoreIT {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    RefreshTokenStore store;

    @Autowired
    StringRedisTemplate redis;

    private static StoredRefreshToken record(Duration ttl) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return new StoredRefreshToken(
                UUID.randomUUID().toString(),
                42L,
                UUID.randomUUID().toString(),
                StoredRefreshToken.fingerprintOf("Mozilla/5.0 Chrome/131.0.0.0"),
                now,
                now.plus(ttl));
    }

    @Test
    @DisplayName("a saved token reads back with every field intact")
    void roundTrips() {
        String value = "opaque-token-" + UUID.randomUUID();
        StoredRefreshToken saved = record(Duration.ofDays(7));

        store.save(value, saved);

        Optional<StoredRefreshToken> found = store.find(value);
        assertThat(found).isPresent();
        assertThat(found.get()).isEqualTo(saved);
    }

    /**
     * The property the whole design rests on. Anyone holding a Redis dump — an
     * RDB file on a backup volume, a {@code KEYS *} in a console during an
     * incident — must come away with nothing they can present as a session.
     */
    @Test
    @DisplayName("Redis never holds the token itself, only its SHA-256")
    void theRawTokenIsNowhereInRedis() {
        String value = "opaque-token-" + UUID.randomUUID();

        store.save(value, record(Duration.ofDays(7)));

        assertThat(redis.hasKey(value)).as("the raw value must not be a key").isFalse();
        assertThat(redis.keys("*"))
                .as("nor may it appear in any key")
                .noneMatch(key -> key.contains(value));
        assertThat(redis.opsForValue().get(RefreshTokenStore.keyFor(value)))
                .as("nor in the payload")
                .isNotNull()
                .doesNotContain(value);
    }

    @Test
    @DisplayName("expiry is Redis's job — the key carries a TTL, so nothing has to sweep")
    void theKeyExpiresOnItsOwn() {
        String value = "opaque-token-" + UUID.randomUUID();

        store.save(value, record(Duration.ofDays(7)));

        Long ttlSeconds = redis.getExpire(RefreshTokenStore.keyFor(value), TimeUnit.SECONDS);
        assertThat(ttlSeconds)
                .as("a token with no TTL outlives its own expiresAt and needs a cleanup job "
                        + "that will one day silently stop running")
                .isNotNull()
                .isBetween(Duration.ofDays(7).toSeconds() - 60, Duration.ofDays(7).toSeconds());
    }

    @Test
    @DisplayName("an already-expired key is gone rather than merely stale")
    void anExpiredTokenIsNotFound() throws Exception {
        String value = "opaque-token-" + UUID.randomUUID();
        store.save(value, record(Duration.ofSeconds(1)));

        assertThat(store.find(value)).isPresent();
        Thread.sleep(1_500);

        assertThat(store.find(value))
                .as("no code path filters on expiresAt — the storage layer is the enforcement")
                .isEmpty();
    }

    @Test
    @DisplayName("an unknown token finds nothing, and says so without throwing")
    void anUnknownTokenIsSimplyAbsent() {
        assertThat(store.find("a-token-that-was-never-issued")).isEmpty();
    }

    @Test
    @DisplayName("keys are namespaced — this Redis is shared with realtime and ShedLock")
    void keysAreNamespaced() {
        assertThat(RefreshTokenStore.keyFor("anything")).startsWith("edutrack:refresh:");
    }
}
