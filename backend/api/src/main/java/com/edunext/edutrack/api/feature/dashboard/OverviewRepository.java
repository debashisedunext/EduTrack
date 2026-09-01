package com.edunext.edutrack.api.feature.dashboard;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Dashboard Rework Dev 2, PR 9 · reads the {@code ns_*}/{@code wip_*}/
 * {@code blocked_*} columns PR 4 added to {@code daily_ticket_stats} and
 * {@code resource_daily_stats}, which {@link TodayStatsRepository} already
 * reads for the same tables under a different question.
 *
 * <p>Separate class rather than a fourth set of queries folded into {@link
 * DashboardRepository} or {@link TodayStatsRepository}, for the reason {@link
 * TodayStatsRepository}'s own header gives: a different question on the same
 * columns, on a different cadence of change. {@link DashboardRepository}'s
 * {@code Flow} still answers "Total" and "Completed" below — created and
 * closed are not category-specific — so this class only adds what it does
 * not already have: the category stock (Pending/In Progress) and the
 * per-assignee breakdown Top Assignees needs.
 *
 * <h2>Category, not the narrower Today figures</h2>
 *
 * <p>{@code wip_total} alone is IN_PROGRESS/REWORK — {@link
 * TodayProgressService}'s own header says so, because Today draws Blocked as
 * its own card. The contract's {@code StatusCategory.IN_PROGRESS} is wider —
 * it also holds ON_HOLD and AWAITING_INFO — so every read here adds {@code
 * blocked_on_hold}/{@code blocked_awaiting_info} back in to answer the
 * category the contract actually names.
 */
@Repository
class OverviewRepository {

    private final JdbcClient jdbc;

    OverviewRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ── project-keyed (daily_ticket_stats) ────────────────────────────────

