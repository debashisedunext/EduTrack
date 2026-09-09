package com.edunext.edutrack.api.feature.onboarding.escalations;

import com.edunext.edutrack.common.pagination.Cursor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

/**
 * C-126 · {@code ob_client_escalations} — the staff side's read, the raise
 * insert and the one guarded resolve update.
 *
 * <h2>Read scope is borrowed, not restated</h2>
 *
 * <p>{@link ObEscalationScope} already writes the five module roles' rule as
 * a SQL predicate over an alias carrying {@code step_id} and {@code
 * ob_client_id} — exactly {@code ob_client_escalations}' own two columns —
 * so it is reused verbatim here rather than written a second time.
 * {@code ObReportScope}'s own javadoc names two expressions of one security
 * rule as the risk this avoids.
 *
 * <h2>Raise and resolve are not scoped the same way</h2>
 *
 * <p>{@link #insert} and {@link #findOpenByStep} are deliberately unscoped:
 * the one caller of {@code insert} is the portal raise route, which has
 * already proven the step belongs to the calling client through {@code
 * ClientPrincipal} — there is no staff module role to apply. {@link
 * #resolve} is a plain guarded {@code UPDATE ... WHERE id = ? AND
 * resolved_at IS NULL}, {@code ObTatRepository.flagBreached}'s own idiom:
 * the row is already fetched (and 404/422-checked) under scope by the
 * service before this runs, so the guard only has to settle the race
 * between two staff members resolving the same escalation at once.
 */
@Repository
class ObClientEscalationRepository {

    private static final String SELECT_BASE = """
            SELECT e.id                       AS id,
                   e.ob_client_id             AS obClientId,
                   c.name                     AS obClientName,
                   e.journey_id               AS journeyId,
                   e.step_id                  AS stepId,
                   s.name                     AS stepTitle,
                   rc.id                      AS contactId,
                   rc.name                    AS contactName,
                   rc.designation             AS contactDesignation,
                   rc.email                   AS contactEmail,
                   rc.phone                   AS contactPhone,
                   rc.whatsapp_opt_in         AS contactWhatsappOptIn,
                   rc.whatsapp_opt_in_at      AS contactWhatsappOptInAt,
                   rc.whatsapp_opt_in_source  AS contactWhatsappOptInSource,
                   (rc.is_primary = 1 AND rc.is_active = 1) AS contactIsPrimary,
                   rc.is_active               AS contactIsActive,
                   e.comment                  AS comment,
                   e.raised_at                AS raisedAt,
                   e.resolved_by              AS resolvedBy,
                   rb.full_name               AS resolvedByName,
                   e.resolved_at              AS resolvedAt,
                   e.resolution_note          AS resolutionNote
              FROM ob_client_escalations e
              JOIN ob_journey_steps    s ON s.id = e.step_id
              JOIN ob_clients          c ON c.id = e.ob_client_id
              JOIN ob_client_contacts rc ON rc.id = e.raised_by_contact_id
         LEFT JOIN users              rb ON rb.id = e.resolved_by
            """;

    private static final String LIST = SELECT_BASE + """
             WHERE %s
               AND (:obClientId IS NULL OR e.ob_client_id = :obClientId)
               AND (:journeyId  IS NULL OR e.journey_id   = :journeyId)
               AND %s
               AND (:cursorAt IS NULL
                    OR e.raised_at > :cursorAt
                    OR (e.raised_at = :cursorAt AND e.id > :cursorId))
             ORDER BY e.raised_at ASC, e.id ASC
             LIMIT :limit
            """;

    /** One row, addressed by id — the same scope predicate the list applies, so an out-of-scope id answers empty exactly as A-112 asks. */
    private static final String FIND_BY_ID = SELECT_BASE + """
             WHERE e.id = :id
               AND %s
            """;

    /** Unscoped — see the class javadoc on why the raise route needs no module-role predicate here. */
    private static final String FIND_OPEN_BY_STEP = SELECT_BASE + """
             WHERE e.step_id = :stepId
               AND e.resolved_at IS NULL
            """;

    private static final String INSERT = """
            INSERT INTO ob_client_escalations
                   (ob_client_id, journey_id, step_id, raised_by_contact_id, comment, raised_at)
            VALUES (:obClientId, :journeyId, :stepId, :raisedByContactId, :comment, :now)
            """;

    /** {@code ObTatRepository.flagBreached}'s idiom: one row, whichever caller gets there first. */
    private static final String RESOLVE = """
            UPDATE ob_client_escalations
               SET resolved_by = :resolvedBy, resolved_at = :now, resolution_note = :note
             WHERE id = :id
               AND resolved_at IS NULL
            """;

