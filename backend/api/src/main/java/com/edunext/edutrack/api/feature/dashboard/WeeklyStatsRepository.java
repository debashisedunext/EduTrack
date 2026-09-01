package com.edunext.edutrack.api.feature.dashboard;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Dashboard Rework Dev 2, PR 11 · reads the {@code open_pct_sum}/{@code
 * pct_sum}, {@code delay_days_sum} and {@code open_due_next_7} columns
 * {@code V20260901_1330__weekly_stats_columns.sql} added, and {@link
 * DailyStatsRepository#refreshWeeklyStats} (worker module — a different
 * class of the same name in a different package) fills in every pass.
 *
 * <p>Read-only class, matching {@link OverviewRepository} and {@link
 * TodayStatsRepository}'s own reason for splitting off from {@link
 * DashboardRepository}: a different question on the same columns, on a
 * different cadence of change. {@code WeeklyProgressService} (PR 12) is
 * this class's only intended reader — this PR adds the column access, not
 * the card arithmetic built on top of it.
 *
 * <h2>Sums, not the average — see the migration</h2>
 *
 * <p>{@code open_pct_sum}/{@code pct_sum} is a sum of {@code
 * tickets.pct_complete} over open tickets, not "the average progress
 * percent". Dividing by {@code openTotal} is left to the caller, which is
 * the whole reason the migration stored a sum: the average stays correct
 * against whichever open total is true when it is read, not the one that
 * was true when the row was last computed.
 *
 * <h2>{@code openPctSum} comes back {@code null} on every day but the
 * actual current one; {@code pctSum} comes back {@code 0} instead</h2>
 *
 * <p>{@code tickets.pct_complete} carries no history, so the worker only
 * ever fills a real number on the pass that is genuinely running today —
 * {@code wip_updated_today}'s own limitation, restated for this column on
 * both tables. The two tables say "not this day" two different ways for
 * the reason {@code V20260901_1330}'s header gives: {@code
 * daily_ticket_stats} gives every project a row on every day, so only
 * {@code NULL} can tell a pre-migration row apart from one this pass
 * skipped; {@code resource_daily_stats} rewrites the whole row every time
 * it is written, so there is no such row to distinguish and the worker
 * writes a real {@code 0} instead, matching every other current-day-only
 * column on that table.
 */
@Repository
class WeeklyStatsRepository {

    private final JdbcClient jdbc;

    WeeklyStatsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ── project-keyed (daily_ticket_stats) ────────────────────────────────

    /** The most recent summarised day at or before {@code to}, within {@code [from, to]}. */
    Optional<LocalDate> latestProjectDay(LocalDate from, LocalDate to, List<Long> projectIds, Long projectFilter) {
        return jdbc.sql("""
                        SELECT MAX(stat_date) FROM daily_ticket_stats
                         WHERE stat_date BETWEEN :from AND :to
                           AND (:unscoped = 1 OR project_id IN (:projectIds))
                           AND (:projectFilter IS NULL OR project_id = :projectFilter)
                        """)
                .param("from", from).param("to", to)
                .param("unscoped", projectIds.isEmpty() ? 1 : 0)
                .param("projectIds", scopeOrSentinel(projectIds))
                .param("projectFilter", projectFilter)
                .query(LocalDate.class)
                .optional();
    }

    /**
     * @param openPctSum {@code null} when {@code day} is not the actual
     *                   current day — see the class note. {@code SUM}
     *                   across every scoped project already returns
     *                   {@code NULL} the moment one of them is, which
     *                   would silently understate a multi-project read on
     *                   any day but today; {@code COALESCE} is deliberately
     *                   absent from that one column so a partial NULL
     *                   surfaces as the whole figure being unavailable
     *                   rather than as a wrong number.
     */
    record ProjectWeekStock(long openTotal, Long openPctSum, long delayDaysSum, long openDueNext7) {
    }

    ProjectWeekStock projectStock(LocalDate day, List<Long> projectIds, Long projectFilter) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(open_total), 0)      AS open_total,
                               SUM(open_pct_sum)                 AS open_pct_sum,
                               COALESCE(SUM(delay_days_sum), 0)  AS delay_days_sum,
                               COALESCE(SUM(open_due_next_7), 0) AS open_due_next_7
                          FROM daily_ticket_stats
                         WHERE stat_date = :day
                           AND (:unscoped = 1 OR project_id IN (:projectIds))
                           AND (:projectFilter IS NULL OR project_id = :projectFilter)
                        """)
                .param("day", day)
                .param("unscoped", projectIds.isEmpty() ? 1 : 0)
                .param("projectIds", scopeOrSentinel(projectIds))
                .param("projectFilter", projectFilter)
                .query((rs, n) -> {
                    long openPctSum = rs.getLong("open_pct_sum");
                    return new ProjectWeekStock(rs.getLong("open_total"),
                            rs.wasNull() ? null : openPctSum,
                            rs.getLong("delay_days_sum"), rs.getLong("open_due_next_7"));
                })
                .single();
    }

    // ── resource-keyed (resource_daily_stats) ─────────────────────────────

    Optional<LocalDate> latestResourceDay(LocalDate from, LocalDate to, long userId) {
        return jdbc.sql("""
                        SELECT MAX(stat_date) FROM resource_daily_stats
                         WHERE stat_date BETWEEN :from AND :to AND user_id = :userId
                        """)
                .param("from", from).param("to", to).param("userId", userId)
                .query(LocalDate.class)
                .optional();
    }

    /** Unlike {@link ProjectWeekStock#openPctSum}, {@code pctSum} is a plain {@code long} — see the class note. */
    record ResourceWeekStock(long openTotal, long pctSum, long delayDaysSum) {
    }

    Optional<ResourceWeekStock> resourceStock(LocalDate day, long userId) {
        return jdbc.sql("""
                        SELECT assigned_open, pct_sum, delay_days_sum
                          FROM resource_daily_stats
                         WHERE stat_date = :day AND user_id = :userId
                        """)
                .param("day", day).param("userId", userId)
                .query((rs, n) -> new ResourceWeekStock(rs.getLong("assigned_open"),
                        rs.getLong("pct_sum"), rs.getLong("delay_days_sum")))
                .optional();
    }

    /** {@link TodayStatsRepository#scopeOrSentinel}'s twin — kept identical rather than shared across small classes. */
    private static List<Long> scopeOrSentinel(List<Long> projectIds) {
        return projectIds.isEmpty() ? List.of(-1L) : projectIds;
    }
}
