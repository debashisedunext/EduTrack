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
 * D-025 · open tickets that have bounced backwards often enough to say so.
 */
@Repository
class PingPongRepository {

    /**
     * <p>The whole condition is expressible in SQL here, unlike every other
     * scanner in this package: there is no duration to compute, so nothing has
     * to be re-checked against the working calendar afterwards. A ticket's
     * iteration count is a count.
     *
     * <p>{@code f.iteration_no < t.current_iteration} is what makes a second
     * alert possible without making one inevitable. A ticket flagged at
     * iteration 3 is not a candidate again until it reaches 4 — at which point
     * it genuinely has bounced again, which is new information rather than a
     * repeat of the old.
     *
     * <p>The join to {@code ping_pong_flags} carries {@code cycle_no} because
     * the flag is per cycle. Omitting it would let a flag raised in cycle 1
     * silently suppress the whole of cycle 2.
     *
     * <p>Ordered by iteration descending so that when the cap bites, the worst
     * offenders are the ones that get through.
     */
    private static final String CANDIDATES = """
            SELECT t.id, t.ticket_code AS ticketCode, t.title,
                   t.current_cycle_no AS cycleNo, t.current_iteration AS iterationNo,
                   t.project_id AS projectId, t.assigned_to AS assignedTo,
                   p.manager_id AS projectManagerId,
                   u.reporting_manager_id AS reportingManagerId
              FROM tickets t
              JOIN projects p ON p.id = t.project_id
              LEFT JOIN users u ON u.id = t.assigned_to
              LEFT JOIN ping_pong_flags f
                     ON f.ticket_id = t.id AND f.cycle_no = t.current_cycle_no
             WHERE t.actual_close_date IS NULL
               AND t.current_iteration >= :threshold
               AND (f.ticket_id IS NULL OR f.iteration_no < t.current_iteration)
             ORDER BY t.current_iteration DESC, t.id
             LIMIT :limit
            """;

    /**
     * First time this cycle has ever been flagged.
     *
     * <p>Two statements rather than one {@code ON DUPLICATE KEY UPDATE}, for
     * the reason D-022 documents at length: <strong>Connector/J reports matched
     * rows, not changed rows</strong>, so an upsert that decides in an
     * {@code IF()} inside the {@code SET} still returns 1 and cannot answer
     * "did I win the claim". In a {@code WHERE}, a row that must not be touched
     * is not matched at all and the count is unambiguous.
     */
    private static final String CLAIM_FIRST = """
            INSERT IGNORE INTO ping_pong_flags
                   (ticket_id, cycle_no, iteration_no, first_flagged_at, last_flagged_at)
            VALUES (:ticketId, :cycleNo, :iterationNo, :now, :now)
            """;

    /** It has bounced again since we last said anything. */
    private static final String CLAIM_AGAIN = """
            UPDATE ping_pong_flags
               SET iteration_no = :iterationNo, last_flagged_at = :now
             WHERE ticket_id = :ticketId
               AND cycle_no = :cycleNo
               AND iteration_no < :iterationNo
            """;

    private static final String EMAILS = """
            SELECT id, email FROM users WHERE id IN (:ids) AND is_active = 1
            """;

    private final JdbcClient jdbc;

    PingPongRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<BouncingTicket> candidates(int threshold, int limit) {
        return jdbc.sql(CANDIDATES)
                .param("threshold", threshold)
                .param("limit", limit)
                .query(BouncingTicket.class)
                .list();
    }

    /** @return true if this call is the one that announces this iteration */
    boolean claim(long ticketId, int cycleNo, int iterationNo, Instant now) {
        int inserted = jdbc.sql(CLAIM_FIRST)
                .param("ticketId", ticketId)
                .param("cycleNo", cycleNo)
                .param("iterationNo", iterationNo)
                .param("now", Timestamp.from(now))
                .update();
        if (inserted == 1) {
            return true;
        }
        return jdbc.sql(CLAIM_AGAIN)
                .param("ticketId", ticketId)
                .param("cycleNo", cycleNo)
                .param("iterationNo", iterationNo)
                .param("now", Timestamp.from(now))
                .update() == 1;
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

    /**
     * @param iterationNo the ticket's current iteration — 1 when it has never
     *                    gone backwards, so N iterations means N-1 bounces
     * @param assignedTo  null when nobody holds it, which is D-026's problem
     */
    record BouncingTicket(
            long id,
            String ticketCode,
            String title,
            int cycleNo,
            int iterationNo,
            long projectId,
            Long assignedTo,
            Long projectManagerId,
            Long reportingManagerId) {
    }
}
