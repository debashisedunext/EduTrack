package com.edunext.edutrack.api.feature.reports;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A-066 · the reads that cannot come from a summary table.
 *
 * <h2>Why this aggregates {@code tickets}, when the dashboard may not</h2>
 *
 * <p>CLAUDE.md and PLAN.md §480 forbid a live {@code COUNT(*)} <b>for
 * dashboards</b>, and the reason is specific to a dashboard: it loads unbidden
 * on every login, for every role, and M6's exit criterion is a first paint under
 * 1.5 seconds on 50,000 tickets. A-050 built the summary tables so that screen
 * never has to count.
 *
 * <p>Five of §7.8's first six reports cannot be answered that way, and not for
 * want of trying: average cycle time, estimated-versus-actual variance, a
 * per-person reopen rate and a <em>list</em> of breached tickets are per-ticket
 * facts. No table keyed {@code (date, project)} or {@code (date, user)} carries
 * them, and adding columns would not help — an average of daily averages is not
 * the average.
 *
 * <p>A report is also a different thing from a dashboard. It is opened
 * deliberately, one at a time, always with a date range, by somebody prepared to
 * wait a moment for an answer they intend to quote. So these queries are bounded
 * by that range, scoped in SQL, and grouped in the database rather than in Java.
 *
 * <p><b>The A-063 README said "the one thing no report may do is aggregate
 * tickets live".</b> That over-stated the rule — it is dashboard-scoped — and
 * the line is corrected there rather than left to contradict this class.
 *
 * <h2>Scope is a WHERE clause, never a filter afterwards</h2>
 *
 * <p>Every query takes {@code unscoped}/{@code projectIds} and the own-work
 * pair. Fetching everything and discarding the rest would be a scope failure
 * waiting for somebody to forget the filter, and would read the whole table at
 * 50,000 tickets to answer a question about four.
 */
@Repository
class TicketReportRepository {

    private final JdbcClient jdbc;

    TicketReportRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ── 1 · resource scorecard ───────────────────────────────────────────────

    /**
     * One row per person, over tickets <b>closed in the window</b>.
     *
     * <p>Closed rather than assigned, because every column except the last is a
     * statement about finished work: on-time, cycle time and variance are
     * undefined for a ticket still open, and including open ones would divide a
     * real numerator by a denominator holding work nobody has had a chance to
     * finish.
     *
     * <p>{@code assigned_now} is therefore what the person holds <em>today</em>,
     * reported beside the rest and deliberately not a ratio with it.
     */
    List<ScorecardRow> scorecard(LocalDate from, LocalDate to, List<Long> projectIds,
                                 boolean ownWork, long userId, Long resourceId) {
        return jdbc.sql("""
                        SELECT u.id        AS user_id,
                               u.full_name AS full_name,
                               COUNT(*)    AS closed,
                               SUM(CASE WHEN t.planned_close_date IS NOT NULL
                                         AND t.actual_close_date <= t.planned_close_date
                                        THEN 1 ELSE 0 END) AS on_time,
                               SUM(CASE WHEN t.planned_close_date IS NOT NULL
                                        THEN 1 ELSE 0 END) AS committed,
                               AVG(TIMESTAMPDIFF(HOUR, t.date_reported, t.actual_close_date))
                                                          AS avg_cycle_hours,
                               COALESCE(SUM(t.total_effort_hrs), 0)     AS actual_hours,
                               COALESCE(SUM(t.estimated_effort_hrs), 0) AS estimated_hours,
                               SUM(CASE WHEN t.reopen_count > 0 THEN 1 ELSE 0 END) AS reopened,
                               (SELECT COUNT(*) FROM tickets o
                                 WHERE o.assigned_to = u.id AND o.status <> 'CLOSED') AS assigned_now
                          FROM tickets t
                          JOIN users u ON u.id = t.assigned_to
                         WHERE t.actual_close_date >= :from
                           AND t.actual_close_date < :toExclusive
                           AND (:unscoped = 1 OR t.project_id IN (:projectIds))
                           AND (:ownWork = 0 OR t.assigned_to = :userId)
                           AND (:resourceId IS NULL OR t.assigned_to = :resourceId)
                      GROUP BY u.id, u.full_name
                      ORDER BY closed DESC, u.full_name
                        """)
                .param("from", from)
                // Exclusive upper bound. `<= :to` against a DATETIME(6) means
                // midnight, which silently drops everything closed on the last
                // day of the range the user actually asked for.
                .param("toExclusive", to.plusDays(1))
                .param("unscoped", projectIds.isEmpty() ? 1 : 0)
                .param("projectIds", projectIds.isEmpty() ? List.of(-1L) : projectIds)
                .param("ownWork", ownWork ? 1 : 0)
                .param("userId", userId)
                .param("resourceId", resourceId)
                .query((rs, n) -> new ScorecardRow(
                        rs.getLong("user_id"),
                        rs.getString("full_name"),
                        rs.getLong("closed"),
                        rs.getLong("on_time"),
                        rs.getLong("committed"),
                        rs.getBigDecimal("avg_cycle_hours"),
                        rs.getBigDecimal("actual_hours"),
                        rs.getBigDecimal("estimated_hours"),
                        rs.getLong("reopened"),
                        rs.getLong("assigned_now")))
                .list();
    }