    private static final String RESOLVE_ONBOARDING_MANAGER = """
            SELECT uma.user_id AS userId
              FROM user_module_access uma
             WHERE uma.module = 'ONBOARDING'
               AND uma.module_role = 'OB_MANAGER'
               AND uma.revoked_at IS NULL
             ORDER BY uma.granted_at ASC, uma.id ASC
             LIMIT 1
            """;

    private final JdbcClient jdbc;

    ObClientEscalationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<Row> list(ObEscalationScope scope, Long obClientId, Long journeyId, String state,
                   String cursor, int fetchSize) {
        String sql = LIST.formatted(scope.predicate("e"), statePredicate(state));
        var spec = jdbc.sql(sql)
                .param("obClientId", obClientId)
                .param("journeyId", journeyId)
                .param("limit", fetchSize);
        if (!scope.unrestricted()) {
            spec = spec.param(ObEscalationScope.USER_PARAM, scope.userId());
        }
        Cursor decoded = decodeCursor(cursor);
        spec = spec.param("cursorAt", decoded == null ? null : Timestamp.from(Instant.parse(decoded.sortKey())))
                .param("cursorId", decoded == null ? null : decoded.id());
        return spec.query(MAPPER).list();
    }

    Optional<Row> findById(ObEscalationScope scope, long id) {
        String sql = FIND_BY_ID.formatted(scope.predicate("e"));
        var spec = jdbc.sql(sql).param("id", id);
        if (!scope.unrestricted()) {
            spec = spec.param(ObEscalationScope.USER_PARAM, scope.userId());
        }
        return spec.query(MAPPER).optional();
    }

    /** The one open escalation on this step, if any — the raise route's idempotency check. */
    Optional<Row> findOpenByStep(long stepId) {
        return jdbc.sql(FIND_OPEN_BY_STEP).param("stepId", stepId).query(MAPPER).optional();
    }

    /**
     * @return the generated id
     * @throws DuplicateKeyException if another request raised on this step first —
     *         {@code uq_ob_client_escalations_open}, the migration's own guard against a
     *         double-click, caught by the caller rather than prevented here
     */
    long insert(long obClientId, long journeyId, long stepId, long raisedByContactId, String comment, Instant now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql(INSERT)
                .param("obClientId", obClientId)
                .param("journeyId", journeyId)
                .param("stepId", stepId)
                .param("raisedByContactId", raisedByContactId)
                .param("comment", comment)
                .param("now", Timestamp.from(now))
                .update(keys);
        Number generated = keys.getKey();
        if (generated == null) {
            throw new IllegalStateException("ob_client_escalations insert returned no generated id");
        }
        return generated.longValue();
    }

    /** @return true if this call resolved it; false if it was already resolved (or does not exist) */
    boolean resolve(long id, long resolvedBy, String note, Instant now) {
        return jdbc.sql(RESOLVE)
                .param("id", id)
                .param("resolvedBy", resolvedBy)
                .param("note", note)
                .param("now", Timestamp.from(now))
                .update() == 1;
    }

    /** The earliest live {@code OB_MANAGER} grant, or {@code null} if the module has none — {@code ObEscalationLadderRepository.resolveObAdmin}'s own idiom, one role over. */
    Long resolveOnboardingManager() {
        return jdbc.sql(RESOLVE_ONBOARDING_MANAGER).query(Long.class).optional().orElse(null);
    }

    /** Defaults to {@code OPEN}, the contract's own reading. */
    private static String statePredicate(String state) {
        return "RESOLVED".equals(state) ? "e.resolved_at IS NOT NULL" : "e.resolved_at IS NULL";
    }

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
            rs.getLong("contactId"),
            rs.getString("contactName"),
            rs.getString("contactDesignation"),
            rs.getString("contactEmail"),
            rs.getString("contactPhone"),
            rs.getBoolean("contactWhatsappOptIn"),
            instant(rs, "contactWhatsappOptInAt"),
            rs.getString("contactWhatsappOptInSource"),
            rs.getBoolean("contactIsPrimary"),
            rs.getBoolean("contactIsActive"),
            rs.getString("comment"),
            instant(rs, "raisedAt"),
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
            long contactId,
            String contactName,
            String contactDesignation,
            String contactEmail,
            String contactPhone,
            boolean contactWhatsappOptIn,
            Instant contactWhatsappOptInAt,
            String contactWhatsappOptInSource,
            boolean contactIsPrimary,
            boolean contactIsActive,
            String comment,
            Instant raisedAt,
            Long resolvedBy,
            String resolvedByName,
            Instant resolvedAt,
            String resolutionNote) {
    }
}
