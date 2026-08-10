package com.edunext.edutrack.worker.sla;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * D-024 · tickets that have been late long enough to go up a second level.
 */
@Repository
class L2EscalationRepository {

    /**
     * Already breached, still open, and not yet escalated twice.
     *
     * <p>{@code is_delayed = 1} is the entry condition rather than a date
     * comparison: L2 follows L1, and D-020 sets that flag at the moment L1
     * fired. Reading the flag rather than recomputing "is it past its date"
     * means the two levels cannot disagree about whether the first one ever
     * happened.
     *
     * <p>The 48-hour test is <em>not</em> here. It is in working hours, so it
     * belongs where the calendar is. The wall-clock prefilter is sound for the
     * same reason D-023's is — working hours never exceed wall-clock hours, so
     * a ticket less than 48 wall-clock hours past its date cannot be 48 working
     * hours past it.
     *
     * <p>The manager chain is resolved in SQL because it is two hops and both
     * are indexed primary-key lookups: assignee → their reporting manager →
     * that manager's manager, which is who L2 means.
     */
    private static final String CANDIDATES = """
            SELECT t.id, t.ticket_code AS ticketCode, t.title, t.level,
                   t.task_type_id AS taskTypeId, t.project_id AS projectId,
                   t.pcd_open AS plannedCloseDate, t.assigned_to AS assignedTo,
                   rm.id AS reportingManagerId, rmm.id AS escalateToId
              FROM tickets t
              LEFT JOIN users a ON a.id = t.assigned_to
              LEFT JOIN users rm ON rm.id = a.reporting_manager_id
              LEFT JOIN users rmm ON rmm.id = rm.reporting_manager_id
              LEFT JOIN l2_escalations e ON e.ticket_id = t.id
             WHERE t.is_delayed = 1
               AND t.actual_close_date IS NULL
               AND e.ticket_id IS NULL
               AND t.pcd_open <= :latestPossibleBreach
             ORDER BY t.pcd_open
             LIMIT :limit
            """;

    private static final String CLAIM = """
            INSERT IGNORE INTO l2_escalations (ticket_id, escalated_to, overdue_hours)
            VALUES (:ticketId, :escalatedTo, :overdueHours)
            """;

    private static final String EMAIL = """
            SELECT email FROM users WHERE id = :id AND is_active = 1
            """;

    private final JdbcClient jdbc;

    L2EscalationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param latestPossibleBreach the newest Planned Close Date that could
     *                             already be 48 working hours in the past
     */
    List<OverdueTicket> candidates(Instant latestPossibleBreach, int limit) {
        return jdbc.sql(CANDIDATES)
                .param("latestPossibleBreach", Timestamp.from(latestPossibleBreach))
                .param("limit", limit)
                .query(OverdueTicket.class)
                .list();
    }

    boolean claim(long ticketId, Long escalatedTo, BigDecimal overdueHours) {
        return jdbc.sql(CLAIM)
                .param("ticketId", ticketId)
                .param("escalatedTo", escalatedTo)
                .param("overdueHours", overdueHours)
                .update() == 1;
    }

    java.util.Optional<String> emailOf(long userId) {
        return jdbc.sql(EMAIL).param("id", userId).query(String.class).optional();
    }

    /**
     * @param escalateToId the reporting manager's own manager — null when the
     *                     assignee is near the top of the tree, or unassigned
     */
    record OverdueTicket(
            long id,
            String ticketCode,
            String title,
            String level,
            Integer taskTypeId,
            long projectId,
            Timestamp plannedCloseDate,
            Long assignedTo,
            Long reportingManagerId,
            Long escalateToId) {
    }
}
