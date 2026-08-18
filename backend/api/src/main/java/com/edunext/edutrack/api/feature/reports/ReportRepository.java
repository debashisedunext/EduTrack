package com.edunext.edutrack.api.feature.reports;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * A-063 · what the report runners read. Reads A-050's summary tables, never
 * {@code tickets}.
 *
 * <p>Same rule as the dashboard, for a sharper reason. CLAUDE.md forbids a live
 * {@code COUNT(*)} behind a dashboard; a report is the same query over a wider
 * window and usually a larger result, so if anything the prohibition binds
 * harder here. A-073's target is 50,000 tickets, and a date-wise report over a
 * year that counted rows would be the slowest page in the product.
 *
 * <p>Where a report needs a fact these tables do not carry, it reads its own
 * source table instead — {@link TicketReportRepository} for the ticket-level
 * ones, and the audit log or the delivery log for reports still to come.
 *
 * <p><b>A-066 corrected this class's original claim</b> that "the one thing no
 * report may do is aggregate {@code tickets} live". That over-stated the rule.
 * CLAUDE.md and PLAN.md §480 scope it to <em>dashboards</em>, for a reason
 * specific to them: a dashboard loads unbidden on every login and must paint in
 * 1.5 seconds. Five of §7.8's first six reports cannot be answered from a
 * summary table at any grain, and read {@code tickets} through
 * {@link TicketReportRepository} — bounded by the requested range and scoped in
 * SQL. The dashboard rule itself is unchanged and still absolute.
 */
@Repository
class ReportRepository {

    private final JdbcClient jdbc;

    ReportRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One row per day in the window: created, closed, reopened, and the stock
     * of open tickets at the end of that day.
     *
     * <p><b>Flow is summed across projects, stock is summed across projects but
     * never across days.</b> The grouping does both correctly by accident of
     * shape — one row per {@code stat_date} — and it is worth naming because
     * the mistake is invisible: summing {@code open_total} over a week produces
     * a backlog roughly seven times too large that still moves plausibly.
     *
     * <p>Days with no row are absent rather than zero-filled. A project created
     * last month has no rows before it existed, and drawing zeros there would
     * assert a backlog of nothing rather than an absence of data. The client
     * plots what it is given.
     */
    List<DayRow> dailyFlow(LocalDate from, LocalDate to, List<Long> projectIds) {
        return jdbc.sql("""
                        SELECT stat_date,
                               COALESCE(SUM(created), 0)    AS created,
                               COALESCE(SUM(closed), 0)     AS closed,
                               COALESCE(SUM(reopened), 0)   AS reopened,
                               COALESCE(SUM(open_total), 0) AS open_total
                          FROM daily_ticket_stats
                         WHERE stat_date BETWEEN :from AND :to
                           AND (:unscoped = 1 OR project_id IN (:projectIds))
                      GROUP BY stat_date
                      ORDER BY stat_date
                        """)
                .param("from", from)
                .param("to", to)
                .param("unscoped", projectIds.isEmpty() ? 1 : 0)
                // A list parameter cannot be empty in SQL, and -1 matches no
                // project. Only reached when unscoped = 1 has already short-
                // circuited the clause, but it must still bind.
                .param("projectIds", projectIds.isEmpty() ? List.of(-1L) : projectIds)
                .query((rs, n) -> new DayRow(
                        rs.getObject("stat_date", LocalDate.class),
                        rs.getLong("created"),
                        rs.getLong("closed"),
                        rs.getLong("reopened"),
                        rs.getLong("open_total")))
                .list();
    }

    /**
     * When A-051 last recomputed anything in the window.
     *
     * <p>Feeds both the {@code ETag} and the staleness the viewer shows. Empty
     * when the window has no summarised days at all, which is a real state on a
     * fresh database and must not be reported as "computed just now".
     */
    Optional<Instant> computedAt(LocalDate from, LocalDate to, List<Long> projectIds) {
        return jdbc.sql("""
                        SELECT MAX(computed_at) AS computed_at
                          FROM daily_ticket_stats
                         WHERE stat_date BETWEEN :from AND :to
                           AND (:unscoped = 1 OR project_id IN (:projectIds))
                        """)
                .param("from", from)
                .param("to", to)
                .param("unscoped", projectIds.isEmpty() ? 1 : 0)
                .param("projectIds", projectIds.isEmpty() ? List.of(-1L) : projectIds)
                .query((rs, n) -> {
                    Timestamp at = rs.getTimestamp("computed_at");
                    return at == null ? null : at.toInstant();
                })
                .optional()
                .flatMap(Optional::ofNullable);
    }

