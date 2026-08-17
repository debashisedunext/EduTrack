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

    record DayRow(LocalDate date, long created, long closed, long reopened, long openTotal) {
    }
}
