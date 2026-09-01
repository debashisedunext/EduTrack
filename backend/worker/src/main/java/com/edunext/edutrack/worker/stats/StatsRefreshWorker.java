package com.edunext.edutrack.worker.stats;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * A-051 · keeps the summary tables current, every five minutes.
 *
 * <p>A-050 created two tables nothing fills. This is what fills them, and until
 * it runs the dashboard reads empty rows — which is the one failure mode worth
 * stating plainly, because an empty chart and a chart of zeroes look identical.
 *
 * <h2>Why a window rather than "today"</h2>
 *
 * <p>The obvious pass recomputes today. It is not enough, because a past day's
 * figures can still change: effort is attributed to {@code work_date}, so a
 * timesheet filled in on Friday for Monday's work alters Monday, and §4A.4
 * requires exactly that attribution. A ticket closed late, or a cycle
 * back-dated by a correction, does the same. So each pass recomputes a trailing
 * window — the cost is a handful of set-based statements, and the alternative
 * is a table that is right about today and quietly wrong about last week.
 *
 * <h2>Backfill is the same operation, paced</h2>
 *
 * <p>On a database that has never run this, every historical day is missing,
 * and the trend widgets — sparklines, the stacked area, the calendar heatmap —
 * are the ones that need them. Because a day's figures are derived rather than
 * accumulated, backfill is not a special path: it is the ordinary recompute
 * pointed at older dates. It is capped per pass so the first run cannot block
 * the cadence for minutes, and it simply continues on the next one.
 *
 * <h2>What a failure costs</h2>
 *
 * <p>Nothing permanent. A pass that dies leaves the previous rows in place and
 * the next pass recomputes the same window from source. That is the property
 * the recompute-don't-accumulate decision buys, and it is why this can be a
 * plain scheduled job with no retry ledger behind it.
 */
@Component
class StatsRefreshWorker {

    private static final Logger log = LoggerFactory.getLogger(StatsRefreshWorker.class);

    private final DailyStatsRepository stats;
    private final Clock clock;

    /**
     * How many days back each pass recomputes. Seven covers a working week of
     * late timesheets, which is the common case; a correction older than that
     * is repaired by the backfill sweep rather than by every pass carrying the
     * cost of a month.
     */
    private final int windowDays;

    /**
     * The most days one pass will backfill. Bounded so a virgin database
     * catches up over several passes instead of one long first run holding the
     * scheduler lock.
     */
    private final int backfillPerPass;

    /**
     * Whether the <em>schedule</em> runs. The bean exists either way, so a test
     * can still drive {@link #refreshOnce()} itself — which is the whole point,
     * and why this is a field rather than {@code @ConditionalOnProperty} on the
     * class as {@code OutboxWorker} uses. Removing the bean would break the very
     * tests that need the schedule off.
     */
    private final boolean enabled;

    StatsRefreshWorker(DailyStatsRepository stats,
                       Clock clock,
                       @Value("${edutrack.stats.window-days:7}") int windowDays,
                       @Value("${edutrack.stats.backfill-per-pass:30}") int backfillPerPass,
                       @Value("${edutrack.stats.enabled:true}") boolean enabled) {
        this.stats = stats;
        this.clock = clock;
        this.windowDays = windowDays;
        this.backfillPerPass = backfillPerPass;
        this.enabled = enabled;
    }

    /**
     * <b>{@code fixedDelay} fires once at context startup</b> and then waits —
     * lengthening the interval does not prevent that first pass, which is what
     * {@code PT6H} in a test looks like it does and does not. A suite that also
     * calls {@link #refreshOnce()} therefore has two passes over the same rows
     * racing, and they contend on {@code resource_daily_stats}:
     * {@code CannotAcquireLock}, on whichever assertion happened to be running.
     * The race predates A-056; adding a third statement per day made each pass
     * long enough for it to start losing.
     */
    @Scheduled(fixedDelayString = "${edutrack.stats.refresh-interval:PT5M}")
    @SchedulerLock(name = "statsRefresh", lockAtMostFor = "PT4M", lockAtLeastFor = "PT30S")
    public void refresh() {
        if (!enabled) {
            return;
        }
        try {
            refreshOnce();
        } catch (RuntimeException e) {
            // The window is recomputed from source next pass, so a failure here
            // costs five minutes of staleness and nothing else.
            log.error("stats: refresh failed, retrying at the next interval", e);
        }
    }