    /**
     * The same question asked of one person, from the resource-keyed table.
     *
     * <h2>Why this exists rather than filtering the query above</h2>
     *
     * <p>It cannot be done by filtering. {@code daily_ticket_stats} is keyed
     * {@code (stat_date, project_id)}, so narrowing it to a Developer's projects
     * answers "what your projects did" — which is not "what you did", and was
     * shown under a label saying it was. A project-keyed table cannot express
     * <em>assigned to me</em> however it is filtered; that is A-050's grain and
     * A-062 named the same limit for the dashboard's widgets.
     *
     * <h2>Three columns fewer, and the missing ones are not an omission</h2>
     *
     * <p>There is no {@code created} and no {@code reopened} here, because a
     * ticket is raised by a reporter and reopened by a manager and neither is
     * the assignee — the figures do not exist per person and cannot be made to.
     * Net backlog is a project's stock for the same reason. What is recorded per
     * person is what somebody <b>closed</b>, and what they are <b>holding</b>:
     * open, and of those, delayed.
     *
     * <p>So a delivery role gets a genuinely different report under the same
     * key, and the columns say which one it is. That is honest in a way both
     * alternatives are not: sharing the columns would mean inventing three, and
     * withholding the report entirely would deny a Developer a question their
     * own data can answer.
     */
    List<ResourceDayRow> dailyResourceFlow(LocalDate from, LocalDate to, long userId) {
        return jdbc.sql("""
                        SELECT stat_date,
                               closed,
                               effort_hours,
                               assigned_open,
                               assigned_delayed
                          FROM resource_daily_stats
                         WHERE stat_date BETWEEN :from AND :to
                           AND user_id = :userId
                      ORDER BY stat_date
                        """)
                .param("from", from)
                .param("to", to)
                .param("userId", userId)
                .query((rs, n) -> new ResourceDayRow(
                        rs.getObject("stat_date", LocalDate.class),
                        rs.getLong("closed"),
                        rs.getBigDecimal("effort_hours"),
                        rs.getLong("assigned_open"),
                        rs.getLong("assigned_delayed")))
                .list();
    }

    /**
     * When the resource rows in the window were last recomputed.
     *
     * <p>A separate query from {@link #computedAt} because the two tables are
     * refreshed in the same pass but are not the same rows: a person with no
     * activity earns no row, so a window can hold project rows and no resource
     * rows at all. Feeding the project table's timestamp into a resource
     * report's ETag would pin an empty answer behind a validator that keeps
     * moving.
     */
    Optional<Instant> resourceComputedAt(LocalDate from, LocalDate to, long userId) {
        return jdbc.sql("""
                        SELECT MAX(computed_at) AS computed_at
                          FROM resource_daily_stats
                         WHERE stat_date BETWEEN :from AND :to
                           AND user_id = :userId
                        """)
                .param("from", from)
                .param("to", to)
                .param("userId", userId)
                .query((rs, n) -> {
                    Timestamp at = rs.getTimestamp("computed_at");
                    return at == null ? null : at.toInstant();
                })
                .optional()
                .flatMap(Optional::ofNullable);
    }

    record DayRow(LocalDate date, long created, long closed, long reopened, long openTotal) {
    }

    record ResourceDayRow(LocalDate date, long closed, java.math.BigDecimal effortHours,
                          long assignedOpen, long assignedDelayed) {
    }
    // ── A-067 · reports 8, 9 and 10, all from the summary tables ─────────────

    /**
     * A-067 report 8 · project health, one row per project.
     *
     * <p>Every column here is already recorded per project per day by A-050, so
     * this is the shape those tables were built for and needs no {@code tickets}.
     *
     * <p><b>Flow is summed over the window; stock is read at its last day.</b>
     * Created and closed accumulate — a month's total is the sum of its days.
     * Open, critical and delayed do not: summing "how many were open" across
     * thirty days gives a backlog thirty times too large that still moves
     * plausibly, which is the mistake A-050's own migration header warns about.
     * So the stock columns come from a subquery pinned to the latest summarised
     * day in range, and the flow columns from a straight SUM.
     */
    List<ProjectHealthRow> projectHealth(LocalDate from, LocalDate to, List<Long> projectIds) {
        return jdbc.sql("""
                        SELECT p.name AS project_name,
                               COALESCE(SUM(d.created), 0)  AS created,
                               COALESCE(SUM(d.closed), 0)   AS closed,
                               COALESCE(SUM(d.reopened), 0) AS reopened,
                               (SELECT l.open_total FROM daily_ticket_stats l
                                 WHERE l.project_id = d.project_id
                                   AND l.stat_date BETWEEN :from AND :to
                              ORDER BY l.stat_date DESC LIMIT 1) AS open_total,
                               (SELECT l.open_critical FROM daily_ticket_stats l
                                 WHERE l.project_id = d.project_id
                                   AND l.stat_date BETWEEN :from AND :to
                              ORDER BY l.stat_date DESC LIMIT 1) AS open_critical,
                               (SELECT l.open_delayed FROM daily_ticket_stats l
                                 WHERE l.project_id = d.project_id
                                   AND l.stat_date BETWEEN :from AND :to
                              ORDER BY l.stat_date DESC LIMIT 1) AS open_delayed
                          FROM daily_ticket_stats d
                          JOIN projects p ON p.id = d.project_id
                         WHERE d.stat_date BETWEEN :from AND :to
                           AND (:unscoped = 1 OR d.project_id IN (:projectIds))
                      GROUP BY d.project_id, p.name
                      ORDER BY open_total DESC, p.name
                        """)
                .param("from", from)
                .param("to", to)
                .param("unscoped", projectIds.isEmpty() ? 1 : 0)
                .param("projectIds", projectIds.isEmpty() ? List.of(-1L) : projectIds)
                .query((rs, n) -> new ProjectHealthRow(
                        rs.getString("project_name"),
                        rs.getLong("created"), rs.getLong("closed"), rs.getLong("reopened"),
                        rs.getLong("open_total"), rs.getLong("open_critical"),
                        rs.getLong("open_delayed")))
                .list();
    }

