package com.edunext.edutrack.worker.onboarding.escalation;

import com.edunext.edutrack.domain.onboarding.ObEscalationLevel;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * C-115 · steps whose TAT has breached and have not yet been escalated at a
 * given rung, plus the ladder's own recipient resolution.
 *
 * <h2>Candidates repeat {@code ObTatRepository}'s "actually running" filter</h2>
 *
 * <p>Same five predicates, same reasoning: not archived, not completed, past
 * the gate, not held, and the client not parked. A step nobody is chasing
 * any more should not climb a ladder either.
 *
 * <h2>One row per (step, level), ever — not just while open</h2>
 *
 * <p>{@code NOT EXISTS} against {@code ob_escalations} checks for <em>any</em>
 * row at that level, resolved or not — {@code uq_ob_escalations_open}'s own
 * partial index only stops two <em>open</em> rungs, and a rung this scanner
 * already raised and somebody already closed must not be raised a second
 * time for the same breach. A step's TAT clock only ever breaches once
 * (§5.7's {@code resume} recomputes {@code due_at} rather than clearing
 * {@code tat_breached_at}), so one ladder climb per breach is the whole
 * lifecycle.
 *
 * <h2>Who L2 and L3 resolve to</h2>
 *
 * <p>{@code ObEscalationRung.recipient}'s {@code ONBOARDING_MANAGER} is
 * {@code users.reporting_manager_id} of the step's owner — {@code
 * ObDigestRepository}'s own account of "the only manager relation the
 * schema has today", carried here rather than rediscovered. {@code OB_ADMIN}
 * is the earliest live grant in {@code user_module_access}, deterministic
 * rather than arbitrary so two passes agree on the same person. Both are
 * B-113's join to replace once the matrix becomes real configuration.
 *
 * <h2>Named {@code Ladder}, not plain {@code ObEscalationRepository}</h2>
 *
 * <p>That simple name already belongs to {@code domain.onboarding}'s JPA
 * repository over {@code ObEscalation} — this worker context scans both
 * packages ({@code @EnableJpaRepositories} for the former, component-scan
 * for this {@code @Repository}), and two beans of the same simple name
 * collide on the bean id Spring would otherwise give both. {@code
 * ObEscalationScannerIT} failing to start its context is what caught it.
 */
@Repository
class ObEscalationLadderRepository {

    private static final String CANDIDATES = """
            SELECT s.id                    AS stepId,
                   s.journey_id            AS journeyId,
                   j.ob_client_id          AS obClientId,
                   c.name                  AS clientName,
                   p.name                  AS productName,
                   s.name                  AS stepName,
                   s.tat_breached_at       AS tatBreachedAt,
                   s.owner_user_id         AS ownerUserId,
                   s.backup_owner_user_id  AS backupOwnerUserId,
                   own.reporting_manager_id AS managerUserId
              FROM ob_journey_steps s
              JOIN ob_journeys j ON j.id = s.journey_id
              JOIN ob_clients  c ON c.id = j.ob_client_id
              JOIN ob_products p ON p.id = j.product_id
         LEFT JOIN users       own ON own.id = s.owner_user_id
             WHERE s.status IN ('IN_PROGRESS', 'BLOCKED')
               AND s.tat_breached_at IS NOT NULL
               AND j.archived_at  IS NULL
               AND j.completed_at IS NULL
               AND j.gate_status   = 'OPEN'
               AND (j.held_by_journey_id IS NULL OR j.released_at IS NOT NULL)
               AND c.overall_status = 'ONBOARDING'
               AND NOT EXISTS (
                     SELECT 1 FROM ob_escalations e
                      WHERE e.step_id = s.id AND e.level = :level
                   )
             ORDER BY s.tat_breached_at
             LIMIT :limit
            """;

    /**
     * {@code IGNORE}, {@code L2EscalationRepository.claim}'s own idiom one
     * package over: the {@code NOT EXISTS} in {@link #CANDIDATES} is the
     * real guard against re-raising a rung that already exists, resolved or
     * not; {@code IGNORE} only covers the narrow race between two scanner
     * passes claiming the same first-time candidate at once, where {@code
     * uq_ob_escalations_open} is what actually decides the winner.
     */
    private static final String INSERT = """
            INSERT IGNORE INTO ob_escalations
                   (ob_client_id, journey_id, step_id, level, reason, escalated_to, escalated_at)
            VALUES (:obClientId, :journeyId, :stepId, :level, :reason, :escalatedTo, :now)
            """;

    private static final String RESOLVE_OB_ADMIN = """
            SELECT uma.user_id AS userId
              FROM user_module_access uma
             WHERE uma.module = 'ONBOARDING'
               AND uma.module_role = 'OB_ADMIN'
               AND uma.revoked_at IS NULL
             ORDER BY uma.granted_at ASC, uma.id ASC
             LIMIT 1
            """;

    private final JdbcClient jdbc;

    ObEscalationLadderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Steps not yet escalated at {@code level}, longest-breached first. */
    List<Candidate> candidates(ObEscalationLevel level, int limit) {
        return jdbc.sql(CANDIDATES)
                .param("level", level.name())
                .param("limit", limit)
                .query(MAPPER)
                .list();
    }

    /** @return true if this call raised the rung; false if another pass claimed it first */
    boolean insert(Candidate step, ObEscalationLevel level, String reason, Long escalatedTo, Instant now) {
        return jdbc.sql(INSERT)
                .param("obClientId", step.obClientId())
                .param("journeyId", step.journeyId())
                .param("stepId", step.stepId())
                .param("level", level.name())
                .param("reason", reason)
                .param("escalatedTo", escalatedTo)
                .param("now", Timestamp.from(now))
                .update() == 1;
    }

    /** The earliest live {@code OB_ADMIN} grant, or {@code null} if the module has none. */
    Long resolveObAdmin() {
        return jdbc.sql(RESOLVE_OB_ADMIN).query(Long.class).optional().orElse(null);
    }

    private static final RowMapper<Candidate> MAPPER = (ResultSet rs, int rowNum) -> new Candidate(
            rs.getLong("stepId"),
            rs.getLong("journeyId"),
            rs.getLong("obClientId"),
            rs.getString("clientName"),
            rs.getString("productName"),
            rs.getString("stepName"),
            instant(rs, "tatBreachedAt"),
            nullableLong(rs, "ownerUserId"),
            nullableLong(rs, "backupOwnerUserId"),
            nullableLong(rs, "managerUserId"));

    /** By hand, {@code ObTatRepository.instant}'s own precedent: storage is UTC (CLAUDE.md). */
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    /**
     * @param ownerUserId       {@code null} = unresolved (C-103)
     * @param backupOwnerUserId {@code null} = none assigned (C-108, not built)
     * @param managerUserId     the owner's {@code reporting_manager_id}, or
     *                          {@code null} if there is no owner or the owner
     *                          has no manager — the L2 gap {@code
     *                          ObDigestRepository} already logs
     */
    record Candidate(
            long stepId,
            long journeyId,
            long obClientId,
            String clientName,
            String productName,
            String stepName,
            Instant tatBreachedAt,
            Long ownerUserId,
            Long backupOwnerUserId,
            Long managerUserId) {
    }
}
