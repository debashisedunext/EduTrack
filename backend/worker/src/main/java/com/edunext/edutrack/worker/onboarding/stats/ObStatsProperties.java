package com.edunext.edutrack.worker.onboarding.stats;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * B-120 · tuning for the onboarding dashboard refresh. Bound from
 * {@code edutrack.ob-stats.*}.
 *
 * <p>Its own block rather than a reuse of {@code edutrack.stats} (A-051), for
 * the reason {@link com.edunext.edutrack.worker.onboarding.outbox.ObOutboxProperties}
 * gives about the two outboxes: the ticketing summary tables are recomputed
 * from a hot, high-volume corpus and this one from a few hundred journeys, so
 * the cadence that suits one is not evidence about the other. Sharing the key
 * would also mean that switching the ticketing refresh off in a test — which
 * {@code StatsRefreshIT} does on every run — silently switched this one off too.
 *
 * @param enabled        false stops the <em>schedule</em>. The beans still
 *                       exist, so a test can drive
 *                       {@link ObStatsRefreshWorker#refreshOnce()} itself —
 *                       {@code StatsRefreshWorker}'s argument for a field
 *                       rather than {@code @ConditionalOnProperty}.
 * @param refreshInterval delay between passes, measured from the end of the last
 * @param flowWindowDays how many days back each pass recomputes the <em>flow</em>
 *                       columns. Never applied to the stock columns; see
 *                       {@link ObDashboardStatsRepository} on why those are
 *                       today-only.
 * @param amberShare     the share of a step's TAT at which it turns AMBER.
 *                       The contract's {@code ObRag} description names 75% as
 *                       the default and calls it configurable; B-113 is where
 *                       it becomes a row rather than a property.
 */
@ConfigurationProperties("edutrack.ob-stats")
public record ObStatsProperties(
        boolean enabled,
        Duration refreshInterval,
        int flowWindowDays,
        BigDecimal amberShare) {

    public ObStatsProperties {
        if (flowWindowDays < 1) {
            throw new IllegalArgumentException("edutrack.ob-stats.flow-window-days must be at least 1");
        }
        if (amberShare == null
                || amberShare.compareTo(BigDecimal.ZERO) <= 0
                || amberShare.compareTo(BigDecimal.ONE) >= 0) {
            // Zero would paint every started step amber the instant it starts
            // and one would paint none of them until the moment it breaches —
            // in both cases the colour stops carrying information rather than
            // failing visibly, which is why this is rejected at startup.
            throw new IllegalArgumentException(
                    "edutrack.ob-stats.amber-share must be strictly between 0 and 1");
        }
    }
}