    record ProjectHealthRow(String projectName, long created, long closed, long reopened,
                            long openTotal, long openCritical, long openDelayed) {
    }

    /**
     * A-067 report 9 · the aging profile, at the most recent summarised day.
     *
     * <p><b>A snapshot, not a range.</b> Aging buckets are stock, and "how long
     * has open work been open" has one answer — the current one. Summing a
     * month of daily bucket counts would count the same ticket thirty times and
     * produce a profile with no meaning, while still drawing a plausible chart.
     *
     * <p>The date range therefore chooses <em>which</em> snapshot rather than how
     * much to add up: the latest day at or before {@code to}. That also answers
     * on a database where the worker has not run since midnight, which asking
     * for {@code to} exactly would not.
     */
    List<AgingRow> aging(LocalDate from, LocalDate to, List<Long> projectIds) {
        return jdbc.sql("""
                        SELECT p.name AS project_name,
                               d.stat_date,
                               d.aging_0_2, d.aging_3_7, d.aging_8_30, d.aging_31_plus,
                               d.open_total
                          FROM daily_ticket_stats d
                          JOIN projects p ON p.id = d.project_id
                         WHERE d.stat_date = (
                                   SELECT MAX(x.stat_date) FROM daily_ticket_stats x
                                    WHERE x.project_id = d.project_id
                                      AND x.stat_date BETWEEN :from AND :to)
                           AND (:unscoped = 1 OR d.project_id IN (:projectIds))
                      ORDER BY d.aging_31_plus DESC, p.name
                        """)
                .param("from", from)
                .param("to", to)
                .param("unscoped", projectIds.isEmpty() ? 1 : 0)
                .param("projectIds", projectIds.isEmpty() ? List.of(-1L) : projectIds)
                .query((rs, n) -> new AgingRow(
                        rs.getString("project_name"),
                        rs.getObject("stat_date", LocalDate.class),
                        rs.getLong("aging_0_2"), rs.getLong("aging_3_7"),
                        rs.getLong("aging_8_30"), rs.getLong("aging_31_plus"),
                        rs.getLong("open_total")))
                .list();
    }

    record AgingRow(String projectName, LocalDate asOf, long bucket0to2, long bucket3to7,
                    long bucket8to30, long bucket31Plus, long openTotal) {
    }

    /**
     * A-067 report 10 · what each person is carrying, at the latest snapshot.
     *
     * <p>Stock again, for {@link #aging}'s reason: "how much is assigned to
     * Ravi" is a question about now. The capacity half — how many working hours
     * exist for that person over the window — is in no table and is computed by
     * the runner from B-023's calendar.
     *
     * <p>A PM is scoped by project <em>membership</em>, because
     * {@code resource_daily_stats} has no project column. A-051 recorded that
     * limitation and it still holds: somebody on three projects appears with
     * their whole load, not the part belonging to the asking PM.
     */
    List<WorkloadRow> workload(LocalDate from, LocalDate to, boolean ownWork, long userId,
                               List<Long> memberOfProjects) {
        return jdbc.sql("""
                        SELECT r.user_id,
                               u.full_name,
                               r.assigned_open,
                               r.assigned_critical,
                               r.assigned_delayed,
                               r.assigned_in_progress
                          FROM resource_daily_stats r
                          JOIN users u ON u.id = r.user_id
                         WHERE r.stat_date = (
                                   SELECT MAX(x.stat_date) FROM resource_daily_stats x
                                    WHERE x.user_id = r.user_id
                                      AND x.stat_date BETWEEN :from AND :to)
                           AND (:ownWork = 0 OR r.user_id = :userId)
                           AND (:unscoped = 1 OR EXISTS (
                                   SELECT 1 FROM project_members pm
                                    WHERE pm.user_id = r.user_id
                                      AND pm.project_id IN (:projectIds)))
                      ORDER BY r.assigned_open DESC, u.full_name
                        """)
                .param("from", from)
                .param("to", to)
                .param("ownWork", ownWork ? 1 : 0)
                .param("userId", userId)
                .param("unscoped", memberOfProjects.isEmpty() ? 1 : 0)
                .param("projectIds", memberOfProjects.isEmpty() ? List.of(-1L) : memberOfProjects)
                .query((rs, n) -> new WorkloadRow(
                        rs.getLong("user_id"), rs.getString("full_name"),
                        rs.getLong("assigned_open"), rs.getLong("assigned_critical"),
                        rs.getLong("assigned_delayed"), rs.getLong("assigned_in_progress")))
                .list();
    }

    record WorkloadRow(long userId, String fullName, long assignedOpen, long assignedCritical,
                       long assignedDelayed, long assignedInProgress) {
    }
}