    record ScorecardRow(long userId, String fullName, long closed, long onTime, long committed,
                        BigDecimal avgCycleHours, BigDecimal actualHours, BigDecimal estimatedHours,
                        long reopened, long assignedNow) {
    }

    // ── 2 · velocity, from the summary table ─────────────────────────────────

    /**
     * Closed and effort per person per ISO week, from {@code resource_daily_stats}.
     *
     * <p>The one report of the six a summary table can answer, so it does. A-050
     * records exactly these two figures per person per day and both are flow, so
     * summing them into weeks is meaningful. Reading {@code tickets} here would
     * be a live aggregate for an answer already computed every five minutes.
     *
     * <p>{@code YEARWEEK(..., 3)} is ISO-8601: weeks start Monday and week 1 is
     * the one holding the first Thursday. Mode 0 would start weeks on Sunday and
     * disagree with every other week boundary in the product.
     *
     * <p>A PM's scope is applied by <em>membership</em>, not by project column —
     * {@code resource_daily_stats} has none. A-051 recorded that limitation and
     * A-056 met it too: a developer on three projects appears with their whole
     * output, not the part belonging to the asking PM.
     */
    List<VelocityRow> velocity(LocalDate from, LocalDate to, boolean ownWork, long userId,
                               Long resourceId, List<Long> memberOfProjects) {
        return jdbc.sql("""
                        SELECT YEARWEEK(r.stat_date, 3) AS iso_week,
                               MIN(r.stat_date)         AS week_start,
                               u.full_name              AS full_name,
                               SUM(r.closed)            AS closed,
                               SUM(r.effort_hours)      AS effort_hours
                          FROM resource_daily_stats r
                          JOIN users u ON u.id = r.user_id
                         WHERE r.stat_date BETWEEN :from AND :to
                           AND (:ownWork = 0 OR r.user_id = :userId)
                           AND (:resourceId IS NULL OR r.user_id = :resourceId)
                           AND (:unscoped = 1 OR EXISTS (
                                   SELECT 1 FROM project_members pm
                                    WHERE pm.user_id = r.user_id
                                      AND pm.project_id IN (:projectIds)))
                      GROUP BY iso_week, u.full_name
                      ORDER BY iso_week, u.full_name
                        """)
                .param("from", from)
                .param("to", to)
                .param("ownWork", ownWork ? 1 : 0)
                .param("userId", userId)
                .param("resourceId", resourceId)
                .param("unscoped", memberOfProjects.isEmpty() ? 1 : 0)
                .param("projectIds", memberOfProjects.isEmpty() ? List.of(-1L) : memberOfProjects)
                .query((rs, n) -> new VelocityRow(
                        rs.getObject("week_start", LocalDate.class),
                        rs.getString("full_name"),
                        rs.getLong("closed"),
                        rs.getBigDecimal("effort_hours")))
                .list();
    }

    record VelocityRow(LocalDate weekStart, String fullName, long closed, BigDecimal effortHours) {
    }

    // ── 3 · effort summary ───────────────────────────────────────────────────

