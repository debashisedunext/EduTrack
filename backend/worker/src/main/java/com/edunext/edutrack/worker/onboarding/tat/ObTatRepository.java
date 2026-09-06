package com.edunext.edutrack.worker.onboarding.tat;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * C-113 · steps whose TAT has passed and have not yet been flagged.
 *
 * <h2>Candidates, and the filters the digest already settled</h2>
 *
 * <p>{@code ObDigestRepository} (B-114) answers a related question — which
 * steps have stopped moving — against the same tables, and this repeats its
 * "actually running" filter rather than inventing a second one: not
 * archived, not completed, past the prerequisite gate, not held by another
 * journey (§5.5), and the client not {@code ON_HOLD}/{@code DROPPED}
 * (§5.11's own reasoning for the digest applies identically here — a breach
 * mail about a client somebody has already parked is noise, not help).
 *
 * <h2>Only {@code IN_PROGRESS} and {@code BLOCKED} carry a running clock</h2>
 *
 * <p>C-105's own account: internal {@code BLOCKED} does not pause the clock
 * (plan §5.7), so a blocked step can still miss its TAT and that is exactly
 * the case nobody is chasing until this scanner does. {@code
 * WAITING_ON_CLIENT} is the opposite — the clock is paused, {@code due_at}
 * is frozen at whatever it was when the pause was recorded, and {@code
 * resume()} recomputes it working-calendar-aware from the remaining budget.
 * Scanning a paused step against its frozen {@code due_at} would flag a
 * breach the step has not actually run into; the stall it represents is
 * {@code ObDigestRepository}'s "parked" case, not this scanner's "overdue"
 * one.
 *
 * <h2>Detection is a timestamp comparison, exactly as D-020</h2>
 *
 * <p>{@code due_at} is an instant a working-calendar computation already
 * committed to (C-105's {@code computeDueAt}); whether it has passed is
 * {@code due_at < now}, not a second working-hours computation layered on
 * top. <em>How far</em> past it a step is — the mail's "overdue by" figure —
 * is where the working calendar comes back in, in {@link ObTatBreach}.
 */
@Repository
class ObTatRepository {

    /**
     * The scanner's sweep: steps whose clock is running, past their date, and
     * not yet flagged — oldest first, served by {@code ix_ob_journey_steps_due
     * (status, due_at)}, A-104's own index for exactly this query.
     */
    private static final String CANDIDATES = """
            SELECT s.id                    AS stepId,
                   s.journey_id            AS journeyId,
                   j.ob_client_id          AS obClientId,
                   c.name                  AS clientName,
                   p.name                  AS productName,
                   s.name                  AS stepName,
                   s.due_at                AS dueAt,
                   s.owner_user_id         AS ownerUserId,
                   s.backup_owner_user_id  AS backupOwnerUserId
              FROM ob_journey_steps s
              JOIN ob_journeys j ON j.id = s.journey_id
              JOIN ob_clients  c ON c.id = j.ob_client_id
              JOIN ob_products p ON p.id = j.product_id
             WHERE s.status IN ('IN_PROGRESS', 'BLOCKED')
               AND s.due_at IS NOT NULL
               AND s.due_at < :now
               AND s.tat_breached_at IS NULL
               AND j.archived_at  IS NULL
               AND j.completed_at IS NULL
               AND j.gate_status   = 'OPEN'
               AND (j.held_by_journey_id IS NULL OR j.released_at IS NOT NULL)
               AND c.overall_status = 'ONBOARDING'
             ORDER BY s.due_at
             LIMIT :limit
            """;

    /**
     * Claim the right to announce this step's breach.
     *
     * <p>Guarded by the flag itself rather than a side table — {@code
     * ob_journey_steps} is plain and mutable (A-104), unlike {@code
     * ticket_stage_transitions}, so there is no append-only column standing
     * in the way of the direct {@code UPDATE ... WHERE} idiom {@code
     * tickets.escalate} (D-020) already uses. Affects one row for whichever
     * pass gets there first, zero for every pass after.
     */
    private static final String FLIP = """
            UPDATE ob_journey_steps
               SET tat_breached_at = :now
             WHERE id = :id
               AND tat_breached_at IS NULL
            """;

    private final JdbcClient jdbc;

    ObTatRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Overdue steps with a live clock, oldest due date first. */
    List<OverdueStep> candidates(Instant now, int limit) {
        return jdbc.sql(CANDIDATES)
                .param("now", Timestamp.from(now))
                .param("limit", limit)
                .query(MAPPER)
                .list();
    }

    /** @return true if this call claimed the flag */
    boolean flagBreached(long stepId, Instant now) {
        return jdbc.sql(FLIP)
                .param("id", stepId)
                .param("now", Timestamp.from(now))
                .update() == 1;
    }

    private static final RowMapper<OverdueStep> MAPPER = (ResultSet rs, int rowNum) -> new OverdueStep(
            rs.getLong("stepId"),
            rs.getLong("journeyId"),
            rs.getLong("obClientId"),
            rs.getString("clientName"),
            rs.getString("productName"),
            rs.getString("stepName"),
            instant(rs, "dueAt"),
            nullableLong(rs, "ownerUserId"),
            nullableLong(rs, "backupOwnerUserId"));

    /**
     * By hand, on {@code ObDigestRepository.MAPPER}'s own precedent one
     * package over: {@code DATETIME(6)} does not map itself onto an
     * {@link Instant}, and storage is UTC everywhere (CLAUDE.md).
     */
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    /**
     * @param ownerUserId       {@code null} = unresolved (C-103's own note)
     * @param backupOwnerUserId {@code null} = none assigned (C-108, not built)
     */
    record OverdueStep(
            long stepId,
            long journeyId,
            long obClientId,
            String clientName,
            String productName,
            String stepName,
            Instant dueAt,
            Long ownerUserId,
            Long backupOwnerUserId) {
    }
}
