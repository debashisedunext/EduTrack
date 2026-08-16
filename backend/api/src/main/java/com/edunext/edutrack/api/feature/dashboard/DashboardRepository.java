package com.edunext.edutrack.api.feature.dashboard;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * A-054 · reads A-050's summary tables. Never {@code tickets}.
 *
 * <h2>The rule this class exists to keep</h2>
 *
 * <p>CLAUDE.md: <b>never a live {@code COUNT(*)} for dashboards</b> — read the
 * pre-aggregated tables. A-050 built them and A-051 fills them every five
 * minutes precisely so this class has something to read. Every query below
 * touches {@code daily_ticket_stats} or {@code resource_daily_stats} and
 * nothing else; a join to {@code tickets} here would put the timeout back at
 * A-073's 50,000-row target while still passing every test at fixture scale.
 *
 * <h2>Two tables, because two questions</h2>
 *
 * <p>{@code daily_ticket_stats} is keyed {@code (stat_date, project_id)} and
 * {@code resource_daily_stats} {@code (stat_date, user_id)}. That is not a
 * convenience — it decides which table can answer a given caller at all. A
 * Developer's dashboard asks "what is assigned to me", which a project-keyed
 * table cannot express however it is filtered. See {@link DashboardService}.
 *
 * <h2>Flow sums, stock does not</h2>
 *
 * <p>{@code created}, {@code closed} and {@code reopened} are flow: summing a
 * date range is meaningful. {@code open_total} and the rest are stock — what
 * was true at the end of a day — and summing a week of "how many were open"
 * is nonsense. Stock therefore reads the <em>latest</em> row in range, never a
 * {@code SUM}. A-050's own migration header names this distinction; getting it
 * wrong produces a number seven times too large that still looks plausible.
 */
@Repository
class DashboardRepository {

    private final JdbcClient jdbc;

    DashboardRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Flow totals over the window, summed across whichever projects are in scope. */
    record Flow(long created, long closed, long reopened) {
    }

    /** Stock as at the last summarised day in the window. */
    record Stock(long openTotal, long openCritical, long openDelayed, long openReopened) {
    }