    /** As {@link DashboardRepository#projectStock} names it: the most recent summarised day in the window. */
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
     * Pending and In Progress, undivided — the card figures. {@code
     * inProgress()} folds Blocked back in; the disjoint, overdue-aware split
     * Top Assignees needs is computed on read in {@link OverviewService},
     * the same place {@link TodayProgressService#wipOnTime} does the
     * identical kind of subtraction, not stored pre-subtracted the way {@code
     * module_daily_stats} is — these columns serve more than one shape of
     * question and a pre-subtracted value would only answer one of them.
     */
    record CategoryStock(long nsTotal, long nsOverdue, long wipTotal, long wipDelayed,
                          long blockedOnHold, long blockedAwaitingInfo) {

        long pending() {
            return nsTotal;
        }

        long inProgress() {
            return wipTotal + blockedOnHold + blockedAwaitingInfo;
        }
    }

    CategoryStock projectCategoryStock(LocalDate day, List<Long> projectIds, Long projectFilter) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(ns_total), 0)              AS ns_total,
                               COALESCE(SUM(ns_overdue), 0)            AS ns_overdue,
                               COALESCE(SUM(wip_total), 0)             AS wip_total,
                               COALESCE(SUM(wip_delayed), 0)           AS wip_delayed,
                               COALESCE(SUM(blocked_on_hold), 0)       AS blocked_on_hold,
                               COALESCE(SUM(blocked_awaiting_info), 0) AS blocked_awaiting_info
                          FROM daily_ticket_stats
                         WHERE stat_date = :day
                           AND (:unscoped = 1 OR project_id IN (:projectIds))
                           AND (:projectFilter IS NULL OR project_id = :projectFilter)
                        """)
                .param("day", day)
                .param("unscoped", projectIds.isEmpty() ? 1 : 0)
                .param("projectIds", scopeOrSentinel(projectIds))
                .param("projectFilter", projectFilter)
                .query((rs, n) -> new CategoryStock(
                        rs.getLong("ns_total"), rs.getLong("ns_overdue"),
                        rs.getLong("wip_total"), rs.getLong("wip_delayed"),
                        rs.getLong("blocked_on_hold"), rs.getLong("blocked_awaiting_info")))
                .single();
    }

    /**
     * The ten busiest people by open total, {@code project_members}-scoped
     * exactly as {@link TodayStatsRepository#misRows} scopes the MIS grid,
     * and for the identical reason: {@code resource_daily_stats} carries no
     * project column to intersect with the caller's {@code projectIds}.
     *
     * <p>Ordered by the same disjoint sum {@link OverviewService} renders as
     * the bar's total, not by {@code assigned_open} — computing the ranking
     * from the same components the bar draws is what keeps "busiest first"
     * agreeing with the bar it labels.
     */
    List<Assignee> topAssignees(LocalDate day, List<Long> projectIds, Long projectFilter, int limit) {
        return jdbc.sql("""
                        SELECT r.user_id, u.full_name,
                               r.ns_total, r.ns_overdue,
                               r.wip_total, r.wip_delayed,
                               r.blocked_on_hold, r.blocked_awaiting_info
                          FROM resource_daily_stats r
                          JOIN users u ON u.id = r.user_id
                         WHERE r.stat_date = :day
                           AND (:unscoped = 1 OR r.user_id IN (
                                   SELECT pm.user_id FROM project_members pm
                                    WHERE pm.project_id IN (:projectIds) AND pm.is_active = 1))
                           AND (:projectFilter IS NULL OR r.user_id IN (
                                   SELECT pm2.user_id FROM project_members pm2
                                    WHERE pm2.project_id = :projectFilter AND pm2.is_active = 1))
                         ORDER BY (COALESCE(r.ns_total, 0) + COALESCE(r.wip_total, 0)
                                   + COALESCE(r.blocked_on_hold, 0) + COALESCE(r.blocked_awaiting_info, 0)) DESC,
                                  u.full_name
                         LIMIT :limit
                        """)
                .param("day", day)
                .param("unscoped", projectIds.isEmpty() ? 1 : 0)
                .param("projectIds", scopeOrSentinel(projectIds))
                .param("projectFilter", projectFilter)
                .param("limit", limit)
                .query((rs, n) -> new Assignee(
                        rs.getLong("user_id"), rs.getString("full_name"),
                        rs.getLong("ns_total"), rs.getLong("ns_overdue"),
                        rs.getLong("wip_total"), rs.getLong("wip_delayed"),
                        rs.getLong("blocked_on_hold"), rs.getLong("blocked_awaiting_info")))
                .list();
    }

    record Assignee(long userId, String displayName, long nsTotal, long nsOverdue,
                     long wipTotal, long wipDelayed, long blockedOnHold, long blockedAwaitingInfo) {
    }

    // ── resource-keyed (resource_daily_stats) — own-work / one named resource ─

    Optional<LocalDate> latestResourceDay(LocalDate from, LocalDate to, long userId) {
        return jdbc.sql("""
                        SELECT MAX(stat_date) FROM resource_daily_stats
                         WHERE stat_date BETWEEN :from AND :to AND user_id = :userId
                        """)
                .param("from", from).param("to", to).param("userId", userId)
                .query(LocalDate.class)
                .optional();
    }

    Optional<CategoryStock> resourceCategoryStock(LocalDate day, long userId) {
        return jdbc.sql("""
                        SELECT ns_total, ns_overdue, wip_total, wip_delayed,
                               blocked_on_hold, blocked_awaiting_info
                          FROM resource_daily_stats
                         WHERE stat_date = :day AND user_id = :userId
                        """)
                .param("day", day).param("userId", userId)
                .query((rs, n) -> new CategoryStock(
                        rs.getLong("ns_total"), rs.getLong("ns_overdue"),
                        rs.getLong("wip_total"), rs.getLong("wip_delayed"),
                        rs.getLong("blocked_on_hold"), rs.getLong("blocked_awaiting_info")))
                .optional();
    }

    /**
     * The freshness stamp for one person's row — {@link DashboardRepository}
     * has no resource-keyed equivalent of its own {@code computedAt}, and
     * {@link TodayStatsRepository#resourceDay} reads it inline rather than
     * as its own method, which this class's shape does not share.
     */
    Optional<Instant> resourceComputedAt(LocalDate day, long userId) {
        return jdbc.sql("""
                        SELECT computed_at FROM resource_daily_stats
                         WHERE stat_date = :day AND user_id = :userId
                        """)
                .param("day", day).param("userId", userId)
                .query((rs, n) -> {
                    Timestamp stamp = rs.getTimestamp("computed_at");
                    return stamp == null ? null : stamp.toInstant();
                })
                .optional();
    }

    /** {@link TodayStatsRepository#scopeOrSentinel}'s twin — kept identical rather than shared across two small classes. */
    private static List<Long> scopeOrSentinel(List<Long> projectIds) {
        return projectIds.isEmpty() ? List.of(-1L) : projectIds;
    }
}
