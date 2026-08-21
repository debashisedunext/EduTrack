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

    // ── 13 · client report (B-060) ────────────────────────────────

    /**
     * B-060 · one row per client. §7.8: "volume, open versus closed, SLA
     * compliance, avg resolution time and satisfaction per client".
     *
     * <h2>Four of the five figures, and the fifth is not withheld — it is not
     * recorded</h2>
     *
     * <p>There is no CSAT column in this schema, in any of the forty-odd
     * migrations, or in the contract. Blueprint §17 item 19 — <i>"CSAT /
     * feedback on closure — a 1–5 rating drives the client-satisfaction
     * report"</i> — is a phase 2–3 item that has not been built. So this query
     * returns no satisfaction, and {@link ClientReportRunner} declares no column
     * for it rather than averaging something adjacent into a number a person
     * would read as a score. This is the report §7.8 describes as shaped to be
     * sent to a client, which is the worst place in the product for an invented
     * figure.
     *
     * <h2>Three populations in one row, on purpose</h2>
     *
     * <p>{@code raised} counts tickets <b>reported</b> in the window,
     * {@code closed} and both SLA halves count those <b>closed</b> in it, and
     * {@code open_now} is a <b>stock</b> taken at read time and deliberately
     * unbounded by the window. Forcing all three onto one population would make
     * each answer a question nobody asked: tickets both raised and closed inside
     * a fortnight are the fast ones, so an average over them flatters every slow
     * client, and "open" restricted to the window would omit exactly the backlog
     * the client is ringing about. The same split {@code taskTypeAnalysis} above
     * states for its two.
     *
     * <h2>The SLA denominator is commitments, not closures</h2>
     *
     * <p>{@code sla_committed} counts closed tickets that had a
     * {@code planned_close_date}; {@code sla_met} those that also beat it. A
     * ticket nobody promised a date for can neither meet an SLA nor breach one,
     * so counting it either way is a claim about a commitment that was never
     * made — A-057's migration makes that argument for the dashboard gauge and
     * this is the same ratio one screen over. A client whose closed tickets all
     * lack a planned date gets a null percentage, which the table renders as an
     * em dash rather than as 0% or 100%.
     *
     * <h2>Internal tickets are excluded, and that is not a filter</h2>
     *
     * <p>{@code client_id} is nullable — §4B.7's own comment says an internally
     * raised ticket can still belong to a client, and by the same token a ticket
     * can belong to none. The {@code JOIN} drops those. A "(no client)" row
     * would be the largest one on most deployments and belongs to no client, on
     * a report whose every row is a client.
     *
     * <p><b>{@code is_client_raised} is likewise not consulted.</b> §4B.7 makes
     * it the flag that "drives client-wise reporting", and it is the wrong one
     * here: a client's experience covers every ticket about their work,
     * including the ones the desk raised on their behalf. Filtering by it would
     * report a subset under the client's name.
     *
     * <h2>Scope</h2>
     *
     * <p>Applied over {@code t.project_id}, so a PM sees this client's tickets
     * on their own projects and nobody else's. That makes the figures partial
     * rather than wrong, which is what {@code meta.appliedScope} exists to say:
     * a client on four projects, read by a PM who owns two, is two projects'
     * worth of that client, and the response states so in words.
     *
     * @param clientId one client, or null for every client with something in
     *                 range. Never widens scope — an out-of-scope client simply
     *                 has no rows the caller may read, which is the
     *                 404-not-403 shape §7 of the conventions asks for.
     */
    List<ClientRow> clientReport(LocalDate from, LocalDate to, List<Long> projectIds,
                                 boolean ownWork, long userId, Long clientId) {
        return jdbc.sql("""
                        SELECT c.id          AS client_id,
                               c.name        AS client_name,
                               c.client_code AS client_code,
                               SUM(CASE WHEN t.date_reported >= :from
                                         AND t.date_reported < :toExclusive
                                        THEN 1 ELSE 0 END) AS raised,
                               SUM(CASE WHEN t.actual_close_date >= :from
                                         AND t.actual_close_date < :toExclusive
                                        THEN 1 ELSE 0 END) AS closed,
                               SUM(CASE WHEN t.status <> 'CLOSED' THEN 1 ELSE 0 END) AS open_now,
                               SUM(CASE WHEN t.actual_close_date >= :from
                                         AND t.actual_close_date < :toExclusive
                                         AND t.planned_close_date IS NOT NULL
                                        THEN 1 ELSE 0 END) AS sla_committed,
                               SUM(CASE WHEN t.actual_close_date >= :from
                                         AND t.actual_close_date < :toExclusive
                                         AND t.planned_close_date IS NOT NULL
                                         AND t.actual_close_date <= t.planned_close_date
                                        THEN 1 ELSE 0 END) AS sla_met,
                               AVG(CASE WHEN t.actual_close_date >= :from
                                         AND t.actual_close_date < :toExclusive
                                        THEN TIMESTAMPDIFF(HOUR, t.date_reported, t.actual_close_date)
                                   END) AS avg_resolution_hours
                          FROM tickets t
                          JOIN clients c ON c.id = t.client_id
                         WHERE t.date_reported < :toExclusive
                           AND (:unscoped = 1 OR t.project_id IN (:projectIds))
                           AND (:ownWork = 0 OR t.assigned_to = :userId)
                           AND (:clientId IS NULL OR t.client_id = :clientId)
                      GROUP BY c.id, c.name, c.client_code
                        HAVING raised > 0 OR closed > 0 OR open_now > 0
                      ORDER BY raised DESC, open_now DESC, c.name
                        """)
                .param("from", from)
                // Exclusive upper bound, for the reason `scorecard` states: a
                // DATETIME(6) compared `<= :to` means midnight, and silently
                // drops the last day of the range the user actually asked for.
                .param("toExclusive", to.plusDays(1))
                .param("unscoped", projectIds.isEmpty() ? 1 : 0)
                .param("projectIds", projectIds.isEmpty() ? List.of(-1L) : projectIds)
                .param("ownWork", ownWork ? 1 : 0)
                .param("userId", userId)
                .param("clientId", clientId)
                .query((rs, n) -> new ClientRow(
                        rs.getLong("client_id"),
                        rs.getString("client_name"),
                        rs.getString("client_code"),
                        rs.getLong("raised"),
                        rs.getLong("closed"),
                        rs.getLong("open_now"),
                        rs.getLong("sla_committed"),
                        rs.getLong("sla_met"),
                        rs.getBigDecimal("avg_resolution_hours")))
                .list();
    }

    /**
     * @param slaCommitted the denominator, carried rather than divided in SQL so
     *                     the runner can tell "nothing was committed" (a null
     *                     percentage) from "nothing was met" (0%). Collapsing to
     *                     a ratio here loses that distinction, and the two are
     *                     opposite readings of the same client.
     */
    record ClientRow(long clientId, String clientName, String clientCode, long raised, long closed,
                     long openNow, long slaCommitted, long slaMet, BigDecimal avgResolutionHours) {
    }

    // ── A-070 · born critical versus became critical (§6) ────────────────────

    /**
     * A-070 · the four quadrants of {@code original_level} against {@code level}.
     *
     * <h2>Why this needs no new column, and what it rests on</h2>
     *
     * <p>{@code tickets.original_level} is written once at creation and the
     * schema calls it "never mutated"; {@code level} carries the current
     * answer. Between them every ticket sits in one of four states — arrived
     * critical and still is, arrived critical and was downgraded, arrived lower
     * and was raised, arrived lower and still is. The escalation engine
     * (D-028's {@code SlaEscalation}) updates {@code level} alone <em>for this
     * report</em>, and says so in its own javadoc.
     *
     * <h2>The cohort is the window, and both halves share it</h2>
     *
     * <p>Everything here is bounded by {@code date_reported}, including the
     * became-critical count — even though becoming critical happens later, and
     * often much later. That is deliberate and it is the decision a reader
     * should check first.
     *
     * <p>The alternative is to count born-critical by when the ticket arrived
     * and became-critical by when the history row was written, which produces
     * two numbers measured on different clocks that cannot be added, shared or
     * compared. One cohort — "of the tickets raised in this window" — gives one
     * denominator and a share that means something.
     *
     * <p><b>The cost is that recent windows understate becoming.</b> A ticket
     * raised yesterday has not had time to breach, so "last week" will always
     * look better than "last quarter". {@code CriticalOriginRunner} puts that
     * in the report's own description rather than leaving it to be discovered
     * by somebody drawing a trend from it.
     *
     * <h2>Attribution reads the latest row, not any row</h2>
     *
     * <p>A ticket can cross CRITICAL more than once: escalated by the scanner,
     * downgraded by a manager, raised again by hand. {@code EXISTS (… actor_type
     * = 'SYSTEM')} would call that one an SLA escalation, which is what it was
     * two changes ago and not what it is. The subquery therefore takes the
     * <em>most recent</em> {@code LEVEL_CHANGED} row that set CRITICAL and asks
     * who wrote it — the change that put the ticket where it is now.
     *
     * <p>Ordered by {@code created_at} then {@code id}, because two rows can
     * share a microsecond and {@code id} is the tiebreak the journal's own
     * chain walk uses.
     *
     * <h2>🔴 Three outcomes, not two, because "no record" is not "a person"</h2>
     *
     * <p>The first version returned {@code escalated_by_sla} alone and had the
     * runner derive "raised by a person" as the remainder. The arithmetic was
     * sound and the label was a small lie, which running it against the B-007
     * corpus made obvious: 77 tickets there are critical without having arrived
     * that way and only 7 carry a {@code LEVEL_CHANGED} row, so seventy would
     * have been reported as somebody's decision when the truth is that nothing
     * recorded one.
     *
     * <p>So both branches are counted here, from the <em>same</em> subquery
     * expression — mutually exclusive by construction, since a row is written
     * by one actor type or the other — and the runner derives the third as what
     * is left. The three partition {@code became_critical} exactly and can
     * never disagree with their own total.
     *
     * <p>The third column earns its place beyond honesty: in production it
     * should be zero. Every real level change goes through
     * {@code PriorityChangeController}, which journals it as a person, or
     * {@code SlaEscalation}, which journals it as SYSTEM. A non-zero count
     * means something moved {@code level} without writing history, which is
     * worth seeing on a report rather than silently folded into a column that
     * names an actor.
     */
    List<CriticalOriginRow> criticalOrigin(LocalDate from, LocalDate to, List<Long> projectIds,
                                           boolean ownWork, long userId, Long resourceId,
                                           Long taskTypeId) {
        return jdbc.sql("""
                        SELECT p.name  AS project_name,
                               tt.name AS task_type,
                               COUNT(*) AS tickets,
                               SUM(t.original_level = 'CRITICAL')                     AS born_critical,
                               SUM(t.level = 'CRITICAL' AND t.original_level <> 'CRITICAL')
                                                                                      AS became_critical,
                               SUM(t.original_level = 'CRITICAL' AND t.level <> 'CRITICAL')
                                                                                      AS de_escalated,
                               SUM(t.level = 'CRITICAL')                              AS critical_now,
                               SUM(t.level = 'CRITICAL' AND t.original_level <> 'CRITICAL'
                                   AND (SELECT h.actor_type
                                          FROM ticket_history h
                                         WHERE h.ticket_id = t.id
                                           AND h.event_type = 'LEVEL_CHANGED'
                                           AND h.new_value = 'CRITICAL'
                                         ORDER BY h.created_at DESC, h.id DESC
                                         LIMIT 1) = 'SYSTEM')                         AS escalated_by_sla,
                               SUM(t.level = 'CRITICAL' AND t.original_level <> 'CRITICAL'
                                   AND (SELECT h.actor_type
                                          FROM ticket_history h
                                         WHERE h.ticket_id = t.id
                                           AND h.event_type = 'LEVEL_CHANGED'
                                           AND h.new_value = 'CRITICAL'
                                         ORDER BY h.created_at DESC, h.id DESC
                                         LIMIT 1) = 'USER')                           AS raised_by_person
                          FROM tickets t
                          JOIN projects p    ON p.id = t.project_id
                          JOIN task_types tt ON tt.id = t.task_type_id
                         WHERE t.date_reported >= :from
                           AND t.date_reported < :toExclusive
                           AND (:unscoped = 1 OR t.project_id IN (:projectIds))
                           AND (:ownWork = 0 OR t.assigned_to = :userId)
                           AND (:resourceId IS NULL OR t.assigned_to = :resourceId)
                           AND (:taskTypeId IS NULL OR t.task_type_id = :taskTypeId)
                      GROUP BY p.name, tt.name
                        -- A row where nothing was ever critical is not an
                        -- absence of a problem worth printing — it is every
                        -- other project and type, and it would bury the ones
                        -- that are. ReopenAnalysisRunner omits its quiet rows
                        -- for the same reason.
                        HAVING born_critical > 0 OR became_critical > 0
                        -- Became first: it is the half the team can do
                        -- something about, and the half that is a statement
                        -- about us rather than about what was sent to us.
                      ORDER BY became_critical DESC, born_critical DESC, p.name, tt.name
                        """)
                .param("from", from)
                .param("toExclusive", to.plusDays(1))
                .param("unscoped", projectIds.isEmpty() ? 1 : 0)
                .param("projectIds", projectIds.isEmpty() ? List.of(-1L) : projectIds)
                .param("ownWork", ownWork ? 1 : 0)
                .param("userId", userId)
                .param("resourceId", resourceId)
                .param("taskTypeId", taskTypeId)
                .query((rs, n) -> new CriticalOriginRow(
                        rs.getString("project_name"), rs.getString("task_type"),
                        rs.getLong("tickets"), rs.getLong("born_critical"),
                        rs.getLong("became_critical"), rs.getLong("de_escalated"),
                        rs.getLong("critical_now"), rs.getLong("escalated_by_sla"),
                        rs.getLong("raised_by_person")))
                .list();
    }

    /**
     * @param bornCritical   arrived critical, whatever it is now
     * @param becameCritical arrived lower and is critical now
     * @param deEscalated    arrived critical and is not any more — the quadrant
     *                       nobody asks for and would notice the absence of
     * @param criticalNow    {@code bornCritical - deEscalated + becameCritical},
     *                       returned rather than derived so the identity is
     *                       checkable against the row on screen
     * @param escalatedBySla of {@code becameCritical}, the ones whose latest
     *                       change to CRITICAL was written by the SLA scanner
     * @param raisedByPerson of {@code becameCritical}, the ones whose latest
     *                       change to CRITICAL was written by somebody. What is
     *                       left over after these two is the ones with no
     *                       history row at all — derived by the runner rather
     *                       than counted, and not the same claim as either of
     *                       these two.
     */
    record CriticalOriginRow(String projectName, String taskType, long tickets,
                             long bornCritical, long becameCritical, long deEscalated,
                             long criticalNow, long escalatedBySla, long raisedByPerson) {
    }

    // ── A-068 · reports 13, 14 and 15, from the transitions and effort logs ──

    /**
     * A-068 report 13 · Rework Analysis — §7.8's "rework rate by developer, QA
     * rejection rate, first-time-right %".
     *
     * <h2>The unit is a backward move, not a reworked ticket</h2>
     *
     * <p>{@code reopenAnalysis} above makes the same distinction one report over
     * and for the same reason: a ticket sent back four times is four failures,
     * and counting {@code rework_count > 0} would put it in the same bucket as
     * one sent back once — precisely the case this report exists to find. Both
     * figures are returned and the runner shows both.
     *
     * <h2>Attributed to whoever sent it back, and to the pair of stages</h2>
     *
     * <p>{@code from_user_id} is the person who rejected the work and
     * {@code from_stage} is where they were standing, so §7.8's "QA rejection
     * rate" is this grouping read at QA rather than a second query. Grouping by
     * the <em>pair</em> is what answers the catalogue's "how often the same pair
     * repeats it", which a per-person count averages away: two developers each
     * bounced once is a different problem from one pair bounced twice.
     *
     * <h2>The four backward action codes are listed, not derived</h2>
     *
     * <p>{@code TransitionService.BACKWARD_ACTIONS} holds the same four. They
     * are repeated here because this is SQL and they reach the database as
     * literals either way; {@code ReportRunnersIT} asserts the two sets agree,
     * so the duplication is checked rather than trusted.
     *
     * <h2>What this returns today, stated rather than discovered later</h2>
     *
     * <p><b>{@code ticket_stage_transitions} has no rows in a running
     * application.</b> Only {@code TransitionService.advance} writes to it, and
     * it refuses to act without an already-open hop — which nothing opens.
     * {@code NoOpenStageException}'s own javadoc names that state and leaves it
     * to somebody else, {@code ReworkService} and {@code HandoffService} both
     * inherit the refusal, and every test that needs a hop either inserts one by
     * raw SQL or mocks {@code openHopFor}.
     *
     * <p>So this query is correct and returns nothing, and starts returning rows
     * the day a first hop is written. That is not a placeholder — it is a true
     * answer to a question nothing has yet produced data for. What must not
     * happen is the emptiness being turned into a reassuring percentage, and
     * {@code ReworkAnalysisRunner} is where that is prevented.
     */
    List<ReworkRow> reworkAnalysis(LocalDate from, LocalDate to, List<Long> projectIds,
                                   boolean ownWork, long userId, Long resourceId) {
        return jdbc.sql("""
                        SELECT COALESCE(u.full_name, '(system)') AS full_name,
                               p.name        AS project_name,
                               tr.from_stage AS from_stage,
                               tr.to_stage   AS to_stage,
                               COUNT(*)                     AS bounces,
                               COUNT(DISTINCT tr.ticket_id) AS tickets_affected
                          FROM ticket_stage_transitions tr
                          JOIN tickets t    ON t.id = tr.ticket_id
                          JOIN projects p   ON p.id = t.project_id
                          LEFT JOIN users u ON u.id = tr.from_user_id
                         WHERE tr.action_code IN ('REWORK', 'VERIFY_FAILED',
                                                  'DEPLOY_FAILED', 'SIGNOFF_REJECTED')
                           AND tr.entered_at >= :fromTs
                           AND tr.entered_at < :toExclusiveTs
                           AND (:unscoped = 1 OR t.project_id IN (:projectIds))
                           AND (:ownWork = 0 OR t.assigned_to = :userId)
                           AND (:resourceId IS NULL OR tr.from_user_id = :resourceId)
                      GROUP BY u.full_name, p.name, tr.from_stage, tr.to_stage
                      ORDER BY bounces DESC, full_name
                        """)
                .param("fromTs", from.atStartOfDay())
                .param("toExclusiveTs", to.plusDays(1).atStartOfDay())
                .param("unscoped", projectIds.isEmpty() ? 1 : 0)
                .param("projectIds", projectIds.isEmpty() ? List.of(-1L) : projectIds)
                .param("ownWork", ownWork ? 1 : 0)
                .param("userId", userId)
                .param("resourceId", resourceId)
                .query((rs, n) -> new ReworkRow(
                        rs.getString("full_name"), rs.getString("project_name"),
                        rs.getString("from_stage"), rs.getString("to_stage"),
                        rs.getLong("bounces"), rs.getLong("tickets_affected")))
                .list();
    }

    /**
     * The first-time-right denominator, counted over tickets closed in the
     * window.
     *
     * <h2>A second query rather than a join</h2>
     *
     * <p>The grouping above is per (person, project, stage pair) and this is per
     * project. There is no grouping at which both are true: forcing one would
     * either repeat the closed count on every stage-pair row, where a reader
     * would sum it, or collapse the stage pairs, which are the report.
     *
     * <p>{@code current_iteration = 1} is first-time-right by §7.8's definition
     * — closed without ever having been sent back. The counter only rises and is
     * never reset ({@code TransitionService} increments it on a backward move,
     * and {@code ReopenService} says the same of {@code rework_count} beside
     * it), so its value at close is a faithful record of the whole cycle.
     *
     * <p><b>This one does return rows today</b>, because it reads
     * {@code tickets} rather than the transitions — which is exactly why the
     * runner may not divide one by the other without saying so.
     */
    List<FirstTimeRightRow> firstTimeRight(LocalDate from, LocalDate to, List<Long> projectIds,
                                           boolean ownWork, long userId, Long resourceId) {
        return jdbc.sql("""
                        SELECT p.name AS project_name,
                               COUNT(*) AS closed,
                               SUM(CASE WHEN t.current_iteration = 1 THEN 1 ELSE 0 END) AS ftr
                          FROM tickets t
                          JOIN projects p ON p.id = t.project_id
                         WHERE t.actual_close_date >= :fromTs
                           AND t.actual_close_date < :toExclusiveTs
                           AND (:unscoped = 1 OR t.project_id IN (:projectIds))
                           AND (:ownWork = 0 OR t.assigned_to = :userId)
                           AND (:resourceId IS NULL OR t.assigned_to = :resourceId)
                      GROUP BY p.name
                      ORDER BY p.name
                        """)
                .param("fromTs", from.atStartOfDay())
                .param("toExclusiveTs", to.plusDays(1).atStartOfDay())
                .param("unscoped", projectIds.isEmpty() ? 1 : 0)
                .param("projectIds", projectIds.isEmpty() ? List.of(-1L) : projectIds)
                .param("ownWork", ownWork ? 1 : 0)
                .param("userId", userId)
                .param("resourceId", resourceId)
                .query((rs, n) -> new FirstTimeRightRow(
                        rs.getString("project_name"), rs.getLong("closed"), rs.getLong("ftr")))
                .list();
    }

    /**
     * A-068 report 14 · Deployment Report — §7.8's "deployments per week,
     * success vs rollback, avg deploy duration".
     *
     * <h2>A deployment is a visit to a Deployment-owned stage, not a table</h2>
     *
     * <p>There is no {@code deployments} table and this report does not add one.
     * A deployment in this product is a ticket entering a stage whose owning
     * role is {@code DEPLOYMENT} and leaving it again — which
     * {@code ticket_stage_transitions} already records completely, with
     * {@code duration_mins} in working minutes set on seal. A second table would
     * be a second answer to "was this deployed", and the ribbon would remain the
     * one people believe.
     *
     * <p><b>Keyed on {@code workflow_stages.owner_role}, never on the literal
     * stage code {@code DEPLOY}</b> — {@code HandoffNotifier.leavesDeployment}
     * decides the identical question the identical way, and its note gives the
     * reason: a stage code is a template's own label and B-034 lets an Admin
     * write another one, whereas the role that owns the stage is what §4A.1
     * fixes. The three seeded templates prove the point — each spells its
     * deployment stage {@code DEPLOY}, and nothing makes a fourth do so.
     * {@code owner_role} carries no foreign key to {@code roles} (A-005 made it
     * a plain {@code VARCHAR}), so this is a literal comparison and not a join.
     *
     * <h2>Success versus rollback is the action code on the way out</h2>
     *
     * <p>A visit that left by {@code FORWARD} shipped; one that left by
     * {@code DEPLOY_FAILED} came back. That is the whole distinction and it is
     * recorded on the <em>next</em> hop rather than on the visit itself, because
     * a hop records how it was entered — so the outcome of visit <i>n</i> is
     * read from the action code of visit <i>n+1</i>, joined on {@code seq_no}.
     * Reading {@code action_code} off the visit row itself would report how the
     * deployment was <em>reached</em>, which is a different and plausible-looking
     * number.
     *
     * <h2>Unsealed visits are excluded</h2>
     *
     * <p>{@code exited_at IS NULL} is a deployment still in progress. It has no
     * outcome and no duration, and averaging a partial stay drags every figure
     * down worst for the deployments taking longest right now — {@code A-067}'s
     * stage-cycle-time made the same call for the same reason.
     *
     * <p>Weekly, because §7.8 asks for "deployments per week". The week is
     * keyed by its Monday so the label sorts and groups identically.
     *
     * <p>Reads the transitions, so see {@code reworkAnalysis} above for what
     * that means today.
     */
    List<DeploymentRow> deploymentReport(LocalDate from, LocalDate to, List<Long> projectIds,
                                         boolean ownWork, long userId) {
        return jdbc.sql("""
                        SELECT DATE(DATE_SUB(dep.entered_at,
                                             INTERVAL WEEKDAY(dep.entered_at) DAY)) AS week_start,
                               p.name AS project_name,
                               COUNT(*) AS deployments,
                               SUM(CASE WHEN nxt.action_code = 'DEPLOY_FAILED' THEN 1 ELSE 0 END)
                                   AS rolled_back,
                               SUM(CASE WHEN nxt.action_code IS NULL
                                          OR nxt.action_code <> 'DEPLOY_FAILED' THEN 1 ELSE 0 END)
                                   AS succeeded,
                               COALESCE(AVG(dep.duration_mins), 0) AS avg_minutes
                          FROM ticket_stage_transitions dep
                          JOIN tickets t   ON t.id = dep.ticket_id
                          JOIN projects p  ON p.id = t.project_id
                          JOIN workflow_stages ws
                                 ON ws.template_id = t.workflow_template_id
                                AND ws.stage_code  = dep.to_stage
                          LEFT JOIN ticket_stage_transitions nxt
                                 ON nxt.ticket_id = dep.ticket_id
                                AND nxt.cycle_no  = dep.cycle_no
                                AND nxt.seq_no    = dep.seq_no + 1
                         WHERE ws.owner_role = 'DEPLOYMENT'
                           AND dep.exited_at IS NOT NULL
                           AND dep.entered_at >= :fromTs
                           AND dep.entered_at < :toExclusiveTs
                           AND (:unscoped = 1 OR t.project_id IN (:projectIds))
                           AND (:ownWork = 0 OR t.assigned_to = :userId)
                      GROUP BY week_start, p.name
                      ORDER BY week_start, p.name
                        """)
                .param("fromTs", from.atStartOfDay())
                .param("toExclusiveTs", to.plusDays(1).atStartOfDay())
                .param("unscoped", projectIds.isEmpty() ? 1 : 0)
                .param("projectIds", projectIds.isEmpty() ? List.of(-1L) : projectIds)
                .param("ownWork", ownWork ? 1 : 0)
                .param("userId", userId)
                .query((rs, n) -> new DeploymentRow(
                        // A-067's defect: rs.getDate(..).toLocalDate() converts
                        // through the JVM default zone, so a stored date read on
                        // an IST machine against a UTC database came back a day
                        // early. getObject(.., LocalDate.class) does not.
                        rs.getObject("week_start", LocalDate.class),
                        rs.getString("project_name"),
                        rs.getLong("deployments"), rs.getLong("succeeded"),
                        rs.getLong("rolled_back"), rs.getBigDecimal("avg_minutes")))
                .list();
    }

    /**
     * A-068 report 15 · Resource Contribution — §7.8's "the §4A.4 per-resource-
     * per-stage roll-up across any ticket set".
     *
     * <h2>Read from the effort logs alone, and that is what makes it work</h2>
     *
     * <p>{@code JourneyRepository.perResource} — C-058's roll-up, the one §4A.4
     * describes — groups by {@code ticket_effort_logs.user_id} and nothing else.
     * Its own sibling {@code hops} joins the transitions, but the per-resource
     * half does not need to, and {@code AssignService}'s javadoc explains why in
     * as many words: {@code ticket_effort_logs.user_id} is stamped from
     * <em>whoever logged the hours</em>, never from {@code tickets.assigned_to},
     * so the roll-up is already correct across reassignment without consulting a
     * hop at all.
     *
     * <p>The effort log carries {@code stage_code}, {@code iteration_no} and
     * {@code cycle_no} itself. So the per-resource-per-stage roll-up is
     * answerable from one append-only table that <b>real production code
     * writes</b> — {@code EffortLogService.append} and
     * {@code QuickUpdateService.appendEffort} — which is what separates this
     * report from its two neighbours above.
     *
     * <h2>Corrections are summed, not filtered out</h2>
     *
     * <p>{@code is_correction} rows may carry negative hours by design
     * ({@code ck_effort_hours} relaxes the positive check for exactly them), and
     * a correction is an accounting reversal. Summing everything is what applies
     * it; excluding corrections would report the hours somebody already withdrew
     * and would disagree with the ticket's own roll-up on the detail page.
     *
     * <p>Tickets are counted {@code DISTINCT}, because a person logging six
     * entries against one ticket has contributed to one ticket.
     */
    List<ContributionRow> resourceContribution(LocalDate from, LocalDate to, List<Long> projectIds,
                                               boolean ownWork, long userId, Long resourceId) {
        return jdbc.sql("""
                        SELECT u.full_name AS full_name,
                               p.name      AS project_name,
                               COALESCE(e.stage_code, '(no stage)') AS stage_code,
                               SUM(e.hours)                   AS hours,
                               COUNT(DISTINCT e.ticket_id)    AS tickets,
                               COUNT(*)                       AS entries
                          FROM ticket_effort_logs e
                          JOIN users u    ON u.id = e.user_id
                          JOIN tickets t  ON t.id = e.ticket_id
                          JOIN projects p ON p.id = t.project_id
                         WHERE e.work_date >= :from
                           AND e.work_date <= :to
                           AND (:unscoped = 1 OR t.project_id IN (:projectIds))
                           AND (:ownWork = 0 OR e.user_id = :userId)
                           AND (:resourceId IS NULL OR e.user_id = :resourceId)
                      GROUP BY u.full_name, p.name, e.stage_code
                      ORDER BY hours DESC, u.full_name
                        """)
                .param("from", from)
                .param("to", to)
                .param("unscoped", projectIds.isEmpty() ? 1 : 0)
                .param("projectIds", projectIds.isEmpty() ? List.of(-1L) : projectIds)
                .param("ownWork", ownWork ? 1 : 0)
                .param("userId", userId)
                .param("resourceId", resourceId)
                .query((rs, n) -> new ContributionRow(
                        rs.getString("full_name"), rs.getString("project_name"),
                        rs.getString("stage_code"), rs.getBigDecimal("hours"),
                        rs.getLong("tickets"), rs.getLong("entries")))
                .list();
    }

    /**
     * @param fromStage where the work stood when it was sent back. Nullable
     *                  because the column is — only a first hop has no
     *                  {@code from_stage}, and a first hop cannot be a backward
     *                  move, so in practice it is always present.
     */
    record ReworkRow(String fullName, String projectName, String fromStage, String toStage,
                     long bounces, long ticketsAffected) {
    }

    record FirstTimeRightRow(String projectName, long closed, long firstTimeRight) {
    }

    /**
     * @param succeeded  left the deployment stage by anything other than
     *                   {@code DEPLOY_FAILED}, including a visit whose next hop
     *                   has not been written — a sealed visit with no successor
     *                   is a ticket that left and closed, which shipped.
     * @param avgMinutes working minutes, from {@code duration_mins}. Zero rather
     *                   than null when nothing qualified, since the row only
     *                   exists when at least one deployment did.
     */
    record DeploymentRow(LocalDate weekStart, String projectName, long deployments,
                         long succeeded, long rolledBack, java.math.BigDecimal avgMinutes) {
    }

    record ContributionRow(String fullName, String projectName, String stageCode,
                           java.math.BigDecimal hours, long tickets, long entries) {
    }
}
