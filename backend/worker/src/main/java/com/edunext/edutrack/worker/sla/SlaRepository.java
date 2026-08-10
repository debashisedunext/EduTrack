package com.edunext.edutrack.worker.sla;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * D-020 · finding tickets that have run past their Planned Close Date.
 */
@Repository
class SlaRepository {

    /**
     * The scan, and the reason A-009 exists.
     *
     * <p>{@code pcd_open} is the STORED generated column standing in for
     * PostgreSQL's partial index: it holds {@code planned_close_date} while the
     * ticket is open and NULL once it closes. So this is a range scan over
     * {@code ix_tickets_pcd_open} covering only open, overdue tickets —
     * O(breaches) rather than O(all tickets), every fifteen minutes, forever.
     * Writing {@code WHERE planned_close_date < ? AND actual_close_date IS
     * NULL} instead would look identical and read the whole table.
     *
     * <p>{@code is_delayed = 0} is what makes the scan self-limiting: a ticket
     * is escalated once, not every fifteen minutes until somebody closes it.
     */
    private static final String BREACHED = """
            SELECT t.id, t.ticket_code, t.title, t.level, t.task_type_id AS taskTypeId,
                   t.pcd_open AS plannedCloseDate,
                   t.project_id AS projectId, p.name AS projectName,
                   t.assigned_to AS assignedTo, p.manager_id AS projectManagerId,
                   u.reporting_manager_id AS reportingManagerId
              FROM tickets t
              JOIN projects p ON p.id = t.project_id
              LEFT JOIN users u ON u.id = t.assigned_to
             WHERE t.pcd_open < :now
               AND t.is_delayed = 0
             ORDER BY t.pcd_open
             LIMIT :limit
            """;

    /**
     * Claim and escalate in one statement.
     *
     * <p>{@code is_delayed = 0} in the WHERE as well as the SET, so this
     * returns 1 only for the caller that actually made the transition. ShedLock
     * already keeps two instances from scanning at once, but that lock can
     * expire under a long run — and "did I escalate this, or did somebody
     * else?" is the difference between one alert and two.
     *
     * <p><strong>{@code original_level} is deliberately untouched.</strong> The
     * schema comment calls it "never mutated": it is what makes "born critical"
     * distinguishable from "became critical" in reporting, which is exactly the
     * question an escalation makes interesting. D-028 owns the rest of that.
     */
    private static final String ESCALATE = """
            UPDATE tickets
               SET is_delayed = 1,
                   delayed_since = :now,
                   level = 'CRITICAL'
             WHERE id = :id
               AND is_delayed = 0
            """;

    /**
     * Real addresses, looked up rather than derived.
     *
     * <p>A scanner that composed {@code user-7@…} would put a plausible,
     * undeliverable address in {@code email_log} and every one of those rows
     * would read as an attempt that failed at the provider — turning D-033's
     * delivery proof into noise. An inactive user is skipped: their mail bounces
     * and their address may have been reassigned.
     */
    private static final String EMAILS = """
            SELECT id, email FROM users WHERE id IN (:ids) AND is_active = 1
            """;

    private final JdbcClient jdbc;

    SlaRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return user id → address, missing any that are inactive */
    java.util.Map<Long, String> emailsOf(java.util.Collection<Long> ids) {
        if (ids.isEmpty()) {
            // IN () is a syntax error, and a ticket with no recipients at all
            // is normal for an unassigned one on a project with no manager.
            return java.util.Map.of();
        }
        return jdbc.sql(EMAILS).param("ids", ids).query(
                (rs, n) -> java.util.Map.entry(rs.getLong("id"), rs.getString("email")))
                .list().stream()
                .collect(java.util.stream.Collectors.toMap(
                        java.util.Map.Entry::getKey, java.util.Map.Entry::getValue));
    }

    List<BreachedTicket> breached(Instant now, int limit) {
        return jdbc.sql(BREACHED)
                .param("now", Timestamp.from(now))
                .param("limit", limit)
                .query(BreachedTicket.class)
                .list();
    }

    /** @return true if this call was the one that escalated it */
    boolean escalate(long ticketId, Instant now) {
        return jdbc.sql(ESCALATE)
                .param("id", ticketId)
                .param("now", Timestamp.from(now))
                .update() == 1;
    }

    /**
     * @param assignedTo         null for an unassigned ticket, which D-026
     *                           treats as its own problem
     * @param reportingManagerId the assignee's manager, null when unassigned or
     *                           when the assignee is at the top of the tree
     */
    record BreachedTicket(
            long id,
            String ticketCode,
            String title,
            String level,
            Integer taskTypeId,
            Timestamp plannedCloseDate,
            long projectId,
            String projectName,
            Long assignedTo,
            Long projectManagerId,
            Long reportingManagerId) {
    }
}
