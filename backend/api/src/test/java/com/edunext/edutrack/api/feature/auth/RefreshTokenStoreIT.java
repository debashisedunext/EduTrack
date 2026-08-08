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

    // ── A-024 · claiming, consumption and family revocation ─────────────────

    /**
     * The property the whole race-safety argument rests on, and the one a mock
     * cannot establish. Two refreshes of one token arrive together — two tabs
     * waking, or a retry of a request whose response was lost. A read-then-delete
     * would let both through and fork the session; {@code DEL} is atomic, so
     * exactly one caller can ever be told it won.
     */
    @Test
    @DisplayName("exactly one caller can ever claim a given token")
    void claimingIsAtomicAndHappensOnce() {
        String value = "opaque-token-" + UUID.randomUUID();
        store.save(value, record(Duration.ofDays(7)));

        assertThat(store.claim(value)).as("the first caller wins").isTrue();
        assertThat(store.claim(value)).as("every later caller is presenting a spent token").isFalse();
        assertThat(store.find(value)).isEmpty();
    }

    @Test
    @DisplayName("claiming a token that was never issued reports false rather than throwing")
    void claimingAnUnknownTokenIsFalse() {
        assertThat(store.claim("a-token-that-was-never-issued")).isFalse();
    }

    /**
     * Without this marker a rotated-away token and a value that never existed are
     * the same absence, and §10.1's reuse rule cannot be enforced at all.
     */
    @Test
    @DisplayName("a consumed token is remembered by family, so reuse stays detectable")
    void consumptionIsRemembered() {
        String value = "opaque-token-" + UUID.randomUUID();
        StoredRefreshToken token = record(Duration.ofDays(7));
        store.save(value, token);
        store.claim(value);

        store.markConsumed(value, token.familyId(), token.expiresAt());

        assertThat(store.find(value)).as("no longer live").isEmpty();
        assertThat(store.findConsumedFamily(value))
                .as("but remembered as spent — this is what turns a replay into a detection")
                .contains(token.familyId());
    }

    @Test
    @DisplayName("a token that was never issued has no consumed marker either")
    void anUnknownTokenWasNeverConsumed() {
        assertThat(store.findConsumedFamily("a-token-that-was-never-issued")).isEmpty();
    }

    /**
     * The marker is a key like any other and must not outlive the decision it
     * informs — after the token's own expiry a presentation is refused regardless.
     */
    @Test
    @DisplayName("the consumed marker expires with the token it describes")
    void theConsumedMarkerCarriesItsOwnTtl() {
        String value = "opaque-token-" + UUID.randomUUID();
        StoredRefreshToken token = record(Duration.ofDays(7));

        store.markConsumed(value, token.familyId(), token.expiresAt());

        Long ttlSeconds = redis.getExpire(
                "edutrack:refresh-consumed:" + Digests.sha256Hex(value), TimeUnit.SECONDS);
        assertThat(ttlSeconds).isNotNull()
                .isBetween(Duration.ofDays(7).toSeconds() - 60, Duration.ofDays(7).toSeconds());
    }

    @Test
    @DisplayName("nothing is remembered for a token whose expiry has already passed")
    void anAlreadyExpiredTokenIsNotMarked() {
        String value = "opaque-token-" + UUID.randomUUID();

        // A negative TTL is rejected by Redis; recording it would be a 500 on a
        // path whose whole job is to end a session cleanly.
        store.markConsumed(value, "family-x", Instant.now().minusSeconds(30));

        assertThat(store.findConsumedFamily(value)).isEmpty();
    }

    /**
     * The tombstone is what reaches the successors an attacker minted before
     * detection — they are live and look perfect, and enumerating them would
     * need a family→members index that drifts.
     */
    @Test
    @DisplayName("a revoked family is remembered, and only that family")
    void revokingOneFamilyLeavesTheOthersAlone() {
        String revoked = "family-" + UUID.randomUUID();
        String other = "family-" + UUID.randomUUID();

        store.revokeFamily(revoked, Duration.ofDays(7));

        assertThat(store.isFamilyRevoked(revoked)).isTrue();
        assertThat(store.isFamilyRevoked(other))
                .as("revoking one login must not sign the user out of every device")
                .isFalse();
    }

    @Test
    @DisplayName("revoking twice is the same as revoking once")
    void revocationIsIdempotent() {
        String family = "family-" + UUID.randomUUID();

        store.revokeFamily(family, Duration.ofDays(7));
        store.revokeFamily(family, Duration.ofDays(7));

        assertThat(store.isFamilyRevoked(family)).isTrue();
    }

    /**
     * A tombstone that expires early resurrects a family that was revoked for
     * theft, so it is deliberately set to the maximum refresh lifetime rather
     * than to the family's actual remaining life.
     */
    @Test
    @DisplayName("the tombstone outlives every token that could belong to the family")
    void theTombstoneOutlivesItsFamily() {
        String family = "family-" + UUID.randomUUID();

        store.revokeFamily(family, Duration.ofDays(7));

        Long ttlSeconds = redis.getExpire(
                "edutrack:refresh-family-revoked:" + family, TimeUnit.SECONDS);
        assertThat(ttlSeconds).isNotNull()
                .isBetween(Duration.ofDays(7).toSeconds() - 60, Duration.ofDays(7).toSeconds());
    }

    /**
     * Three key spaces, and a hashed token key must never be mistaken for a
     * marker key. Nesting the markers under {@code edutrack:refresh:} would make
     * that a matter of luck about what a hex digest can spell.
     */
    @Test
    @DisplayName("live tokens, consumed markers and tombstones occupy separate namespaces")
    void theThreeKeySpacesDoNotOverlap() {
        String value = "opaque-token-" + UUID.randomUUID();
        StoredRefreshToken token = record(Duration.ofDays(7));

        store.save(value, token);
        store.markConsumed(value, token.familyId(), token.expiresAt());
        store.revokeFamily(token.familyId(), Duration.ofDays(7));

        assertThat(redis.keys(RefreshTokenStore.KEY_PREFIX + "*"))
                .as("a prefix scan for live tokens must not sweep up markers")
                .contains(RefreshTokenStore.keyFor(value))
                .noneMatch(key -> key.startsWith(RefreshTokenStore.CONSUMED_PREFIX))
                .noneMatch(key -> key.startsWith(RefreshTokenStore.FAMILY_REVOKED_PREFIX));
    }

    /**
     * Discarding is for refusals that are <i>not</i> reuse. Recording those as
     * consumed would make the next presentation look like theft and revoke a
     * family for a reason that never was one.
     */
    @Test
    @DisplayName("a discarded token leaves no consumed marker behind")
    void discardingIsNotConsuming() {
        String value = "opaque-token-" + UUID.randomUUID();
        store.save(value, record(Duration.ofDays(7)));

        store.discard(value);

        assertThat(store.find(value)).isEmpty();
        assertThat(store.findConsumedFamily(value))
                .as("a browser-update mismatch must not arm a theft detection")
                .isEmpty();
    }
}
