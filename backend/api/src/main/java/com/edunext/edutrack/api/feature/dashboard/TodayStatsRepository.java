package com.edunext.edutrack.api.feature.dashboard;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Dashboard Rework Dev 1, PR 6 · reads PR 4's fourteen Today counters plus
 * {@code open_by_role} and the pre-existing {@code open_total}. Never
 * {@code tickets} — CLAUDE.md's "never a live {@code COUNT(*)} for
 * dashboards" applies here exactly as it does to {@link DashboardRepository}
 * and {@link WidgetRepository}.
 *
 * <p>Separate class rather than added to {@link DashboardRepository}, for the
 * same reason {@link WidgetRepository} split off from it: a different set of
 * columns, on a different cadence of change, on a table already carrying two
 * repositories' worth of queries.
 *
 * <h2>The MIS grid has the same resource-scoping gap {@link WidgetRepository}
 * already names</h2>
 *
 * <p>{@code resource_daily_stats} is keyed {@code (stat_date, user_id)} with
 * no project column (A-050), so a PM cannot be given "my project's resources"
 * exactly — there is nothing to intersect their {@code projectIds} against.
 * {@link #misRows} scopes by <em>membership</em> instead, via
 * {@code project_members}, matching {@link WidgetRepository#velocityByWeek}
 * and {@link WidgetRepository#resourceLoad}: a person on three projects who
 * happens to be on the PM's one appears with their whole load, not the slice
 * that belongs to this PM. Recorded here rather than re-argued, since it is
 * the identical trade-off for the identical reason.
 *
 * <h2>No "Unassigned" row in the MIS grid</h2>
 *
 * <p>The prototype's mock data includes one; the schema cannot produce it.
 * {@code resource_daily_stats.user_id} is {@code NOT NULL} and the worker
 * populates it only for tickets with a real {@code assigned_to} — an
 * unassigned ticket earns no resource row anywhere to report it from. The
 * Open Issues card's {@code UNASSIGNED} chip is the honest place that fact
 * lives; inventing a zero-keyed MIS row here would need a query over
 * {@code tickets} this class exists to avoid.
 */
@Repository
class TodayStatsRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    TodayStatsRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ── project-keyed (daily_ticket_stats) — FULL variant ────────────────────

    /** The fourteen counters plus {@code open_total}, summed across every project the query matches. */
    record ProjectDay(long nsTotal, long nsOverdue, long nsDueToday,
                       long wipTotal, long wipUpdatedToday, long wipNearDelay, long wipDelayed,
                       long startedToday, long finishedEarly, long finishedOnTime, long finishedLate,
                       long blockedOnHold, long blockedAwaitingInfo, long pendingReview,
                       long openTotal) {
    }

    /**
     * The most recent summarised day at or before {@code today}, across the
     * caller's scope. Empty on a virgin database, or when nothing in scope has
     * ever been computed — distinct from "computed, and everything is zero".
     */
    Optional<LocalDate> latestProjectDay(LocalDate today, List<Long> projectIds, Long projectFilter) {
        return jdbc.sql("""
                        SELECT MAX(stat_date) FROM daily_ticket_stats
                         WHERE stat_date <= :today
                           AND (:unscoped = 1 OR project_id IN (:projectIds))
                           AND (:projectFilter IS NULL OR project_id = :projectFilter)
                        """)
                .param("today", today)
                .param("unscoped", projectIds.isEmpty() ? 1 : 0)
                .param("projectIds", scopeOrSentinel(projectIds))
                .param("projectFilter", projectFilter)
                .query(LocalDate.class)
                .optional();
    }

    /**
     * One day's figures, summed across every scoped project. {@code COALESCE}
     * on every column: a project row can carry {@code NULL} on any of these
     * fourteen (the migration's "not computed on this database yet" case), and
     * {@code SUM} skipping a {@code NULL} must not be allowed to shrink the
     * total silently the way it would for an un-narrowed comparison elsewhere
     * in this package.
     */
    ProjectDay projectDay(LocalDate day, List<Long> projectIds, Long projectFilter) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(ns_total), 0)              AS ns_total,
                               COALESCE(SUM(ns_overdue), 0)            AS ns_overdue,
                               COALESCE(SUM(ns_due_today), 0)          AS ns_due_today,
                               COALESCE(SUM(wip_total), 0)             AS wip_total,
                               COALESCE(SUM(wip_updated_today), 0)     AS wip_updated_today,
                               COALESCE(SUM(wip_near_delay), 0)        AS wip_near_delay,
                               COALESCE(SUM(wip_delayed), 0)           AS wip_delayed,
                               COALESCE(SUM(started_today), 0)         AS started_today,
                               COALESCE(SUM(finished_early), 0)        AS finished_early,
                               COALESCE(SUM(finished_on_time), 0)      AS finished_on_time,
                               COALESCE(SUM(finished_late), 0)         AS finished_late,
                               COALESCE(SUM(blocked_on_hold), 0)       AS blocked_on_hold,
                               COALESCE(SUM(blocked_awaiting_info), 0) AS blocked_awaiting_info,
                               COALESCE(SUM(pending_review), 0)        AS pending_review,
                               COALESCE(SUM(open_total), 0)            AS open_total
                          FROM daily_ticket_stats
                         WHERE stat_date = :day
                           AND (:unscoped = 1 OR project_id IN (:projectIds))
                           AND (:projectFilter IS NULL OR project_id = :projectFilter)
                        """)
                .param("day", day)
                .param("unscoped", projectIds.isEmpty() ? 1 : 0)
                .param("projectIds", scopeOrSentinel(projectIds))
                .param("projectFilter", projectFilter)
                .query((rs, n) -> new ProjectDay(
                        rs.getLong("ns_total"), rs.getLong("ns_overdue"), rs.getLong("ns_due_today"),
                        rs.getLong("wip_total"), rs.getLong("wip_updated_today"), rs.getLong("wip_near_delay"),
                        rs.getLong("wip_delayed"), rs.getLong("started_today"), rs.getLong("finished_early"),
                        rs.getLong("finished_on_time"), rs.getLong("finished_late"), rs.getLong("blocked_on_hold"),
                        rs.getLong("blocked_awaiting_info"), rs.getLong("pending_review"),
                        rs.getLong("open_total")))
                .single();
    }

    /**
     * Every not-closed ticket, by the role currently holding it — the same
     * per-project-JSON-merged-in-Java shape {@link
     * WidgetRepository#openByTaskType} already uses, and for the identical
     * reason: {@code JSON_MERGE_PATCH} replaces a duplicate key rather than
     * adding it, so two projects each showing 4 for {@code DEVELOPER} would
     * merge to 4 instead of 8.
     *
     * @return role code (or the literal {@code UNASSIGNED}) to open count.
     *         Absent from the map means zero, matching how the worker omits a
     *         role from the JSON entirely when nothing is open against it.
     */
    Map<String, Long> openByRole(LocalDate day, List<Long> projectIds, Long projectFilter) {
        List<String> documents = jdbc.sql("""
                        SELECT open_by_role FROM daily_ticket_stats
                         WHERE stat_date = :day
                           AND (:unscoped = 1 OR project_id IN (:projectIds))
                           AND (:projectFilter IS NULL OR project_id = :projectFilter)
                           AND open_by_role IS NOT NULL
                        """)
                .param("day", day)
                .param("unscoped", projectIds.isEmpty() ? 1 : 0)
                .param("projectIds", scopeOrSentinel(projectIds))
                .param("projectFilter", projectFilter)
                .query(String.class)
                .list();

        Map<String, Long> merged = new LinkedHashMap<>();
        for (String document : documents) {
            parseRoleCounts(document).forEach((role, count) -> merged.merge(role, count.longValue(), Long::sum));
        }
        return merged;
    }

    private Map<String, Integer> parseRoleCounts(String document) {
        try {
            return json.readValue(document, new TypeReference<>() {
            });
        } catch (JsonProcessingException malformed) {
            // Written by JSON_OBJECTAGG, so MySQL has already validated it as an
            // object. Unreadable here means the column was written by something
            // else — a defect to surface, not a chip to silently draw at zero.
            throw new IllegalStateException(
                    "daily_ticket_stats.open_by_role holds a value that is not a JSON object: " + document,
                    malformed);
        }
    }

    /** The freshness stamp for one day's scoped rows — mirrors {@link DashboardRepository#computedAt}. */
    Optional<Instant> computedAt(LocalDate day, List<Long> projectIds, Long projectFilter) {
        return jdbc.sql("""
                        SELECT MAX(computed_at) AS computed_at FROM daily_ticket_stats
                         WHERE stat_date = :day
                           AND (:unscoped = 1 OR project_id IN (:projectIds))
                           AND (:projectFilter IS NULL OR project_id = :projectFilter)
                        """)
                .param("day", day)
                .param("unscoped", projectIds.isEmpty() ? 1 : 0)
                .param("projectIds", scopeOrSentinel(projectIds))
                .param("projectFilter", projectFilter)
                .query((rs, n) -> {
                    Timestamp stamp = rs.getTimestamp("computed_at");
                    return stamp == null ? null : stamp.toInstant();
                })
                .optional();
    }

    /**
     * The MIS grid — one row per resource on their team, scoped by {@code
     * project_members} rather than by a project column {@code
     * resource_daily_stats} does not have. See the class note.
     *
     * @param projectFilter narrows to one project's membership regardless of
     *                      the caller's wider scope, the same AND-not-replace
     *                      rule every other dashboard filter follows.
     */
    List<MisRow> misRows(LocalDate day, List<Long> projectIds, Long projectFilter) {
        return jdbc.sql("""
                        SELECT r.user_id, u.full_name,
                               r.ns_overdue, r.ns_due_today, r.ns_total,
                               r.wip_total, r.wip_updated_today, r.wip_near_delay, r.wip_delayed,
                               r.finished_early, r.finished_on_time, r.finished_late
                          FROM resource_daily_stats r
                          JOIN users u ON u.id = r.user_id
                         WHERE r.stat_date = :day
                           AND (:unscoped = 1 OR r.user_id IN (
                                   SELECT pm.user_id FROM project_members pm
                                    WHERE pm.project_id IN (:projectIds) AND pm.is_active = 1))
                           AND (:projectFilter IS NULL OR r.user_id IN (
                                   SELECT pm2.user_id FROM project_members pm2
                                    WHERE pm2.project_id = :projectFilter AND pm2.is_active = 1))
                         ORDER BY u.full_name
                        """)
                .param("day", day)
                .param("unscoped", projectIds.isEmpty() ? 1 : 0)
                .param("projectIds", scopeOrSentinel(projectIds))
                .param("projectFilter", projectFilter)
                .query((rs, n) -> new MisRow(
                        rs.getLong("user_id"), rs.getString("full_name"),
                        rs.getLong("ns_overdue"), rs.getLong("ns_due_today"), rs.getLong("ns_total"),
                        rs.getLong("wip_total"), rs.getLong("wip_updated_today"), rs.getLong("wip_near_delay"),
                        rs.getLong("wip_delayed"), rs.getLong("finished_early"), rs.getLong("finished_on_time"),
                        rs.getLong("finished_late")))
                .list();
    }

    record MisRow(long userId, String displayName,
                  long nsOverdue, long nsDueToday, long nsTotal,
                  long wipTotal, long wipUpdatedToday, long wipNearDelay, long wipDelayed,
                  long finishedEarly, long finishedOnTime, long finishedLate) {
    }

    // ── resource-keyed (resource_daily_stats) — OWN_WORK variant ─────────────

    record ResourceDay(long nsTotal, long nsOverdue, long nsDueToday,
                        long wipTotal, long wipUpdatedToday, long wipNearDelay, long wipDelayed,
                        long startedToday, long finishedEarly, long finishedOnTime, long finishedLate,
                        long blockedOnHold, long blockedAwaitingInfo, long pendingReview,
                        Instant computedAt) {
    }

    /** As {@link #latestProjectDay}, for one person — a row exists only on a day they earned one. */
    Optional<LocalDate> latestResourceDay(long userId, LocalDate today) {
        return jdbc.sql("""
                        SELECT MAX(stat_date) FROM resource_daily_stats
                         WHERE user_id = :userId AND stat_date <= :today
                        """)
                .param("userId", userId)
                .param("today", today)
                .query(LocalDate.class)
                .optional();
    }

    Optional<ResourceDay> resourceDay(LocalDate day, long userId) {
        return jdbc.sql("""
                        SELECT ns_total, ns_overdue, ns_due_today,
                               wip_total, wip_updated_today, wip_near_delay, wip_delayed,
                               started_today, finished_early, finished_on_time, finished_late,
                               blocked_on_hold, blocked_awaiting_info, pending_review, computed_at
                          FROM resource_daily_stats
                         WHERE stat_date = :day AND user_id = :userId
                        """)
                .param("day", day)
                .param("userId", userId)
                .query((rs, n) -> new ResourceDay(
                        rs.getLong("ns_total"), rs.getLong("ns_overdue"), rs.getLong("ns_due_today"),
                        rs.getLong("wip_total"), rs.getLong("wip_updated_today"), rs.getLong("wip_near_delay"),
                        rs.getLong("wip_delayed"), rs.getLong("started_today"), rs.getLong("finished_early"),
                        rs.getLong("finished_on_time"), rs.getLong("finished_late"), rs.getLong("blocked_on_hold"),
                        rs.getLong("blocked_awaiting_info"), rs.getLong("pending_review"),
                        rs.getTimestamp("computed_at").toInstant()))
                .optional();
    }

    /** {@link WidgetRepository#scopeOrSentinel}'s twin — kept identical rather than shared across two small classes. */
    private static List<Long> scopeOrSentinel(List<Long> projectIds) {
        return projectIds.isEmpty() ? List.of(-1L) : projectIds;
    }
}
