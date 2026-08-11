package com.edunext.edutrack.worker.sla;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * D-026 · open tickets nobody has been given, and who to tell about them.
 */
@Repository
class UnassignedTicketRepository {

    /**
     * <p>The two-hour test is <strong>not</strong> here — it is in working
     * hours, so it belongs where the calendar is (D-027). What is here is the
     * wall-clock prefilter, sound in the only direction that matters: working
     * hours never exceed wall-clock hours, so a ticket raised less than two
     * wall-clock hours ago cannot yet be two working hours old.
     *
     * <p>{@code date_reported} rather than {@code created_at}, because §7.5
     * lets Admin and PM backdate it. A ticket entered on Thursday for a call
     * that came in on Monday has been waiting since Monday, and that is the
     * number triage should be judged on.
     */
    private static final String CANDIDATES = """
            SELECT t.id, t.ticket_code AS ticketCode, t.title, t.level,
                   t.project_id AS projectId, t.date_reported AS reportedAt,
                   p.manager_id AS projectManagerId,
                   a.alerted_at AS lastAlertedAt
              FROM tickets t
              JOIN projects p ON p.id = t.project_id
              LEFT JOIN unassigned_ticket_alerts a ON a.ticket_id = t.id
             WHERE t.assigned_to IS NULL
               AND t.actual_close_date IS NULL
               AND t.date_reported <= :latestPossible
             ORDER BY t.date_reported
             LIMIT :limit
            """;

    /**
     * The Support Desk for one project, as the scope guard will define it.
     *
     * <p><strong>Project members, not every Support user in the company.</strong>
     * CLAUDE.md's row-scoping rule gives Support their projects, and an
     * out-of-scope ticket answers 404. Mailing a support agent about a ticket
     * they would be refused if they clicked the link is worse than not mailing
     * them: it teaches them the alerts are noise.
     *
     * <p>{@code COALESCE(pm.role_in_project, r.code)} because A-003 says the
     * per-project role may differ from the global one — "a Developer globally
     * can be mapped as QA on one project" — so the project's answer wins where
     * it has one.
     */
    private static final String SUPPORT_DESK = """
            SELECT u.id
              FROM project_members pm
              JOIN users u ON u.id = pm.user_id
              JOIN roles r ON r.id = u.role_id
             WHERE pm.project_id = :projectId
               AND pm.is_active = 1
               AND u.is_active = 1
               AND COALESCE(pm.role_in_project, r.code) = 'SUPPORT'
            """;

    /** First alert this ticket has ever had. */
    private static final String CLAIM_FIRST = """
            INSERT IGNORE INTO unassigned_ticket_alerts (ticket_id, alerted_at, first_alerted_at)
            VALUES (:ticketId, :now, :now)
            """;

    /**
     * A repeat, only once the previous alert is itself a working day old.
     *
     * <p>Split from the insert rather than an {@code ON DUPLICATE KEY UPDATE}
     * for the reason D-022 documents: Connector/J reports <em>matched</em> rows,
     * so a condition inside the {@code SET} cannot answer "did I win the claim".
     * In the {@code WHERE} it can.
     */
    private static final String CLAIM_AGAIN = """
            UPDATE unassigned_ticket_alerts
               SET alerted_at = :now
             WHERE ticket_id = :ticketId
               AND alerted_at <= :repeatableIfBefore
            """;

    private static final String EMAILS = """
            SELECT id, email FROM users WHERE id IN (:ids) AND is_active = 1
            """;

    private final JdbcClient jdbc;

    UnassignedTicketRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param latestPossible the newest report time that could already be two
     *                       working hours in the past
     */
    List<UnassignedTicket> candidates(Instant latestPossible, int limit) {
        return jdbc.sql(CANDIDATES)
                .param("latestPossible", Timestamp.from(latestPossible))
                .param("limit", limit)
                .query(UnassignedTicket.class)
                .list();
    }

    List<Long> supportDeskFor(long projectId) {
        return jdbc.sql(SUPPORT_DESK).param("projectId", projectId).query(Long.class).list();
    }

    /**
     * @param repeatableIfBefore an alert at or before this instant may be
     *                           replaced; a later one means triage has already
     *                           been told today
     * @return true if this call is the one that alerts
     */
    boolean claim(long ticketId, Instant now, Instant repeatableIfBefore) {
        int inserted = jdbc.sql(CLAIM_FIRST)
                .param("ticketId", ticketId)
                .param("now", Timestamp.from(now))
                .update();
        if (inserted == 1) {
            return true;
        }
        return jdbc.sql(CLAIM_AGAIN)
                .param("ticketId", ticketId)
                .param("now", Timestamp.from(now))
                .param("repeatableIfBefore", Timestamp.from(repeatableIfBefore))
                .update() == 1;
    }

    /**
     * Forgets tickets that have since been picked up or closed.
     *
     * <p>Without this the table would only ever grow, and worse: a ticket
     * assigned today and <em>reassigned away</em> next week would inherit its
     * old alert row and stay silent for up to a working day, which is exactly
     * when triage most needs telling. The candidate query cannot do this itself
     * — it only ever looks at unassigned tickets, so it never sees the moment
     * one stops being unassigned.
     *
     * <p>Leaves the table meaning "currently unassigned and already announced",
     * which is also the shape a triage dashboard would want to read.
     */
    private static final String FORGET_ASSIGNED = """
            DELETE a FROM unassigned_ticket_alerts a
              JOIN tickets t ON t.id = a.ticket_id
             WHERE t.assigned_to IS NOT NULL
                OR t.actual_close_date IS NOT NULL
            """;

    int forgetAssigned() {
        return jdbc.sql(FORGET_ASSIGNED).update();
    }

    Map<Long, String> emailsOf(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql(EMAILS).param("ids", ids)
                .query((rs, n) -> Map.entry(rs.getLong("id"), rs.getString("email")))
                .list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** @param lastAlertedAt null when triage has never been told about this one */
    record UnassignedTicket(
            long id,
            String ticketCode,
            String title,
            String level,
            long projectId,
            Timestamp reportedAt,
            Long projectManagerId,
            Timestamp lastAlertedAt) {
    }
}
