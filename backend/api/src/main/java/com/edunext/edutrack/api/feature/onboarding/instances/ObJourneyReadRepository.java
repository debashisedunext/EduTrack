package com.edunext.edutrack.api.feature.onboarding.instances;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.edunext.edutrack.api.feature.onboarding.ObStepRag;
import com.edunext.edutrack.api.feature.onboarding.clients.ObClientScope;

/**
 * C-110 · the one journey behind {@code GET /onboarding/journeys/{journeyId}}.
 *
 * <h2>Why this is a query and not {@code ObJourneyRepository.findById}</h2>
 *
 * <p>Half of {@code ObJourneySummary} is not on {@code ob_journeys}:
 * {@code clientName} is denormalised for the eye, the product triple is a
 * join, {@code templateVersion} is the pinned version on
 * {@code ob_journey_templates}, and {@code percentComplete}, {@code rag} and
 * {@code totalTatDays} are aggregates over the step rows. Loading the entity
 * and then issuing four more reads to decorate it would be four round trips
 * for one row — {@code ObClientReadRepository.journeysOf}'s shape, narrowed
 * from "one client's journeys" to "this journey".
 *
 * <h2>Scoped in the same statement, by {@link ObClientScope}'s own predicate</h2>
 *
 * <p>A journey is visible exactly when its client is, so the {@code JOIN} onto
 * {@code ob_clients} that {@code clientName} already needs is also where the
 * scope belongs — {@code ObClientReadRepository.findDetail}'s shape. Composing
 * the predicate rather than respelling §3 here is CLAUDE.md's rule: a second
 * reading of the scope would drift the first time Sales' changed, and it would
 * drift silently because both would look correct in isolation.
 *
 * <p><b>Out of scope answers empty, exactly as "no such journey" does</b>, so
 * the controller's 404 cannot distinguish them — blueprint §2's
 * no-existence-leak rule, which is why this returns an {@code Optional} and
 * not something a caller could turn into a 403.
 */
@Repository
class ObJourneyReadRepository {

    private final JdbcClient jdbc;

    ObJourneyReadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Archived journeys answer empty.
     *
     * <p>An archived journey is a withdrawn purchase, excluded from every
     * count and every list — {@code ObClientReadRepository.journeysOf} drops
     * them for the same reason — so addressing one directly is a 404 rather
     * than a view onto a row the rest of the module pretends is gone.
     */
    Optional<Row> find(ObClientScope scope, long journeyId) {
        if (scope.deniesEverything()) {
            return Optional.empty();
        }
        var spec = jdbc.sql("""
                SELECT j.id                 AS id,
                       j.ob_client_id       AS obClientId,
                       c.name               AS clientName,
                       j.gate_status        AS gateStatus,
                       j.held_by_journey_id AS heldByJourneyId,
                       j.started_at         AS startedAt,
                       j.completed_at       AS completedAt,
                       j.archived_at        AS archivedAt,
                       p.id                 AS productId,
                       p.code               AS productCode,
                       p.name               AS productName,
                       t.id                 AS templateId,
                       t.version            AS templateVersion,
                       (SELECT COUNT(*) FROM ob_journey_steps ts WHERE ts.journey_id = j.id) AS stepCount,
                       (SELECT COUNT(*) FROM ob_journey_steps ds WHERE ds.journey_id = j.id
                         AND ds.status IN ('DONE', 'SKIPPED')) AS stepsSettled,
                       (SELECT COALESCE(SUM(bs.tat_days), 0) FROM ob_journey_steps bs
                         WHERE bs.journey_id = j.id) AS totalTatDays,
                       (SELECT %s FROM ob_journey_steps rs WHERE rs.journey_id = j.id) AS rag
                  FROM ob_journeys j
                  JOIN ob_clients c ON c.id = j.ob_client_id
                  JOIN ob_products p ON p.id = j.product_id
                  JOIN ob_journey_templates t ON t.id = j.template_id
                 WHERE j.id = :id
                   AND j.archived_at IS NULL
                   AND %s
                """.formatted(ObStepRag.worstOverSteps("rs"), scope.predicate("c")))
                .param("id", journeyId);

        if (!scope.unrestricted()) {
            spec = spec.param(ObClientScope.USER_PARAM, scope.userId());
        }
        return spec.query(MAPPER).optional();
    }

    /** The display name behind {@code owner}, resolved only when there is one. */
    Optional<String> displayNameOf(long userId) {
        return jdbc.sql("SELECT full_name FROM users WHERE id = :id")
                .param("id", userId).query(String.class).optional();
    }

    record Row(long id, long obClientId, String clientName, String gateStatus, Long heldByJourneyId,
               Instant startedAt, Instant completedAt, Instant archivedAt,
               long productId, String productCode, String productName,
               long templateId, int templateVersion,
               int stepCount, int stepsSettled, int totalTatDays, String rag) {
    }

    private static final RowMapper<Row> MAPPER = (rs, n) -> new Row(
            rs.getLong("id"),
            rs.getLong("obClientId"),
            rs.getString("clientName"),
            rs.getString("gateStatus"),
            nullableLong(rs, "heldByJourneyId"),
            instant(rs, "startedAt"),
            instant(rs, "completedAt"),
            instant(rs, "archivedAt"),
            rs.getLong("productId"),
            rs.getString("productCode"),
            rs.getString("productName"),
            rs.getLong("templateId"),
            rs.getInt("templateVersion"),
            rs.getInt("stepCount"),
            rs.getInt("stepsSettled"),
            rs.getInt("totalTatDays"),
            rs.getString("rag"));

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
