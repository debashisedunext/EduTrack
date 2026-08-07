package com.edunext.edutrack.worker.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tuning for the mail outbox (D-010). Bound from {@code edutrack.outbox.*}.
 *
 * @param enabled      false switches the poller off without removing the beans
 * @param pollInterval delay between polls, measured from the end of the last one
 * @param batchSize    rows claimed per poll
 * @param lease        how long a claimed row stays invisible to other workers.
 *                     Must exceed the slowest realistic send: if a send is
 *                     still in flight when the lease expires, a second worker
 *                     claims the same row and the recipient gets it twice.
 * @param maxAttempts  total attempts before a message is marked FAILED
 * @param backoffBase  first retry delay; doubles per attempt
 * @param backoffCap   ceiling on the doubling
 * @param transport    {@code logging} or {@code smtp}
 */
@ConfigurationProperties("edutrack.outbox")
public record OutboxProperties(
        boolean enabled,
        Duration pollInterval,
        int batchSize,
        Duration lease,
        int maxAttempts,
        Duration backoffBase,
        Duration backoffCap,
        String transport) {

    public OutboxProperties {
        if (batchSize < 1) {
            throw new IllegalArgumentException("edutrack.outbox.batch-size must be at least 1");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("edutrack.outbox.max-attempts must be at least 1");
        }
        if (lease == null || lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("edutrack.outbox.lease must be a positive duration");
        }
    }

    /**
     * Exponential backoff for the retry after {@code attemptsSoFar} failures:
     * base × 2^(attemptsSoFar-1), capped. Deliberately not jittered — retries
     * are spread by each row's own failure time, not by a shared wake-up.
     */
    public Duration backoffFor(int attemptsSoFar) {
        int exponent = Math.max(0, attemptsSoFar - 1);
        // Cap the shift before it can overflow a long.
        if (exponent >= 32) {
            return backoffCap;
        }
        Duration delay = backoffBase.multipliedBy(1L << exponent);
        return delay.compareTo(backoffCap) > 0 ? backoffCap : delay;
    }
}