    /**
     * Effort by resource × project × task type, from the effort log itself.
     *
     * <p>Not from {@code tickets.total_effort_hrs}, which is a per-ticket total
     * with no way to split it across the people who contributed — a ticket three
     * people worked on would attribute all of it to whoever holds it now.
     * {@code ticket_effort_logs} is keyed by {@code user_id} and is the only
     * source that can answer "who spent the hours".
     *
     * <p>Attributed to {@code work_date} per §4A.4: a timesheet filled in on
     * Friday for Monday's work belongs to Monday, which is also why A-051
     * recomputes a trailing week.
     *
     * <p><b>Corrections are summed, not excluded.</b> A correcting row carries a
     * signed value that cancels the entry it reverses, so filtering
     * {@code is_correction} out would restore the mistake it exists to undo.
     */
    List<EffortRow> effortSummary(LocalDate from, LocalDate to, List<Long> projectIds,
                                  boolean ownWork, long userId, Long resourceId, Long taskTypeId) {
        return jdbc.sql("""
                        SELECT u.full_name AS full_name,
                               p.name      AS project_name,
                               tt.name     AS task_type,
                               SUM(e.hours) AS hours,
                               COUNT(*)     AS entries,
                               COUNT(DISTINCT e.ticket_id) AS tickets
                          FROM ticket_effort_logs e
                          JOIN tickets t     ON t.id = e.ticket_id
                          JOIN users u       ON u.id = e.user_id
                          JOIN projects p    ON p.id = t.project_id
                          JOIN task_types tt ON tt.id = t.task_type_id
                         WHERE e.work_date BETWEEN :from AND :to
                           AND (:unscoped = 1 OR t.project_id IN (:projectIds))
                           AND (:ownWork = 0 OR e.user_id = :userId)
                           AND (:resourceId IS NULL OR e.user_id = :resourceId)
                           AND (:taskTypeId IS NULL OR t.task_type_id = :taskTypeId)
                      GROUP BY u.full_name, p.name, tt.name
                      ORDER BY hours DESC
                        """)
                .param("from", from)
                .param("to", to)
                .param("unscoped", projectIds.isEmpty() ? 1 : 0)
                .param("projectIds", projectIds.isEmpty() ? List.of(-1L) : projectIds)
                .param("ownWork", ownWork ? 1 : 0)
                .param("userId", userId)
                .param("resourceId", resourceId)
                .param("taskTypeId", taskTypeId)
                .query((rs, n) -> new EffortRow(
                        rs.getString("full_name"), rs.getString("project_name"), rs.getString("task_type"),
                        rs.getBigDecimal("hours"), rs.getLong("entries"), rs.getLong("tickets")))
                .list();
    }

    record EffortRow(String fullName, String projectName, String taskType,
                     BigDecimal hours, long entries, long tickets) {
    }

    // ── 4 · SLA breach ───────────────────────────────────────────────────────

    /**
     * Every breach in the window — a listing, not an aggregate.
     *
     * <p>A breach is a ticket whose commitment passed before it closed, or which
     * is still open past it. Tickets with no {@code planned_close_date} are
     * absent rather than counted as met: no commitment was made, so none was
     * broken. A-057 drew the same line for the SLA gauge, and it is why that
     * gauge has two columns rather than one.
     *
     * <p>The escalation count and the reason join the two places that actually
     * record them — {@code l2_escalations} (D-024) and the latest
     * {@code ticket_history} remark, which is where a human explanation ends up.
     * Both are nullable and rendered as such: an invented reason on a compliance
     * report is worse than an empty cell.
     */
    List<BreachRow> slaBreaches(LocalDate from, LocalDate to, List<Long> projectIds,
                                boolean ownWork, long userId, String level, Long taskTypeId) {
        return jdbc.sql("""
                        SELECT t.ticket_code AS ticket_code,
                               p.name        AS project_name,
                               t.level       AS level,
                               u.full_name   AS assignee,
                               t.status      AS status,
                               TIMESTAMPDIFF(HOUR, t.planned_close_date,
                                   COALESCE(t.actual_close_date, UTC_TIMESTAMP())) AS overdue_hours,
                               (SELECT COUNT(*) FROM l2_escalations l
                                 WHERE l.ticket_id = t.id) AS escalations,
                               (SELECT h.remarks FROM ticket_history h
                                 WHERE h.ticket_id = t.id
                                   AND h.remarks IS NOT NULL AND h.remarks <> ''
                              ORDER BY h.id DESC LIMIT 1) AS reason
                          FROM tickets t
                          JOIN projects p ON p.id = t.project_id
                          LEFT JOIN users u ON u.id = t.assigned_to
                         WHERE t.planned_close_date IS NOT NULL
                           AND t.planned_close_date >= :from
                           AND t.planned_close_date < :toExclusive
                           AND (t.actual_close_date > t.planned_close_date
                                OR (t.actual_close_date IS NULL
                                    AND t.planned_close_date < UTC_TIMESTAMP()))
                           AND (:unscoped = 1 OR t.project_id IN (:projectIds))
                           AND (:ownWork = 0 OR t.assigned_to = :userId)
                           AND (:level IS NULL OR t.level = :level)
                           AND (:taskTypeId IS NULL OR t.task_type_id = :taskTypeId)
                      ORDER BY overdue_hours DESC
                        """)
                .param("from", from)
                .param("toExclusive", to.plusDays(1))
                .param("unscoped", projectIds.isEmpty() ? 1 : 0)
                .param("projectIds", projectIds.isEmpty() ? List.of(-1L) : projectIds)
                .param("ownWork", ownWork ? 1 : 0)
                .param("userId", userId)
                .param("level", level)
                .param("taskTypeId", taskTypeId)
                .query((rs, n) -> new BreachRow(
                        rs.getString("ticket_code"), rs.getString("project_name"), rs.getString("level"),
                        rs.getString("assignee"), rs.getString("status"),
                        rs.getLong("overdue_hours"), rs.getLong("escalations"), rs.getString("reason")))
                .list();
    }

