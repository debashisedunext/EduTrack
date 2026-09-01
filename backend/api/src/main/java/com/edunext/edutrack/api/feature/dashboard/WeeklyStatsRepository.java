package com.edunext.edutrack.api.feature.dashboard;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
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
     * The stock figures the delayed and due cards read. {@code open_pct_sum}
     * is deliberately <b>not</b> here — see {@link #projectProgress}, which
     * has to choose its own day.
     */
    record ProjectWeekStock(long openTotal, long openDelayed, long delayDaysSum, long openDueNext7) {
    }

    ProjectWeekStock projectStock(LocalDate day, List<Long> projectIds, Long projectFilter) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(open_total), 0)      AS open_total,
                               COALESCE(SUM(open_delayed), 0)    AS open_delayed,
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
                .query((rs, n) -> new ProjectWeekStock(rs.getLong("open_total"),
                        rs.getLong("open_delayed"), rs.getLong("delay_days_sum"),
                        rs.getLong("open_due_next_7")))
                .single();
    }

    /**
     * Average progress's two halves, from the latest day in the window that
     * actually carries a measured figure.
     *
     * <h2>Why this cannot reuse {@link #projectStock}'s day</h2>
     *
     * <p>{@code open_pct_sum} is written only on the pass where the day being
     * summarised IS the current day, so within any window most days carry
     * {@code NULL} — the latest summarised day and the latest day with a
     * progress figure are usually different dates. Reading the former would
     * report {@code NULL} as zero and put "0% progress" on a week that simply
     * has not been measured since Tuesday.
     *
     * <h2>Both halves are restricted to the rows that have a figure</h2>
     *
     * <p><b>{@code SUM} ignores NULLs rather than propagating them</b> — a
     * detail worth stating because the obvious reading is the opposite, and
     * this class's own note said the opposite until PR 12 corrected it. Over
     * three projects where one is {@code NULL}, {@code SUM(open_pct_sum)}
     * quietly returns the other two's total. Pairing that numerator with an
     * unrestricted {@code SUM(open_total)} denominator would divide two
     * populations that are not the same set, and understate every
     * multi-project average. So both sides carry the same {@code
     * open_pct_sum IS NOT NULL} filter: the answer is "average progress
     * across the projects that reported one", which is a statement that
     * survives a project having no figure.
     *
     * @return empty when no day in the window carries a figure at all — the
     *         card is then omitted rather than sent as a fabricated zero.
     */
    record Progress(LocalDate day, long pctSum, long openTotal) {
    }

    Optional<Progress> projectProgress(LocalDate from, LocalDate to, List<Long> projectIds, Long projectFilter) {
        return jdbc.sql("""
                        SELECT stat_date,
                               COALESCE(SUM(open_pct_sum), 0) AS pct_sum,
                               COALESCE(SUM(open_total), 0)   AS open_total
                          FROM daily_ticket_stats
                         WHERE stat_date = (
                                   SELECT MAX(stat_date) FROM daily_ticket_stats
                                    WHERE stat_date BETWEEN :from AND :to
                                      AND open_pct_sum IS NOT NULL
                                      AND (:unscoped = 1 OR project_id IN (:projectIds))
                                      AND (:projectFilter IS NULL OR project_id = :projectFilter))
                           AND open_pct_sum IS NOT NULL
                           AND (:unscoped = 1 OR project_id IN (:projectIds))
                           AND (:projectFilter IS NULL OR project_id = :projectFilter)
                         GROUP BY stat_date
                        """)
                .param("from", from).param("to", to)
                .param("unscoped", projectIds.isEmpty() ? 1 : 0)
                .param("projectIds", scopeOrSentinel(projectIds))
                .param("projectFilter", projectFilter)
                .query((rs, n) -> new Progress(rs.getObject("stat_date", LocalDate.class),
                        rs.getLong("pct_sum"), rs.getLong("open_total")))
                .optional();
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

    /**
     * @param pctSum {@code 0} rather than {@code NULL} on any day that was
     *               not the current one when its pass ran, and <b>0 is also a
     *               genuine answer</b> — this table cannot tell the two
     *               apart, where the project table can. {@link
     *               DailyStatsRepository#refreshWeeklyStats} (worker module)
     *               carries the reason: {@code refreshResourceStats} DELETEs
     *               and re-INSERTs the row earlier in the same pass, so there
     *               is no prior value left to preserve. {@link
     *               WeeklyProgressService} therefore offers own-work progress
     *               for the current week only.
     */
    record ResourceWeekStock(long openTotal, long assignedDelayed, long pctSum, long delayDaysSum,
                              Instant computedAt) {
    }

    /**
     * {@code computed_at} is read from the same row rather than by a second
     * query — {@link DashboardRepository} has no resource-keyed {@code
     * computedAt} of its own, and {@link TodayStatsRepository#resourceDay}
     * already takes it inline from the row it is loading for the same reason.
     */
    Optional<ResourceWeekStock> resourceStock(LocalDate day, long userId) {
        return jdbc.sql("""
                        SELECT assigned_open, assigned_delayed, pct_sum, delay_days_sum, computed_at
                          FROM resource_daily_stats
                         WHERE stat_date = :day AND user_id = :userId
                        """)
                .param("day", day).param("userId", userId)
                .query((rs, n) -> {
                    Timestamp stamp = rs.getTimestamp("computed_at");
                    return new ResourceWeekStock(rs.getLong("assigned_open"),
                            rs.getLong("assigned_delayed"), rs.getLong("pct_sum"),
                            rs.getLong("delay_days_sum"),
                            stamp == null ? null : stamp.toInstant());
                })
                .optional();
    }

    /**
     * {@code assigned_due_next_7} is A-062's column, not PR 11's — the
     * resource table has carried it since V20260817_1130 and it answers the
     * same question {@code open_due_next_7} does for a project. Read at the
     * week's Monday it is exactly "due this week", for the reason {@link
     * WeeklyProgressService} gives.
     */
    Optional<Long> resourceDueThatWeek(LocalDate weekStart, long userId) {
        return jdbc.sql("""
                        SELECT assigned_due_next_7 FROM resource_daily_stats
                         WHERE stat_date = :day AND user_id = :userId
                        """)
                .param("day", weekStart).param("userId", userId)
                .query(Long.class)
                .optional();
    }

    /** {@link TodayStatsRepository#scopeOrSentinel}'s twin — kept identical rather than shared across small classes. */
    private static List<Long> scopeOrSentinel(List<Long> projectIds) {
        return projectIds.isEmpty() ? List.of(-1L) : projectIds;
    }
}
