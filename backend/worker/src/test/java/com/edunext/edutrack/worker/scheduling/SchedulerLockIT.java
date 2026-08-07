package com.edunext.edutrack.worker.scheduling;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-011 · proves the scheduler lock actually excludes.
 *
 * <p>Two {@link LockProvider}s over the same Redis stand in for two worker
 * instances — the deployment this exists to protect. Asserting against a
 * single provider would pass even if the lock were a no-op, since nothing
 * would be contending.
 */
@Testcontainers
class SchedulerLockIT {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;

    /** Two providers over one Redis: instance A and instance B. */
    private static LockProvider instanceA;
    private static LockProvider instanceB;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(
                REDIS.getHost(), REDIS.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();

        instanceA = new RedisLockProvider(connectionFactory, "edutrack:lock");
        instanceB = new RedisLockProvider(connectionFactory, "edutrack:lock");
    }

    @AfterAll
    static void disconnect() {
        connectionFactory.destroy();
    }

    @Test
    void onlyOneInstanceHoldsTheLock() {
        LockConfiguration slaScan = lock("slaScanner", Duration.ofMinutes(10));

        Optional<SimpleLock> held = instanceA.lock(slaScan);
        assertThat(held).as("the first instance to arrive takes the lock").isPresent();

        assertThat(instanceB.lock(slaScan))
                .as("a second instance must not run the same scan concurrently")
                .isEmpty();

        held.orElseThrow().unlock();
    }

    @Test
    void theLockIsAvailableAgainOnceReleased() {
        LockConfiguration digest = lock("dailyDigest", Duration.ofMinutes(10));

        instanceA.lock(digest).orElseThrow().unlock();

        Optional<SimpleLock> second = instanceB.lock(digest);
        assertThat(second).as("the next scheduled run must be able to proceed").isPresent();
        second.orElseThrow().unlock();
    }

    /**
     * The reason {@code lockAtMostFor} exists: an instance that dies mid-run
     * never unlocks, and without expiry the scanner would stop for good.
     *
     * <p>One second is the floor, not an arbitrary choice — the Redis provider
     * sets the key's TTL in whole seconds, so anything shorter rounds to zero
     * and Redis rejects the SET outright.
     */
    @Test
    void aLockFromACrashedInstanceExpires() throws Exception {
        // A LockConfiguration fixes its absolute expiry at construction, so
        // each attempt needs a fresh one under the same lock name — exactly
        // what a real scheduled run does on its next tick.
        String scanner = "stageSlaScanner-" + UUID.randomUUID();

        assertThat(instanceA.lock(named(scanner, Duration.ofSeconds(1))))
                .isPresent();                                 // acquired, then "crash" — never unlocked
        assertThat(instanceB.lock(named(scanner, Duration.ofSeconds(1))))
                .as("still held by the instance that died")
                .isEmpty();

        Thread.sleep(1500);

        Optional<SimpleLock> afterExpiry = instanceB.lock(named(scanner, Duration.ofSeconds(10)));
        assertThat(afterExpiry).as("a dead instance must not stall the scanner forever").isPresent();
        afterExpiry.orElseThrow().unlock();
    }

    @Test
    void differentScannersDoNotContend() {
        Optional<SimpleLock> sla = instanceA.lock(lock("ticketSla", Duration.ofMinutes(10)));
        Optional<SimpleLock> escalation = instanceB.lock(lock("escalation", Duration.ofMinutes(10)));

        assertThat(sla).isPresent();
        assertThat(escalation).as("one scanner must not block an unrelated one").isPresent();

        sla.orElseThrow().unlock();
        escalation.orElseThrow().unlock();
    }

    /** Unique per test, so ordering between tests cannot leak a held lock. */
    private static LockConfiguration lock(String name, Duration lockAtMostFor) {
        return named(name + "-" + UUID.randomUUID(), lockAtMostFor);
    }

    /** A config for an exact lock name, when a test needs two attempts at one. */
    private static LockConfiguration named(String name, Duration lockAtMostFor) {
        return new LockConfiguration(Instant.now(), name, lockAtMostFor, Duration.ZERO);
    }
}
