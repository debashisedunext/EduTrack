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
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-025 · the access-token blacklist against a real Redis.
 *
 * <p>{@code LogoutServiceTest} proves the right {@code jti} is handed over; this
 * proves what Redis actually ends up holding — above all the TTL, which is the
 * property that keeps the blacklist self-limiting and which a mock cannot
 * observe at all.
 *
 * <p>JPA and Flyway stay excluded, like {@code RefreshTokenStoreIT} — this
 * touches no tables and must not need a MySQL.
 */
@Testcontainers
@SpringBootTest
@EnableAutoConfiguration(exclude = {
        HibernateJpaAutoConfiguration.class,
        JpaRepositoriesAutoConfiguration.class,
        FlywayAutoConfiguration.class
})
class AccessTokenBlacklistIT {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    AccessTokenBlacklist blacklist;

    @Autowired
    StringRedisTemplate redis;

    private static String freshJti() {
        return UUID.randomUUID().toString();
    }

    @Test
    @DisplayName("a revoked jti reads back as revoked")
    void revokedTokensAreRecognised() {
        String jti = freshJti();

        blacklist.revoke(jti, Instant.now().plus(Duration.ofMinutes(15)));

        assertThat(blacklist.isRevoked(jti)).isTrue();
    }

    @Test
    @DisplayName("a jti nobody revoked is not revoked")
    void untouchedTokensAreNotRevoked() {
        assertThat(blacklist.isRevoked(freshJti()))
                .as("the default must be 'usable', or every request fails closed on an empty cache")
                .isFalse();
    }

    /**
     * The property that keeps this from growing without bound. An entry only has
     * to outlive the token it revokes; one second later the token is refused as
     * expired anyway, so the blacklist can never hold more than one
     * access-token-lifetime of logouts no matter how many people sign out.
     */
    @Test
    @DisplayName("the entry expires with the token, so the blacklist is self-limiting")
    void theEntryCarriesTheTokensOwnTtl() {
        String jti = freshJti();

        blacklist.revoke(jti, Instant.now().plus(Duration.ofMinutes(15)));

        Long ttlSeconds = redis.getExpire(AccessTokenBlacklist.keyFor(jti), TimeUnit.SECONDS);
        assertThat(ttlSeconds)
                .as("without a TTL the blacklist grows forever and eventually pages someone")
                .isNotNull()
                .isBetween(Duration.ofMinutes(15).toSeconds() - 30, Duration.ofMinutes(15).toSeconds());
    }

    @Test
    @DisplayName("the entry lapses on its own once the token would have expired")
    void theEntryLapsesWithoutASweeper() throws Exception {
        String jti = freshJti();
        blacklist.revoke(jti, Instant.now().plus(Duration.ofSeconds(1)));

        assertThat(blacklist.isRevoked(jti)).isTrue();
        Thread.sleep(1_500);

        assertThat(blacklist.isRevoked(jti))
                .as("nothing sweeps this — Redis expiry is the entire cleanup story")
                .isFalse();
    }

    /**
     * A zero or negative TTL is rejected by Redis outright, so an already-expired
     * token must be a no-op rather than a 500 on the logout path.
     */
    @Test
    @DisplayName("revoking an already-expired token is a harmless no-op")
    void anAlreadyExpiredTokenIsNotWritten() {
        String jti = freshJti();

        blacklist.revoke(jti, Instant.now().minus(Duration.ofMinutes(1)));

        assertThat(blacklist.isRevoked(jti))
                .as("the token is already unusable; the blacklist has nothing to add")
                .isFalse();
    }

    /**
     * The key is the {@code jti} — a random UUID that is useless to whoever
     * holds it — never the token, which is a bearer credential and must not be
     * written into a cache that gets snapshotted and backed up.
     */
    @Test
    @DisplayName("keys are namespaced and hold no credential")
    void keysAreNamespacedAndCarryNoSecret() {
        String jti = freshJti();

        blacklist.revoke(jti, Instant.now().plus(Duration.ofMinutes(15)));

        assertThat(AccessTokenBlacklist.keyFor(jti)).startsWith("edutrack:access-blacklist:");
        assertThat(redis.opsForValue().get(AccessTokenBlacklist.keyFor(jti)))
                .as("presence is the signal; the value must not carry the token")
                .isNotNull()
                .doesNotContain(".");
    }

    @Test
    @DisplayName("revoking one token leaves every other session alone")
    void revocationIsScopedToOneToken() {
        String revoked = freshJti();
        String untouched = freshJti();

        blacklist.revoke(revoked, Instant.now().plus(Duration.ofMinutes(15)));

        assertThat(blacklist.isRevoked(revoked)).isTrue();
        assertThat(blacklist.isRevoked(untouched))
                .as("one person signing out must not sign anyone else out")
                .isFalse();
    }
}
