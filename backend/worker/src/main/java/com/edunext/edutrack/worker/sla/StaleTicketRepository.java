package com.edunext.edutrack.worker.sla;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * D-022 · open tickets and when somebody last did anything to them.
 */
@Repository
class StaleTicketRepository {

    /**
     * <h2>Why this does not use {@code tickets.updated_at}</h2>
     *
     * <p>It would be one column instead of four correlated subqueries, and it
     * would be wrong. {@code updated_at} moves on <em>any</em> write to the
     * row — including the SLA scanner's own {@code is_delayed} stamp. A ticket
     * that breached would therefore look freshly updated, and D-020 escalating
     * it would be the very thing that stopped D-022 nudging anybody about it.
     * The two engines would quietly cancel each other, and the tickets it
     * happened to would be exactly the ones already in trouble.
     *
     * <p>So activity means <strong>a person did something</strong>: a comment,
     * an effort log, a stage hop. {@code date_reported} is the floor, so a
     * ticket nobody has touched since it was raised still ages from the day it
     * arrived rather than reading as having no activity at all.
     *
     * <p>Correlated subqueries rather than joins because each is a bounded
     * index lookup on {@code ticket_id} returning one value, and joining four
     * child tables to a filtered parent multiplies rows before aggregating
     * them back down.
     */
    private static final String CANDIDATES = """
            SELECT t.id, t.ticket_code AS ticketCode, t.title,
                   t.project_id AS projectId, t.assigned_to AS assignedTo,
                   u.reporting_manager_id AS reportingManagerId,
                   n.nudged_at AS lastNudgedAt,
                   GREATEST(
                     t.date_reported,
                     COALESCE((SELECT MAX(c.created_at) FROM ticket_comments c
                                WHERE c.ticket_id = t.id), t.date_reported),
                     COALESCE((SELECT MAX(e.created_at) FROM ticket_effort_logs e
                                WHERE e.ticket_id = t.id), t.date_reported),
                     COALESCE((SELECT MAX(s.entered_at) FROM ticket_stage_transitions s
                                WHERE s.ticket_id = t.id), t.date_reported)
                   ) AS lastActivityAt
              FROM tickets t
              JOIN users u ON u.id = t.assigned_to
              LEFT JOIN stale_ticket_nudges n ON n.ticket_id = t.id
             WHERE t.actual_close_date IS NULL
               AND t.assigned_to IS NOT NULL
             ORDER BY t.id
             LIMIT :limit
            """;

    /**
     * Claim the nudge.
     *
     * <p>{@code ON DUPLICATE KEY UPDATE} with the freshness test inside the
     * {@code IF} rather than a read-then-write: two instances evaluating the
     * same ticket in the same pass would otherwise both decide to nudge. MySQL
     * reports 1 for an insert and 2 for an update that changed something, so a
     * non-zero count is "this call won"; a stale-nudge attempt that loses the
     * race changes nothing and returns 0.
     */
    private static final String CLAIM = """
            INSERT INTO stale_ticket_nudges (ticket_id, nudged_at, last_activity_at)
            VALUES (:ticketId, :now, :lastActivityAt)
            ON DUPLICATE KEY UPDATE
                nudged_at = IF(nudged_at <= :nudgeableIfBefore, :now, nudged_at),
                last_activity_at = IF(nudged_at <= :nudgeableIfBefore,
                                      :lastActivityAt, last_activity_at)
            """;

    private static final String EMAILS = """
            SELECT id, email FROM users WHERE id IN (:ids) AND is_active = 1
            """;

    private final JdbcClient jdbc;

    StaleTicketRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<OpenTicket> candidates(int limit) {
        return jdbc.sql(CANDIDATES).param("limit", limit).query(OpenTicket.class).list();
    }

    /**
     * @param nudgeableIfBefore a previous nudge at or before this instant may
     *                          be replaced; a later one means somebody has
     *                          already been told recently enough
     * @return true if this call is the one that nudges
     */
    boolean claim(long ticketId, Instant now, Instant lastActivity, Instant nudgeableIfBefore) {
        return jdbc.sql(CLAIM)
                .param("ticketId", ticketId)
                .param("now", Timestamp.from(now))
                .param("lastActivityAt", Timestamp.from(lastActivity))
                .param("nudgeableIfBefore", Timestamp.from(nudgeableIfBefore))
                .update() > 0;
    }

    java.util.Map<Long, String> emailsOf(java.util.Collection<Long> ids) {
        if (ids.isEmpty()) {
            return java.util.Map.of();
        }
        return jdbc.sql(EMAILS).param("ids", ids).query(
                        (rs, n) -> java.util.Map.entry(rs.getLong("id"), rs.getString("email")))
                .list().stream()
                .collect(java.util.stream.Collectors.toMap(
                        java.util.Map.Entry::getKey, java.util.Map.Entry::getValue));
    }

    /** @param lastNudgedAt null when this ticket has never been nudged */
    record OpenTicket(
            long id,
            String ticketCode,
            String title,
            long projectId,
            long assignedTo,
            Long reportingManagerId,
            Timestamp lastNudgedAt,
            Timestamp lastActivityAt) {
    }
}