    record BreachRow(String ticketCode, String projectName, String level, String assignee,
                     String status, long overdueHours, long escalations, String reason) {
    }

    // ── 5 · task type analysis ───────────────────────────────────────────────

    /**
     * Volume and average resolution time per task type.
     *
     * <p>Volume counts tickets <b>raised</b> in the window; resolution averages
     * those <b>closed</b> in it. Two populations, deliberately. Averaging the
     * cycle time of tickets raised in the window would exclude everything still
     * open, so a type whose tickets take three months would look fast — only its
     * quick ones would have closed in time to be counted.
     */
    List<TaskTypeRow> taskTypeAnalysis(LocalDate from, LocalDate to, List<Long> projectIds,
                                       boolean ownWork, long userId, Long taskTypeId) {
        return jdbc.sql("""
                        SELECT tt.name AS task_type,
                               SUM(CASE WHEN t.date_reported >= :from
                                         AND t.date_reported < :toExclusive
                                        THEN 1 ELSE 0 END) AS raised,
                               SUM(CASE WHEN t.actual_close_date >= :from
                                         AND t.actual_close_date < :toExclusive
                                        THEN 1 ELSE 0 END) AS closed,
                               AVG(CASE WHEN t.actual_close_date >= :from
                                         AND t.actual_close_date < :toExclusive
                                        THEN TIMESTAMPDIFF(HOUR, t.date_reported, t.actual_close_date)
                                   END) AS avg_resolution_hours,
                               SUM(CASE WHEN t.status <> 'CLOSED' THEN 1 ELSE 0 END) AS still_open
                          FROM tickets t
                          JOIN task_types tt ON tt.id = t.task_type_id
                         WHERE t.date_reported < :toExclusive
                           AND (:unscoped = 1 OR t.project_id IN (:projectIds))
                           AND (:ownWork = 0 OR t.assigned_to = :userId)
                           AND (:taskTypeId IS NULL OR t.task_type_id = :taskTypeId)
                      GROUP BY tt.id, tt.name
                      HAVING raised > 0 OR closed > 0 OR still_open > 0
                      ORDER BY raised DESC, tt.name
                        """)
                .param("from", from)
                .param("toExclusive", to.plusDays(1))
                .param("unscoped", projectIds.isEmpty() ? 1 : 0)
                .param("projectIds", projectIds.isEmpty() ? List.of(-1L) : projectIds)
                .param("ownWork", ownWork ? 1 : 0)
                .param("userId", userId)
                .param("taskTypeId", taskTypeId)
                .query((rs, n) -> new TaskTypeRow(
                        rs.getString("task_type"), rs.getLong("raised"), rs.getLong("closed"),
                        rs.getBigDecimal("avg_resolution_hours"), rs.getLong("still_open")))
                .list();
    }

    record TaskTypeRow(String taskType, long raised, long closed,
                       BigDecimal avgResolutionHours, long stillOpen) {
    }

    // ── 6 · reopen analysis ──────────────────────────────────────────────────

