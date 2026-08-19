package com.edunext.edutrack.api.feature.reports;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * B-063 · one week of one person's effort log, joined to what names it.
 *
 * <p>Deliberately one query and no aggregation. The pivot into a grid happens
 * in {@link TimesheetService}, over rows this returns in work-date order,
 * because the two totals the screen needs run in different directions — a day
 * spans rows and a row spans days — and a {@code GROUP BY} answering one of
 * them would still leave the other to Java. A week of one person's entries is a
 * bounded read on {@code ix_effort_user_date}, not the cross-ticket scan
 * CLAUDE.md's {@code COUNT(*)} rule is aimed at.
 *
 * <p><b>Corrections are included, signed.</b> {@code ticket_effort_logs} is
 * append-only, so a fixed mistake is a negative compensating row rather than an
 * edit. Filtering them out would make this screen disagree with the ticket's
 * own effort tab about the same hours, which is worse than showing a minus.
 */
@Repository
class TimesheetRepository {

    private final JdbcClient jdbc;

    TimesheetRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Every entry {@code userId} logged with a work date in {@code [from, to]}.
     *
     * <p>{@code work_date} is a {@code DATE}, not a {@code DATETIME}, so
     * {@code BETWEEN} is inclusive at both ends without the exclusive-upper
     * dance {@code Profile360Repository.headline} needs for close dates. The
     * hours were logged against a date somebody typed, and it never acquires a
     * time of day — that is the column's own javadoc, and it is why a timesheet
     * can be built without a timezone entering the query at all.
     *
     * <p>The stage name is resolved through the <em>ticket's own</em> workflow
     * template rather than from a global list: two templates may name
     * {@code QA} differently, and a week spanning both would otherwise print
     * whichever one the join happened to find. A {@code LEFT JOIN} throughout —
     * an entry predating the ribbon has no {@code stage_code}, and a ticket
     * whose template has since dropped the stage still has hours worth showing.
     */
    List<Entry> entries(long userId, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        SELECT e.ticket_id, t.ticket_code, t.title,
                               p.id AS project_id, p.project_code, p.name AS project_name,
                               e.stage_code, ws.display_name AS stage_name,
                               e.iteration_no, e.cycle_no,
                               e.work_date, e.hours, e.is_correction
                          FROM ticket_effort_logs e
                          JOIN tickets t  ON t.id = e.ticket_id
                          JOIN projects p ON p.id = t.project_id
                     LEFT JOIN workflow_stages ws
                                ON ws.template_id = t.workflow_template_id
                               AND ws.stage_code  = e.stage_code
                         WHERE e.user_id = :userId
                           AND e.work_date BETWEEN :from AND :to
                      ORDER BY e.work_date, e.id
                        """)
                .param("userId", userId)
                .param("from", from)
                .param("to", to)
                .query((rs, n) -> new Entry(
                        rs.getLong("ticket_id"),
                        rs.getString("ticket_code"),
                        rs.getString("title"),
                        rs.getLong("project_id"),
                        rs.getString("project_code"),
                        rs.getString("project_name"),
                        rs.getString("stage_code"),
                        rs.getString("stage_name"),
                        rs.getInt("iteration_no"),
                        rs.getInt("cycle_no"),
                        rs.getObject("work_date", LocalDate.class),
                        rs.getBigDecimal("hours"),
                        rs.getBoolean("is_correction")))
                .list();
    }

    record Entry(long ticketId, String ticketCode, String ticketTitle,
                 long projectId, String projectCode, String projectName,
                 String stageCode, String stageName, int iterationNo, int cycleNo,
                 LocalDate workDate, BigDecimal hours, boolean correction) {
    }
}
