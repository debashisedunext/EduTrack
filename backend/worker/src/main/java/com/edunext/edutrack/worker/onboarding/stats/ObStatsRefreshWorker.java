package com.edunext.edutrack.worker.onboarding.stats;

import com.edunext.edutrack.domain.masters.WorkingCalendarRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

/**
 * B-120 · keeps {@code ob_dashboard_summary} and
 * {@code ob_implementor_daily_stats} current.
 *
 * <p>A-108 declared both tables and left them empty. CLAUDE.md forbids a live
 * {@code COUNT(*)} behind a dashboard, so OB-02, the reports hub and both v1.2
 * grids have no other source, and until this runs each of them draws a board of
 * zeroes rather than an error. This class is the whole of what fills them;
 * {@link ObDashboardStatsRepository} is the arithmetic.
 *
 * <h2>Two passes with different reaches, and the reason is in the data</h2>
 *
 * <p>The stock columns — RAG, the journey buckets, the workload grid — are
 * <em>current</em> values with no history behind them, so a past day's stock can
 * be recorded but never recomputed. The flow columns derive from immutable
 * timestamps and can. So each pass rewrites today completely and recomputes only
 * the flow half of a trailing window. The repository's class note carries the
 * full argument; the shape to remember here is that
 * <b>{@code refresh*Stock} is only ever called with today's date</b>.
 *
 * <h2>The day is the organisation's, not UTC's</h2>
 *
 * <p>{@code stat_date} comes from {@code WorkingCalendar.zone()} rather than
 * {@code ZoneOffset.UTC} as A-051's ticketing worker uses. The tables behind
 * this one answer "what is due today" and "what went live today" for a team
 * sitting in one office, and in IST a UTC day boundary moves both questions five
 * and a half hours — enough that the first pass of the morning would report
 * yesterday's board and nothing would look broken. The calendar is the only
 * place the organisation states its own zone, and every SLA figure in the system
 * already reads it.
 *
 * <h2>What a failure costs</h2>
 *
 * <p>Latency, and only for the flow half. Every figure is recomputed from source
 * rather than accumulated, so a pass that dies leaves the previous rows in place
 * and the next pass produces the same numbers. The one thing a long outage does
 * cost is stock history: nothing records what was amber during it, and nothing
 * can. That is A-108's stated property of the table rather than a defect here.
 */
@Component
public class ObStatsRefreshWorker {

    private static final Logger log = LoggerFactory.getLogger(ObStatsRefreshWorker.class);

    private final ObDashboardStatsRepository stats;
    private final WorkingCalendarRepository calendars;
    private final ObStatsProperties props;
    private final Clock clock;

    ObStatsRefreshWorker(ObDashboardStatsRepository stats,
                         WorkingCalendarRepository calendars,
                         ObStatsProperties props,
                         Clock clock) {
        this.stats = stats;
        this.calendars = calendars;
        this.props = props;
        this.clock = clock;
    }

    /**
     * <b>{@code fixedDelay} fires once at context startup</b> and then waits —
     * lengthening the interval in a test does not prevent that first pass, which
     * is the trap {@code StatsRefreshWorker} documents after it cost two
     * integration re-runs. A suite that also calls {@link #refreshOnce()} has two
     * passes over the same rows, and they contend: the stock passes DELETE and
     * rewrite the same day. Every test here therefore sets
     * {@code edutrack.ob-stats.enabled=false} and drives {@link #refreshOnce()}
     * itself.
     */
    @Scheduled(fixedDelayString = "${edutrack.ob-stats.refresh-interval:PT5M}")
    @SchedulerLock(name = "obStatsRefresh", lockAtMostFor = "PT4M", lockAtLeastFor = "PT30S")
    public void refresh() {
        if (!props.enabled()) {
            return;
        }
        try {
            refreshOnce();
        } catch (RuntimeException e) {
            // Recomputed from source next pass, so this costs staleness and
            // nothing else. Logged at error because a dashboard that is quietly
            // hours old is indistinguishable from a quiet week.
            log.error("ob-stats: refresh failed, retrying at the next interval", e);
        }
    }

    /**
     * One pass. Returns how many days of flow it recomputed, today included.
     *
     * <p><b>Not {@code @Transactional} as a whole, deliberately.</b> Each
     * repository method is its own transaction, so a pass that fails on day four
     * leaves days one to three committed rather than rolling back a correct
     * recomputation because a later one broke. The unit of atomicity that
     * matters is the day — a dashboard must never read one day's journey counts
     * beside another pass's cards — and that boundary is on the repository
     * methods, where it also survives being called directly by a test.
     */
    public int refreshOnce() {
        ZoneId zone = calendars.getCalendar().zone();
        Instant now = clock.instant();
        Instant computedAt = now;
        LocalDate today = LocalDate.ofInstant(now, zone);

        ObStatsDay currentDay = ObStatsDay.of(today, zone);
        stats.refreshSummaryStock(currentDay, now, computedAt, props.amberShare());
        stats.refreshImplementorStock(currentDay, now, computedAt, props.amberShare());

        int days = 0;
        // Oldest first, so a partially caught-up board fills from the left. A gap
        // in the middle of a trend reads as "nothing happened then", which is a
        // different and wrong statement — A-051's argument for its own backfill
        // order, and it applies to a recovered outage just as well.
        LocalDate from = flowWindowStart(today, props.flowWindowDays(), stats.genesis());
        for (LocalDate day = from; !day.isAfter(today); day = day.plusDays(1)) {
            ObStatsDay d = ObStatsDay.of(day, zone);
            stats.refreshSummaryFlow(d, computedAt);
            stats.refreshImplementorFlow(d, computedAt);
            stats.refreshBlockedHours(d, now, computedAt);
            days++;
        }
        return days;
    }

    /**
     * The oldest day this pass will recompute flow for.
     *
     * <p>Clamped to the oldest {@code stat_date} the summary already holds. A
     * flow row written for a day before the table landed would carry stock
     * zeroes, and a zero is a claim — "no journeys were open" — where an absent
     * row is silence. A-108 chose silence, and the clamp is what honours it while
     * still letting the window widen naturally as the table ages.
     *
     * <p>Static and package-private so the clamp can be asserted for a virgin
     * table without one: it is the rule that decides whether a fresh deployment
     * fabricates a week of zeroes, and it is the kind of arithmetic that reads as
     * obviously right and is off by one.
     */
    static LocalDate flowWindowStart(LocalDate today, int flowWindowDays, Optional<LocalDate> genesis) {
        LocalDate windowStart = today.minusDays(flowWindowDays - 1L);
        LocalDate floor = genesis.orElse(today);
        return windowStart.isBefore(floor) ? floor : windowStart;
    }

    /**
     * Drives one arbitrary day's flow recomputation. Package-private and used by
     * {@code ObStatsRefreshIT} for the one assertion {@link #refreshOnce()}
     * cannot express against a fixed clock: what a <em>later</em> pass does to an
     * earlier day's row, and specifically that it leaves that day's stock alone.
     */
    @Transactional
    int refreshFlowForDay(LocalDate day, Instant now) {
        ZoneId zone = calendars.getCalendar().zone();
        ObStatsDay d = ObStatsDay.of(day, zone);
        stats.refreshSummaryFlow(d, now);
        stats.refreshImplementorFlow(d, now);
        return stats.refreshBlockedHours(d, now, now);
    }
}