    /**
     * Reopens by resource, project and task type — §7.8's "quality signal".
     *
     * <p>All three groupings at once rather than three reports: the question
     * people arrive with is "where do reopens cluster", and one sortable table
     * answers it in a pass.
     *
     * <p>{@code reopen_count} rather than {@code is_reopened}: a ticket reopened
     * three times is three failures to resolve it, and the boolean would put the
     * worst case in the same bucket as the mildest.
     */
    List<ReopenRow> reopenAnalysis(LocalDate from, LocalDate to, List<Long> projectIds,
                                   boolean ownWork, long userId, Long resourceId) {
        return jdbc.sql("""
                        SELECT COALESCE(u.full_name, '(unassigned)') AS full_name,
                               p.name  AS project_name,
                               tt.name AS task_type,
                               COUNT(*)            AS tickets,
                               SUM(t.reopen_count) AS reopens,
                               SUM(CASE WHEN t.reopen_count > 0 THEN 1 ELSE 0 END) AS reopened_tickets
                          FROM tickets t
                          JOIN projects p    ON p.id = t.project_id
                          JOIN task_types tt ON tt.id = t.task_type_id
                          LEFT JOIN users u  ON u.id = t.assigned_to
                         WHERE t.date_reported >= :from
                           AND t.date_reported < :toExclusive
                           AND (:unscoped = 1 OR t.project_id IN (:projectIds))
                           AND (:ownWork = 0 OR t.assigned_to = :userId)
                           AND (:resourceId IS NULL OR t.assigned_to = :resourceId)
                      GROUP BY u.full_name, p.name, tt.name
                      HAVING reopens > 0
                      ORDER BY reopens DESC
                        """)
                .param("from", from)
                .param("toExclusive", to.plusDays(1))
                .param("unscoped", projectIds.isEmpty() ? 1 : 0)
                .param("projectIds", projectIds.isEmpty() ? List.of(-1L) : projectIds)
                .param("ownWork", ownWork ? 1 : 0)
                .param("userId", userId)
                .param("resourceId", resourceId)
                .query((rs, n) -> new ReopenRow(
                        rs.getString("full_name"), rs.getString("project_name"), rs.getString("task_type"),
                        rs.getLong("tickets"), rs.getLong("reopens"), rs.getLong("reopened_tickets")))
                .list();
    }

    record ReopenRow(String fullName, String projectName, String taskType,
                     long tickets, long reopens, long reopenedTickets) {
    }
    // ── A-067 · reports 11 and 12, from the stage transitions ────────────────

    /**
     * A-067 report 11 · the stage funnel — "how many tickets sit at each ribbon
     * stage, and where they stop".
     *
     * <p>Read from {@code tickets.current_stage} rather than from
     * {@code daily_ticket_stats.wip_by_stage}. A-050 declared that column and
     * left it NULL deliberately — "a point-in-time column cannot be backfilled"
     * — and A-058, which fills it, has not landed. Every row of it is still NULL
     * today, so a funnel reading it would draw an empty chart and call it data.
     *
     * <p>Two counts per stage, and the difference is the report. {@code sitting}
     * is how many open tickets are at that stage now; {@code passedThrough} is
     * how many entered it in the window at all. A stage where those two are
     * close is a stage work stops at — which is what "where they stop" asks.
     */
    List<FunnelRow> stageFunnel(LocalDate from, LocalDate to, List<Long> projectIds,
                                boolean ownWork, long userId) {
        return jdbc.sql("""
                        SELECT s.stage_code,
                               MIN(s.seq)  AS seq,
                               MIN(s.display_name) AS display_name,
                               (SELECT COUNT(*) FROM tickets t
                                 WHERE t.current_stage = s.stage_code
                                   AND t.status <> 'CLOSED'
                                   AND (:unscoped = 1 OR t.project_id IN (:projectIds))
                                   AND (:ownWork = 0 OR t.assigned_to = :userId)) AS sitting,
                               (SELECT COUNT(DISTINCT tr.ticket_id)
                                  FROM ticket_stage_transitions tr
                                  JOIN tickets t2 ON t2.id = tr.ticket_id
                                 WHERE tr.to_stage = s.stage_code
                                   AND tr.entered_at >= :from
                                   AND tr.entered_at < :toExclusive
                                   AND (:unscoped = 1 OR t2.project_id IN (:projectIds))
                                   AND (:ownWork = 0 OR t2.assigned_to = :userId)) AS passed_through
                          FROM workflow_stages s
                      GROUP BY s.stage_code
                      ORDER BY seq, s.stage_code
                        """)
                .param("from", from)
                .param("toExclusive", to.plusDays(1))
                .param("unscoped", projectIds.isEmpty() ? 1 : 0)
                .param("projectIds", projectIds.isEmpty() ? List.of(-1L) : projectIds)
                .param("ownWork", ownWork ? 1 : 0)
                .param("userId", userId)
                .query((rs, n) -> new FunnelRow(
                        rs.getString("stage_code"), rs.getString("display_name"),
                        rs.getLong("sitting"), rs.getLong("passed_through")))
                .list();
    }

