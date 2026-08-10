package com.edunext.edutrack.worker.sla;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * D-021 · open tickets that might be approaching their Planned Close Date.
 */
@Repository
class PreBreachRepository {

    /**
     * Candidates, deliberately not "tickets at 80%".
     *
     * <p>The ratio is working hours elapsed over working hours committed, and
     * <strong>neither side can be bounded cheaply in SQL</strong>. D-023 could
     * prefilter because working hours never exceed wall-clock hours, which puts
     * a one-sided bound on a single duration. Here both numerator and
     * denominator are working hours, and the wall-clock ratio is not a bound in
     * either direction: a window whose weekend falls in the second half is
     * further along in working terms than the calendar suggests, and one whose
     * weekend falls early is behind it.
     *
     * <p>So the filter is only what is certainly irrelevant — closed tickets,
     * unassigned ones (nobody to warn), tickets already past their date
     * (D-020's job, and warning somebody about a deadline that has gone is
     * worse than saying nothing), and cycles already warned. What survives is
     * the open assigned working set, which is bounded by how much work is in
     * flight rather than by table size.
     *
     * <p>{@code pcd_open} still earns its place: it is NULL for closed tickets,
     * so they leave the index range rather than being filtered out of a scan.
     */
    private static final String CANDIDATES = """
            SELECT t.id, t.ticket_code AS ticketCode, t.title, t.current_cycle_no AS cycleNo,
                   t.date_reported AS dateReported, t.pcd_open AS plannedCloseDate,
                   t.project_id AS projectId, t.assigned_to AS assignedTo
              FROM tickets t
              LEFT JOIN sla_prebreach_alerts a
                     ON a.ticket_id = t.id AND a.cycle_no = t.current_cycle_no
             WHERE t.pcd_open > :now
               AND t.assigned_to IS NOT NULL
               AND t.is_delayed = 0
               AND a.ticket_id IS NULL
             ORDER BY t.pcd_open
             LIMIT :limit
            """;

    /** The claim. {@code INSERT IGNORE} makes "who warns" a race nobody loses twice. */
    private static final String CLAIM = """
            INSERT IGNORE INTO sla_prebreach_alerts (ticket_id, cycle_no, elapsed_pct)
            VALUES (:ticketId, :cycleNo, :elapsedPct)
            """;

    private static final String EMAIL = """
            SELECT email FROM users WHERE id = :id AND is_active = 1
            """;

    private final JdbcClient jdbc;

    PreBreachRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<ApproachingTicket> candidates(Instant now, int limit) {
        return jdbc.sql(CANDIDATES)
                .param("now", Timestamp.from(now))
                .param("limit", limit)
                .query(ApproachingTicket.class)
                .list();
    }

    boolean claim(long ticketId, short cycleNo, BigDecimal elapsedPct) {
        return jdbc.sql(CLAIM)
                .param("ticketId", ticketId)
                .param("cycleNo", cycleNo)
                .param("elapsedPct", elapsedPct)
                .update() == 1;
    }

    /** Empty when the assignee has been deactivated since the ticket was given to them. */
    java.util.Optional<String> emailOf(long userId) {
        return jdbc.sql(EMAIL).param("id", userId).query(String.class).optional();
    }

    record ApproachingTicket(
            long id,
            String ticketCode,
            String title,
            short cycleNo,
            Timestamp dateReported,
            Timestamp plannedCloseDate,
            long projectId,
            long assignedTo) {
    }
}
