package com.edunext.edutrack.worker.onboarding.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * B-110 · tuning for the onboarding outbox dispatcher. Bound from
 * {@code edutrack.ob-outbox.*}.
 *
 * <p>Its own block rather than a reuse of {@code edutrack.outbox} (D-010):
 * the two queues drain different tables to different populations, and a
 * mail burst on the ticketing side should be tunable without touching the
 * cadence a client's sign-off request leaves at.
 *
 * @param enabled      false switches the poller off without removing the beans
 * @param pollInterval delay between polls, measured from the end of the last one
 * @param batchSize    rows claimed per poll
 * @param lease        how long a claimed row stays SENDING before another
 *                     worker may reclaim it. Must exceed the slowest realistic
 *                     send, or an in-flight message is delivered twice
 * @param maxAttempts  total attempts before a row is marked FAILED
 * @param backoffBase  first retry delay; doubles per attempt
 * @param backoffCap   ceiling on the doubling
 */
@ConfigurationProperties("edutrack.ob-outbox")
public record ObOutboxProperties(
        boolean enabled,
        Duration pollInterval,
        int batchSize,
        Duration lease,
        int maxAttempts,
        Duration backoffBase,
        Duration backoffCap) {

    public ObOutboxProperties {
        if (batchSize < 1) {
            throw new IllegalArgumentException("edutrack.ob-outbox.batch-size must be at least 1");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("edutrack.ob-outbox.max-attempts must be at least 1");
        }
        if (lease == null || lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("edutrack.ob-outbox.lease must be a positive duration");
        }
        if (backoffBase == null || backoffBase.isNegative()) {
            throw new IllegalArgumentException("edutrack.ob-outbox.backoff-base must not be negative");
        }
        if (backoffCap == null || backoffCap.compareTo(backoffBase) < 0) {
            throw new IllegalArgumentException("edutrack.ob-outbox.backoff-cap must be at least backoff-base");
        }
    }

    /**
     * Exponential backoff for the retry after {@code attemptsSoFar} failures:
     * base × 2^(attemptsSoFar-1), capped. Not jittered — retries are spread by
     * each row's own failure time, not by a shared wake-up.
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