    record FunnelRow(String stageCode, String displayName, long sitting, long passedThrough) {
    }

    /**
     * A-067 report 12 · stage cycle time — "average time per stage, split into
     * active work and idle waiting".
     *
     * <h2>The split is the report, and it is only possible because two tables
     * agree on a stage code</h2>
     *
     * <p>{@code ticket_stage_transitions.duration_mins} is elapsed time: how long
     * the ticket sat in that stage, weekends included.
     * {@code ticket_effort_logs.stage_code} is what somebody actually logged
     * working on it there. <b>Idle is the remainder</b> — time the ticket spent
     * in a stage with nobody recorded as working on it.
     *
     * <p>That is the number §7.8 is after: a stage averaging four days of which
     * three hours were worked is not slow because the work is hard.
     *
     * <h2>Only sealed transitions count</h2>
     *
     * <p>{@code exited_at IS NOT NULL}. An unsealed row is a ticket <em>still</em>
     * in that stage, and its duration is not yet a fact — including it would
     * average a partial stay against completed ones and pull every figure down,
     * most for the stages where work is currently piling up. Sealing is the one
     * mutation A-008 permits on that table, and it is what makes the row final.
     *
     * <p>Effort is attributed by {@code stage_code} rather than by transition,
     * because the effort log holds no transition reference — so a stage entered
     * twice on a rework loop has its hours counted against the stage rather than
     * against one visit, making the active share on a reworked stage an upper
     * bound rather than exact.
     *
     * <p>It is aggregated in a <b>derived table carrying the same scope
     * predicates</b>, not a correlated subquery over ticket ids. The first
     * version filtered only on stage code and window, so a PM's report summed
     * effort logged by people on projects they cannot see — wrong numbers, and a
     * disclosure of activity outside their scope. Found by an integration test
     * whose fixture happened to leave another test's rows in the table.
     */
    List<StageTimeRow> stageCycleTime(LocalDate from, LocalDate to, List<Long> projectIds,
                                      boolean ownWork, long userId) {
        return jdbc.sql("""
                        SELECT v.to_stage AS stage_code,
                               COUNT(*)   AS visits,
                               AVG(v.duration_mins) / 60 AS avg_elapsed_hours,
                               SUM(v.duration_mins) / 60 AS total_elapsed_hours,
                               COALESCE(MAX(a.active_hours), 0) AS active_hours
                          FROM ticket_stage_transitions v
                          JOIN tickets t ON t.id = v.ticket_id
                          LEFT JOIN (
                                SELECT e.stage_code, SUM(e.hours) AS active_hours
                                  FROM ticket_effort_logs e
                                  JOIN tickets et ON et.id = e.ticket_id
                                 WHERE e.work_date BETWEEN :from AND :to
                                   AND (:unscoped = 1 OR et.project_id IN (:projectIds))
                                   AND (:ownWork = 0 OR et.assigned_to = :userId)
                              GROUP BY e.stage_code) a ON a.stage_code = v.to_stage
                         WHERE v.exited_at IS NOT NULL
                           AND v.entered_at >= :from
                           AND v.entered_at < :toExclusive
                           AND (:unscoped = 1 OR t.project_id IN (:projectIds))
                           AND (:ownWork = 0 OR t.assigned_to = :userId)
                      GROUP BY v.to_stage
                      ORDER BY avg_elapsed_hours DESC
                        """)
                .param("from", from)
                .param("to", to)
                .param("toExclusive", to.plusDays(1))
                .param("unscoped", projectIds.isEmpty() ? 1 : 0)
                .param("projectIds", projectIds.isEmpty() ? List.of(-1L) : projectIds)
                .param("ownWork", ownWork ? 1 : 0)
                .param("userId", userId)
                .query((rs, n) -> new StageTimeRow(
                        rs.getString("stage_code"),
                        rs.getLong("visits"),
                        rs.getBigDecimal("avg_elapsed_hours"),
                        rs.getBigDecimal("total_elapsed_hours"),
                        rs.getBigDecimal("active_hours")))
                .list();
    }

    record StageTimeRow(String stageCode, long visits, BigDecimal avgElapsedHours,
                        BigDecimal totalElapsedHours, BigDecimal activeHours) {
    }
}