    /**
     * @param projectIds empty means unrestricted — Admin. A non-empty list is
     *                   the caller's scope and is applied here rather than
     *                   trusted from the request.
     */
    Flow projectFlow(LocalDate from, LocalDate to, List<Long> projectIds, Long projectFilter) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(created), 0)  AS created,
                               COALESCE(SUM(closed), 0)   AS closed,
                               COALESCE(SUM(reopened), 0) AS reopened
                          FROM daily_ticket_stats
                         WHERE stat_date BETWEEN :from AND :to
                           AND (:unscoped = 1 OR project_id IN (:projectIds))
                           AND (:projectFilter IS NULL OR project_id = :projectFilter)
                        """)
                .param("from", from)
                .param("to", to)
                .param("unscoped", projectIds.isEmpty() ? 1 : 0)
                .param("projectIds", projectIds.isEmpty() ? List.of(-1L) : projectIds)
                .param("projectFilter", projectFilter)
                .query((rs, n) -> new Flow(rs.getLong("created"), rs.getLong("closed"), rs.getLong("reopened")))
                .single();
    }

    /**
     * Stock at the most recent summarised day at or before {@code to}.
     *
     * <p>Reading the latest day rather than {@code to} itself matters when the
     * range ends today and A-051 has not run since midnight, or when it ends in
     * the future: asking for a day with no row would answer zero, and zero open
     * tickets is a very different statement from "not computed yet".
     */
    Optional<Stock> projectStock(LocalDate from, LocalDate to, List<Long> projectIds, Long projectFilter) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(open_total), 0)    AS open_total,
                               COALESCE(SUM(open_critical), 0) AS open_critical,
                               COALESCE(SUM(open_delayed), 0)  AS open_delayed,
                               COALESCE(SUM(open_reopened), 0) AS open_reopened
                          FROM daily_ticket_stats
                         WHERE stat_date = (
                                   SELECT MAX(stat_date) FROM daily_ticket_stats
                                    WHERE stat_date BETWEEN :from AND :to
                                      AND (:unscoped = 1 OR project_id IN (:projectIds))
                                      AND (:projectFilter IS NULL OR project_id = :projectFilter))
                           AND (:unscoped = 1 OR project_id IN (:projectIds))
                           AND (:projectFilter IS NULL OR project_id = :projectFilter)
                        """)
                .param("from", from)
                .param("to", to)
                .param("unscoped", projectIds.isEmpty() ? 1 : 0)
                .param("projectIds", projectIds.isEmpty() ? List.of(-1L) : projectIds)
                .param("projectFilter", projectFilter)
                .query((rs, n) -> new Stock(rs.getLong("open_total"), rs.getLong("open_critical"),
                        rs.getLong("open_delayed"), rs.getLong("open_reopened")))
                .optional();
    }

    /**
     * One row per day in the window — A-055's sparklines.
     *
     * <p>Days with no summary row are absent rather than zero, and the service
     * fills the gaps. That distinction is the point: a project with no row for
     * Sunday has not had "zero open tickets since Friday", it has not been
     * computed for Sunday, and a sparkline that dips to the axis every weekend
     * is a chart that lies quietly.
     */
    record Day(LocalDate day, long created, long closed, long openTotal,
               long openCritical, long openDelayed, long openReopened) {
    }

    List<Day> projectSeries(LocalDate from, LocalDate to, List<Long> projectIds, Long projectFilter) {
        return jdbc.sql("""
                        SELECT stat_date,
                               SUM(created)       AS created,
                               SUM(closed)        AS closed,
                               SUM(open_total)    AS open_total,
                               SUM(open_critical) AS open_critical,
                               SUM(open_delayed)  AS open_delayed,
                               SUM(open_reopened) AS open_reopened
                          FROM daily_ticket_stats
                         WHERE stat_date BETWEEN :from AND :to
                           AND (:unscoped = 1 OR project_id IN (:projectIds))
                           AND (:projectFilter IS NULL OR project_id = :projectFilter)
                         GROUP BY stat_date
                         ORDER BY stat_date
                        """)
                .param("from", from)
                .param("to", to)
                .param("unscoped", projectIds.isEmpty() ? 1 : 0)
                .param("projectIds", projectIds.isEmpty() ? List.of(-1L) : projectIds)
                .param("projectFilter", projectFilter)
                .query((rs, n) -> new Day(
                        rs.getObject("stat_date", LocalDate.class),
                        rs.getLong("created"), rs.getLong("closed"), rs.getLong("open_total"),
                        rs.getLong("open_critical"), rs.getLong("open_delayed"),
                        rs.getLong("open_reopened")))
                .list();
    }

    /** The same series for one person. Created and reopened are not theirs to own — see {@link #resourceFlow}. */
    List<Day> resourceSeries(LocalDate from, LocalDate to, long userId) {
        return jdbc.sql("""
                        SELECT stat_date, closed, assigned_open, assigned_critical, assigned_delayed
                          FROM resource_daily_stats
                         WHERE stat_date BETWEEN :from AND :to AND user_id = :userId
                         ORDER BY stat_date
                        """)
                .param("from", from).param("to", to).param("userId", userId)
                .query((rs, n) -> new Day(
                        rs.getObject("stat_date", LocalDate.class),
                        0, rs.getLong("closed"), rs.getLong("assigned_open"),
                        rs.getLong("assigned_critical"), rs.getLong("assigned_delayed"), 0))
                .list();
    }

    /** The same two questions for one person, from the resource-keyed table. */
    Flow resourceFlow(LocalDate from, LocalDate to, long userId) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(closed), 0) AS closed
                          FROM resource_daily_stats
                         WHERE stat_date BETWEEN :from AND :to AND user_id = :userId
                        """)
                .param("from", from).param("to", to).param("userId", userId)
                // created and reopened are not attributable to a resource: a
                // ticket is raised by a reporter and reopened by a manager, and
                // neither is the assignee whose dashboard this is. Reported as
                // zero rather than borrowed from the project table, which would
                // show a Developer their whole project's intake.
                .query((rs, n) -> new Flow(0, rs.getLong("closed"), 0))
                .single();
    }

    Optional<Stock> resourceStock(LocalDate from, LocalDate to, long userId) {
        return jdbc.sql("""
                        SELECT assigned_open, assigned_critical, assigned_delayed
                          FROM resource_daily_stats
                         WHERE user_id = :userId
                           AND stat_date = (
                                   SELECT MAX(stat_date) FROM resource_daily_stats
                                    WHERE stat_date BETWEEN :from AND :to AND user_id = :userId)
                        """)
                .param("from", from).param("to", to).param("userId", userId)
                // assigned_reopened has no column: reopen is a ticket-level
                // fact and resource_daily_stats does not carry it. Zero here is
                // honest; inventing it from the project table would not be.
                .query((rs, n) -> new Stock(rs.getLong("assigned_open"), rs.getLong("assigned_critical"),
                        rs.getLong("assigned_delayed"), 0))
                .optional();
    }

    /**
     * The freshness stamp A-050 declared so a stale row is visible rather than
     * merely wrong.
     *
     * <p>Mapped through {@code getTimestamp} rather than asked for as an
     * {@code Instant}. PLAN.md §3.1 stores time as {@code DATETIME(6)} — never
     * {@code TIMESTAMP} — precisely so MySQL performs no session-timezone
     * conversion, and the cost of that choice is that the column carries no
     * offset for the driver to convert <em>from</em>: asking for an
     * {@code Instant} directly fails with a type mismatch. The connection is
     * pinned to UTC ({@code connectionTimeZone=UTC}), which is where the offset
     * actually comes from, and every one of these columns is written in UTC.
     */
    Optional<Instant> computedAt(LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        SELECT MAX(computed_at) AS computed_at FROM daily_ticket_stats
                         WHERE stat_date BETWEEN :from AND :to
                        """)
                .param("from", from).param("to", to)
                .query((rs, n) -> {
                    Timestamp stamp = rs.getTimestamp("computed_at");
                    return stamp == null ? null : stamp.toInstant();
                })
                .optional();
    }
}
