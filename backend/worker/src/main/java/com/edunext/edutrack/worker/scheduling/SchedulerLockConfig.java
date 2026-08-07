package com.edunext.edutrack.worker.scheduling;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * D-011 · scheduler locking, so a scanner running on three instances fires
 * once rather than three times.
 *
 * <p><strong>What this is not for.</strong> The mail outbox (D-010) is
 * deliberately <em>not</em> locked. {@code SELECT … FOR UPDATE SKIP LOCKED}
 * already makes concurrent claims disjoint, and running every instance at once
 * is how the outbox drains a burst. A scheduler lock there would pin the queue
 * to one instance and turn horizontal scaling off. Lock the scanners, which
 * read the whole table and act once per breach; leave the queue alone.
 *
 * <p>Redis rather than JDBC: the JDBC provider needs a {@code shedlock} table
 * and therefore a migration, for a lock holding no business data. Redis is
 * already required for refresh tokens and the JWT blacklist.
 *
 * <p>The tradeoff to know about: a Redis failover can drop a held lock, so a
 * scanner could double-fire in that window. That is acceptable for the work it
 * guards — the SLA scanner's writes are idempotent (setting {@code is_delayed}
 * twice is the same as once), and the mails it sends are covered by D-035's
 * per-recipient rate limit. It would not be acceptable for anything that
 * appends to the hash-chained tables, so those must not rely on this.
 */
@Configuration
@EnableSchedulerLock(
        // Ceiling for any @SchedulerLock that does not set its own. It exists
        // to release the lock if an instance dies mid-run without unlocking;
        // too short and a second instance starts while the first is still
        // working, too long and a crash stalls the scanner for that period.
        // Individual scanners override this with their own realistic bound.
        defaultLockAtMostFor = "PT10M")
public class SchedulerLockConfig {

    /** Keeps EduTrack's locks in their own keyspace on a shared Redis. */
    static final String LOCK_KEY_PREFIX = "edutrack:lock";

    @Bean
    public LockProvider lockProvider(RedisConnectionFactory connectionFactory) {
        return new RedisLockProvider(connectionFactory, LOCK_KEY_PREFIX);
    }
}
