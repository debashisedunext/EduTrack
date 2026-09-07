package com.edunext.edutrack.api.feature.onboarding.escalations;

import com.edunext.edutrack.common.pagination.Cursor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

/**
 * C-115 · the read side of {@code GET /onboarding/escalations} (OB-02,
 * OB-10) — {@code ob_escalations}, open ones first and oldest first within
 * that, as the contract's own description asks.
 *
 * <p>Ordered and cursored on {@code (escalated_at, id)}. The contract's
 * {@code state} filter is single-valued per call (defaults to {@code OPEN}
 * and never mixes states in one response), which is what keeps that pair
 * monotonic — no page ever has to interleave an open row and a resolved one
 * under one sort key.
 */
@Repository
class ObEscalationReadRepository {

    private static final String SELECT_BASE = """
            SELECT e.id                    AS id,
                   e.ob_client_id          AS obClientId,
                   e.journey_id            AS journeyId,
                   e.step_id               AS stepId,
                   s.name                  AS stepTitle,
                   c.name                  AS obClientName,
                   e.level                 AS level,
                   e.reason                AS reason,
                   e.escalated_to          AS escalatedTo,
                   et.full_name            AS escalatedToName,
                   e.escalated_at          AS escalatedAt,
                   e.acknowledged_by       AS acknowledgedBy,
                   ab.full_name            AS acknowledgedByName,
                   e.acknowledged_at       AS acknowledgedAt,
                   e.resolved_by           AS resolvedBy,
                   rb.full_name            AS resolvedByName,
                   e.resolved_at           AS resolvedAt,
                   e.resolution_note       AS resolutionNote
              FROM ob_escalations e
              JOIN ob_journey_steps s ON s.id = e.step_id
              JOIN ob_clients      c ON c.id = e.ob_client_id
         LEFT JOIN users           et ON et.id = e.escalated_to
         LEFT JOIN users           ab ON ab.id = e.acknowledged_by
         LEFT JOIN users           rb ON rb.id = e.resolved_by
            """;

    private static final String LIST = SELECT_BASE + """
             WHERE %s
               AND (:obClientId IS NULL OR e.ob_client_id = :obClientId)
               AND (:journeyId  IS NULL OR e.journey_id   = :journeyId)
               AND (:escalatedTo IS NULL OR e.escalated_to = :escalatedTo)
               AND (:level IS NULL OR e.level = :level)
               AND %s
               AND (:cursorAt IS NULL
                    OR e.escalated_at > :cursorAt
                    OR (e.escalated_at = :cursorAt AND e.id > :cursorId))
             ORDER BY e.escalated_at ASC, e.id ASC
             LIMIT :limit
            """;

    /** One row, addressed by id — the same scope predicate the list applies, so an out-of-scope id answers empty exactly as A-112 asks. */
    private static final String FIND_BY_ID = SELECT_BASE + """
             WHERE e.id = :id
               AND %s
            """;

    private final JdbcClient jdbc;

    ObEscalationReadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param fetchSize {@link com.edunext.edutrack.common.pagination.PageLimit#fetchSize} — one
     *                  more than the page, so {@code CursorPage.of} can tell {@code hasMore}
     */
    List<Row> list(ObEscalationScope scope, Long obClientId, Long journeyId, Long escalatedTo,
                   String level, String state, String cursor, int fetchSize) {
        String sql = LIST.formatted(scope.predicate("e"), statePredicate(state));
        var spec = jdbc.sql(sql)
                .param("obClientId", obClientId)
                .param("journeyId", journeyId)
                .param("escalatedTo", escalatedTo)
                .param("level", level)
                .param("limit", fetchSize);
        if (!scope.unrestricted()) {
            spec = spec.param(ObEscalationScope.USER_PARAM, scope.userId());
        }
        Cursor decoded = decodeCursor(cursor);
        spec = spec.param("cursorAt", decoded == null ? null : Timestamp.from(Instant.parse(decoded.sortKey())))
                .param("cursorId", decoded == null ? null : decoded.id());
        return spec.query(MAPPER).list();
    }

    /** One rung, scoped exactly as the list is — empty for "no such row" and "not yours" alike. */
    Optional<Row> findById(ObEscalationScope scope, long id) {
        String sql = FIND_BY_ID.formatted(scope.predicate("e"));
        var spec = jdbc.sql(sql).param("id", id);
        if (!scope.unrestricted()) {
            spec = spec.param(ObEscalationScope.USER_PARAM, scope.userId());
        }
        return spec.query(MAPPER).optional();
    }

    /** Defaults to {@code OPEN}, which here means unresolved — the contract's own reading. */
    private static String statePredicate(String state) {
        return switch (state == null ? "OPEN" : state) {
            case "ACKNOWLEDGED" -> "e.resolved_at IS NULL AND e.acknowledged_at IS NOT NULL";
            case "RESOLVED" -> "e.resolved_at IS NOT NULL";
            default -> "e.resolved_at IS NULL";
        };
    }

    /**
     * The cursor names {@code escalated_at|id}. Malformed, absent or an
     * unparseable {@code sortKey} is the first page — {@code Cursor}'s own
     * convention — rather than a 400.
     */
    private static Cursor decodeCursor(String cursor) {
        Cursor decoded = Cursor.decode(cursor);
        if (decoded == null) {
            return null;
        }
        try {
            Instant.parse(decoded.sortKey());
            return decoded;
        } catch (DateTimeParseException malformed) {
            return null;
        }
    }

    private static final RowMapper<Row> MAPPER = (ResultSet rs, int rowNum) -> new Row(
            rs.getLong("id"),
            rs.getLong("obClientId"),
            rs.getString("obClientName"),
            rs.getLong("journeyId"),
            rs.getLong("stepId"),
            rs.getString("stepTitle"),
            rs.getString("level"),
            rs.getString("reason"),
            nullableLong(rs, "escalatedTo"),
            rs.getString("escalatedToName"),
            instant(rs, "escalatedAt"),
            nullableLong(rs, "acknowledgedBy"),
            rs.getString("acknowledgedByName"),
            instant(rs, "acknowledgedAt"),
            nullableLong(rs, "resolvedBy"),
            rs.getString("resolvedByName"),
            instant(rs, "resolvedAt"),
            rs.getString("resolutionNote"));

    /** By hand — {@code DATETIME(6)} does not map onto an {@link Instant} unassisted; storage is UTC (CLAUDE.md). */
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    record Row(
            long id,
            long obClientId,
            String obClientName,
            long journeyId,
            long stepId,
            String stepTitle,
            String level,
            String reason,
            Long escalatedTo,
            String escalatedToName,
            Instant escalatedAt,
            Long acknowledgedBy,
            String acknowledgedByName,
            Instant acknowledgedAt,
            Long resolvedBy,
            String resolvedByName,
            Instant resolvedAt,
            String resolutionNote) {
    }
}
