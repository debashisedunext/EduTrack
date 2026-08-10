package com.edunext.edutrack.worker.sla;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * D-023 · stage segments that may have outstayed their stage SLA.
 */
@Repository
class StageSlaRepository {

    /**
     * Candidates, not breaches.
     *
     * <p>Whether a segment has breached is a <em>working-hours</em> question
     * and the working calendar lives in Java (B-024), so SQL cannot answer it.
     * What SQL can do is exclude everything that could not possibly have
     * breached, and the prefilter below is exact rather than a guess:
     *
     * <p><strong>Working hours are never greater than wall-clock hours.</strong>
     * A segment entered less than {@code sla_hours} ago in wall-clock terms
     * cannot have accumulated {@code sla_hours} of working time — weekends and
     * holidays only ever remove time. So the elapsed-seconds test below lets
     * nothing through that should have been caught, while dropping the
     * overwhelming majority of open segments before the calendar is consulted.
     * The reverse test would not be safe, which is why the cheap filter is on
     * this side of the comparison.
     *
     * <p>The threshold is computed <em>per row</em> against that stage's own
     * {@code sla_hours}, not from one cutoff timestamp: every stage has its own
     * budget, and a single {@code now - N hours} would apply one stage's SLA to
     * all of them. {@code TIMESTAMPDIFF} in seconds rather than
     * {@code INTERVAL … HOUR} because {@code sla_hours} is {@code DECIMAL(6,2)}
     * and an interval would silently truncate a 1.5-hour SLA to one hour.
     *
     * <p>{@code exited_at IS NULL} is the ticket's current segment, served by
     * {@code ix_stage_open (to_stage, exited_at)} — the index A-005 added and
     * labelled "stage-SLA scan, Stream D".
     *
     * <p>Segments already announced are excluded by the anti-join rather than
     * re-evaluated and discarded, so a long-breached ticket stops costing a
     * calendar computation every fifteen minutes forever.
     */
    private static final String CANDIDATES = """
            SELECT tr.id AS transitionId, tr.ticket_id AS ticketId, tr.to_stage AS stageCode,
                   tr.entered_at AS enteredAt, tr.to_user_id AS stageOwnerId,
                   ws.sla_hours AS slaHours, ws.display_name AS stageName,
                   t.ticket_code AS ticketCode, t.project_id AS projectId,
                   p.manager_id AS projectManagerId,
                   u.reporting_manager_id AS reportingManagerId
              FROM ticket_stage_transitions tr
              JOIN tickets t ON t.id = tr.ticket_id
              JOIN projects p ON p.id = t.project_id
              JOIN workflow_stages ws ON ws.template_id = t.workflow_template_id
                                     AND ws.stage_code = tr.to_stage
              LEFT JOIN users u ON u.id = tr.to_user_id
              LEFT JOIN stage_sla_alerts a ON a.transition_id = tr.id
             WHERE tr.exited_at IS NULL
               AND a.transition_id IS NULL
               AND t.actual_close_date IS NULL
               AND ws.sla_hours IS NOT NULL
               AND TIMESTAMPDIFF(SECOND, tr.entered_at, :now) >= ws.sla_hours * 3600
             ORDER BY tr.entered_at
             LIMIT :limit
            """;

    /**
     * Claim the right to announce this segment.
     *
     * <p>{@code INSERT IGNORE} against the primary key: the row count is 1 for
     * the caller that got there first and 0 for anybody else. That makes the
     * claim atomic without a lock, and without the flag living on the
     * append-only transition row.
     */
    private static final String CLAIM = """
            INSERT IGNORE INTO stage_sla_alerts (transition_id, breached_by)
            VALUES (:transitionId, :breachedBy)
            """;

    private static final String EMAILS = """
            SELECT id, email FROM users WHERE id IN (:ids) AND is_active = 1
            """;

    private final JdbcClient jdbc;

    StageSlaRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Open segments whose wall-clock age has reached their own stage SLA. */
    List<OpenStage> candidates(Instant now, int limit) {
        return jdbc.sql(CANDIDATES)
                .param("now", Timestamp.from(now))
                .param("limit", limit)
                .query(OpenStage.class)
                .list();
    }

    /** @return true if this call claimed the announcement */
    boolean claim(long transitionId, BigDecimal breachedBy) {
        return jdbc.sql(CLAIM)
                .param("transitionId", transitionId)
                .param("breachedBy", breachedBy)
                .update() == 1;
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

    /**
     * @param stageOwnerId whoever the segment was handed to, null when a stage
     *                     is queued rather than assigned
     */
    record OpenStage(
            long transitionId,
            long ticketId,
            String ticketCode,
            String stageCode,
            String stageName,
            Timestamp enteredAt,
            BigDecimal slaHours,
            long projectId,
            Long stageOwnerId,
            Long projectManagerId,
            Long reportingManagerId) {
    }
}