    /** @return how many days this pass recomputed */
    int refreshOnce() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDate windowStart = today.minusDays(windowDays - 1L);

        int days = recompute(windowStart, today);
        days += backfillOlderThan(windowStart);
        return days;
    }

    private int recompute(LocalDate from, LocalDate to) {
        int days = 0;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            stats.refreshTicketStats(day, clock.instant());
            // A-056 · a second pass rather than a column in the statement above.
            // Reading tickets from inside that INSERT … SELECT contended with the
            // shared locks it already held. Ordered after it because it updates
            // the rows that statement just wrote.
            stats.refreshTypeCounts(day, clock.instant());
            stats.refreshResourceStats(day, clock.instant());
            // A-059 · widget 20's table. Independent of the three above — it
            // reads tickets and writes its own table — so its position in this
            // sequence carries no dependency, only the cost of a fourth
            // statement per day. Inside the same loop rather than a pass of its
            // own, so a day is summarised completely or not at all: two loops
            // would leave a window in which the project charts had advanced to
            // today and the client chart had not, on one dashboard.
            stats.refreshClientStats(day, clock.instant());
            // A-058 · widgets 16–19. The first two are UPDATE passes over the
            // rows refreshTicketStats has just written, so they must follow it;
            // the third writes its own table and could sit anywhere.
            //
            // All three read ticket_stage_transitions, which is why the second
            // pass and not the first: reading that table from inside the
            // INSERT … SELECT above is the shape that deadlocked A-056 —
            // CannotAcquireLock, on a suite that had passed the day before.
            // Dashboard Rework Dev 2, PR 14 · the module-open widget's table.
            // Independent of everything above — it reads tickets and statuses
            // and writes its own table — so its position carries no dependency,
            // only the cost of one more statement per day. Inside the same loop
            // rather than a pass of its own, for refreshClientStats' reason: a
            // day is summarised completely or not at all, and two loops would
            // leave a window in which the project charts had advanced and the
            // module chart had not, on one dashboard.
            //
            // ⚠️ The split (docs/Dashboard-Rework-Split.md) has Dev 1 wiring all
            // three new call sites in PR 4, so that Dev 2 never edits this file.
            // PR 4 has not landed and PR 14 does nothing without a call site, so
            // this one line is added here rather than waiting. Flagged rather
            // than done quietly; the other two are still Dev 1's to add.
            stats.refreshModuleStats(day, clock.instant());
            stats.refreshWipByStage(day, clock.instant());
            stats.refreshReworkCounts(day, clock.instant());
            stats.refreshStageStats(day, clock.instant());
            days++;
        }
        return days;
    }

    /**
     * Fill in history, oldest first, up to the per-pass cap.
     *
     * <p>Oldest first so the trend charts fill from the left and a partially
     * caught-up dashboard shows a shorter history rather than one with a hole
     * in the middle — a gap reads as "nothing happened then", which is a
     * different and wrong statement.
     */
    private int backfillOlderThan(LocalDate windowStart) {
        LocalDate earliest = stats.earliestActivity().orElse(null);
        if (earliest == null || !earliest.isBefore(windowStart)) {
            return 0;
        }
        LocalDate from = stats.backfillResumePoint(earliest, windowStart).orElse(null);
        if (from == null) {
            return 0;
        }
        LocalDate to = from.plusDays(backfillPerPass - 1L);
        if (to.isAfter(windowStart.minusDays(1))) {
            to = windowStart.minusDays(1);
        }
        int days = recompute(from, to);
        if (days > 0) {
            log.info("stats: backfilled {} day(s) from {}", days, from);
        }
        return days;
    }
}
