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
 * <p>Where a future report genuinely needs a fact the summary tables do not
 * carry — the audit log's chain verdict, the email delivery log's states —
 * that report reads its own source table, which is not {@code tickets} either.
 * The one thing no report may do is aggregate {@code tickets} live.
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
                        rs.getDate("stat_date").toLocalDate(),
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
                        rs.getDate("stat_date").toLocalDate(),
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
}
