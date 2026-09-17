package com.edunext.edutrack.api.feature.onboarding.signoff;

import com.edunext.edutrack.api.feature.onboarding.clients.ObClientScope;
import com.edunext.edutrack.common.pagination.Cursor;
import com.edunext.edutrack.domain.onboarding.ObSignoffKind;
import com.edunext.edutrack.domain.onboarding.ObSignoffStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The staff sign-off reads — {@code listObSignoffs} and {@code getObSignoff} —
 * scoped by A-112 in SQL.
 *
 * <h2>The secrets are absent from the projection, not from a serializer</h2>
 *
 * <p>{@code token_hash}, {@code otp_hash}, {@code otp_expires_at} and
 * {@code otp_attempts} are not in {@link #COLUMNS}, and neither is
 * {@code pdf_storage_key} — only {@code IS NOT NULL} on it. That is
 * {@code PortalSignoffReader}'s rule applied to the staff side: a column a query
 * never selects cannot be leaked by a DTO somebody widens later, whereas a
 * projection that reads everything and trusts a record to omit it is one
 * careless field away from publishing a hash the schema went to trouble to hide.
 *
 * <h2>Why this is a second reader and not {@code ObSignoffRepository}</h2>
 *
 * <p>The JPA repository loads whole {@link com.edunext.edutrack.domain.onboarding.ObSignoff}
 * entities, secrets included, which is right for the write paths that have to
 * set them and wrong for a list endpoint. It also cannot express A-112's
 * predicate, which is a join onto {@code ob_clients} with a per-role shape.
 * {@code ObSignoffCertificateVisibility} already established the split for this
 * exact feature; this is the same split for the reads beside it.
 *
 * <h2>Out of scope reads as absent</h2>
 *
 * <p>The predicate is composed into every query, so a sign-off belonging to a
 * client the caller cannot see simply does not come back — {@link #find} returns
 * empty and the service turns that into a 404. Blueprint section 2's
 * no-existence-leak rule: a 403 would confirm the row exists.
 */
@Repository
class ObSignoffAdminRepository {

    /**
     * Ordered newest-first, and the keyset is {@code (requested_at, id)} for the
     * reason {@link Cursor} exists: {@code requested_at} alone is not unique —
     * two sign-offs requested in the same microsecond would straddle a page
     * boundary and one of them would never be returned.
     */
    private static final String COLUMNS = """
            SELECT s.id                          AS id,
                   s.ob_client_id                AS ob_client_id,
                   s.journey_id                  AS journey_id,
                   s.step_id                     AS step_id,
                   s.kind                        AS kind,
                   s.status                      AS status,
                   s.requested_by                AS requested_by,
                   u.full_name                   AS requested_by_name,
                   s.requested_at                AS requested_at,
                   s.token_expires_at            AS token_expires_at,
                   s.signed_at                   AS signed_at,
                   s.objected_at                 AS objected_at,
                   s.pdf_storage_key IS NOT NULL AS has_certificate,
                   s.signed_ip                   AS signed_ip,
                   s.signed_user_agent           AS signed_user_agent,
                   s.objection_note              AS objection_note,
                   c.id                          AS c_id,
                   c.name                        AS c_name,
                   c.designation                 AS c_designation,
                   c.email                       AS c_email,
                   c.phone                       AS c_phone,
                   c.whatsapp_opt_in             AS c_whatsapp_opt_in,
                   c.whatsapp_opt_in_at          AS c_whatsapp_opt_in_at,
                   c.whatsapp_opt_in_source      AS c_whatsapp_opt_in_source,
                   c.is_primary_key              AS c_is_primary,
                   c.is_active                   AS c_is_active,
                   sc.id                         AS sc_id,
                   sc.name                       AS sc_name,
                   sc.designation                AS sc_designation,
                   sc.email                      AS sc_email,
                   sc.phone                      AS sc_phone,
                   sc.whatsapp_opt_in            AS sc_whatsapp_opt_in,
                   sc.whatsapp_opt_in_at         AS sc_whatsapp_opt_in_at,
                   sc.whatsapp_opt_in_source     AS sc_whatsapp_opt_in_source,
                   sc.is_primary_key             AS sc_is_primary,
                   sc.is_active                  AS sc_is_active
              FROM ob_signoffs s
              JOIN ob_clients cl              ON cl.id = s.ob_client_id
              JOIN ob_client_contacts c       ON c.id  = s.sent_to_contact_id
              LEFT JOIN ob_client_contacts sc ON sc.id = s.signed_by_contact_id
              LEFT JOIN users u               ON u.id  = s.requested_by
            """;

    private final JdbcClient jdbc;

    ObSignoffAdminRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One page of sign-offs, newest first.
     *
     * @param fetchSize {@code limit + 1} — the extra row is how
     *                  {@link com.edunext.edutrack.common.pagination.CursorPage}
     *                  tells a full page from the last one
     */
    List<Row> list(ObClientScope scope, Long obClientId, Long journeyId,
                   ObSignoffKind kind, ObSignoffStatus status,
                   Cursor after, int fetchSize) {

        if (scope.deniesEverything()) {
            // The predicate would answer "1 = 0"; a caller with no onboarding
            // standing should not cost a round trip to be told nothing.
            return List.of();
        }

        List<String> where = new ArrayList<>();
        where.add(scope.predicate("cl"));
        if (obClientId != null) {
            where.add("s.ob_client_id = :obClientId");
        }
        if (journeyId != null) {
            where.add("s.journey_id = :journeyId");
        }
        if (kind != null) {
            where.add("s.kind = :kind");
        }
        if (status != null) {
            where.add("s.status = :status");
        }
        if (after != null) {
            // Strictly "older than the last row returned", with the id breaking
            // a tie in the same direction the ORDER BY does.
            where.add("(s.requested_at < :afterAt"
                    + " OR (s.requested_at = :afterAt AND s.id < :afterId))");
        }

        var spec = jdbc.sql(COLUMNS
                + " WHERE " + String.join("\n   AND ", where)
                + "\n ORDER BY s.requested_at DESC, s.id DESC"
                + "\n LIMIT :limit");

        if (!scope.unrestricted()) {
            spec = spec.param(ObClientScope.USER_PARAM, scope.userId());
        }
        if (obClientId != null) {
            spec = spec.param("obClientId", obClientId);
        }
        if (journeyId != null) {
            spec = spec.param("journeyId", journeyId);
        }
        if (kind != null) {
            spec = spec.param("kind", kind.name());
        }
        if (status != null) {
            spec = spec.param("status", status.name());
        }
        if (after != null) {
            spec = spec.param("afterAt", Timestamp.from(Instant.parse(after.sortKey())))
                    .param("afterId", after.id());
        }
        return spec.param("limit", fetchSize).query(MAPPER).list();
    }

    /**
     * How many services on this journey are still running — {@code GO_LIVE}'s
     * 422 guard.
     *
     * <p>{@code DONE} and {@code SKIPPED} both count as finished: a skipped step
     * is a decision that it was not needed, not an outstanding one, and
     * {@code ObClientGoLiveService} treats the pair the same way when it decides
     * whether a client may flip to Live-Green. Anything else — {@code PENDING},
     * {@code IN_PROGRESS}, {@code BLOCKED}, {@code WAITING_ON_CLIENT} — is work
     * the client would be signing off unseen.
     *
     * <p>Not scoped: the caller has already been through {@link #findJourney},
     * which is. Re-applying the predicate here would ask the same question twice
     * and answer "0 outstanding" for a journey the caller cannot see, which is
     * the one wrong answer this could give.
     */
    long unfinishedStepsOn(long journeyId) {
        Long count = jdbc.sql("""
                        SELECT COUNT(*)
                          FROM ob_journey_steps
                         WHERE journey_id = :journeyId
                           AND status NOT IN ('DONE', 'SKIPPED')
                        """)
                .param("journeyId", journeyId)
                .query(Long.class)
                .single();
        return count == null ? 0 : count;
    }

    /**
     * The journey a sign-off is being requested against, if this caller may see
     * its client at all.
     *
     * @return empty for no such journey <em>and</em> for one outside scope —
     *         404 either way, so no request can tell them apart
     */
    Optional<SignoffJourney> findJourney(ObClientScope scope, long journeyId) {
        if (scope.deniesEverything()) {
            return Optional.empty();
        }
        var spec = jdbc.sql("""
                        SELECT j.id AS id, j.ob_client_id AS ob_client_id
                          FROM ob_journeys j
                          JOIN ob_clients cl ON cl.id = j.ob_client_id
                         WHERE j.id = :id AND %s
                        """.formatted(scope.predicate("cl")))
                .param("id", journeyId);
        if (!scope.unrestricted()) {
            spec = spec.param(ObClientScope.USER_PARAM, scope.userId());
        }
        return spec.query((rs, n) -> new SignoffJourney(rs.getLong("id"), rs.getLong("ob_client_id")))
                .optional();
    }

    /**
     * Whether this contact may be sent this client's sign-off.
     *
     * <p>The client is half the question, and the important half. The foreign
     * key would accept any contact id in the table, including one belonging to
     * somebody else's client — {@code ObSignoffAcceptService} makes exactly this
     * point about why it refuses to take {@code signed_by_contact_id} from a
     * caller. A sign-off mailed to another client's SPOC is a cross-client
     * disclosure the schema cannot catch.
     */
    boolean isActiveContactOf(long obClientId, long contactId) {
        return jdbc.sql("""
                        SELECT 1
                          FROM ob_client_contacts
                         WHERE id = :contactId
                           AND ob_client_id = :obClientId
                           AND is_active = 1
                         LIMIT 1
                        """)
                .param("contactId", contactId)
                .param("obClientId", obClientId)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    record SignoffJourney(long id, long obClientId) {
    }

    /** @return empty for no such sign-off <em>and</em> for one outside the caller's scope */
    Optional<Row> find(ObClientScope scope, long signoffId) {
        if (scope.deniesEverything()) {
            return Optional.empty();
        }
        var spec = jdbc.sql(COLUMNS + " WHERE s.id = :id AND " + scope.predicate("cl"))
                .param("id", signoffId);
        if (!scope.unrestricted()) {
            spec = spec.param(ObClientScope.USER_PARAM, scope.userId());
        }
        return spec.query(MAPPER).optional();
    }

    /**
     * Flat because the mapper is flat. The service splits it into the contract's
     * {@code ObSignoff} and {@code ObSignoffDetail} shapes — this stays one row
     * per row so that the list and the detail read cannot answer differently
     * about the same sign-off.
     */
    record Row(long id, long obClientId, long journeyId, Long stepId,
               ObSignoffKind kind, ObSignoffStatus status,
               Long requestedBy, String requestedByName, Instant requestedAt,
               Instant tokenExpiresAt, Instant signedAt, Instant objectedAt,
               boolean hasCertificate, String signedIp, String signedUserAgent,
               String objectionNote,
               ObSignoffAdminDtos.ObContact sentToContact,
               ObSignoffAdminDtos.ObContact signedByContact) {
    }

    /**
     * Explicit rather than {@code SimplePropertyRowMapper}, and B-114 paid for
     * the lesson: that mapper reads {@code DATETIME(6)} as a
     * {@code LocalDateTime} and has no converter to {@code Instant}, so it
     * throws on every row. Declaring the record in local time would "fix" it by
     * reintroducing the silent session-timezone conversion {@code DATETIME} was
     * chosen over {@code TIMESTAMP} to avoid.
     */
    private static final RowMapper<Row> MAPPER = (rs, n) -> new Row(
            rs.getLong("id"),
            rs.getLong("ob_client_id"),
            rs.getLong("journey_id"),
            nullableLong(rs, "step_id"),
            ObSignoffKind.valueOf(rs.getString("kind")),
            ObSignoffStatus.valueOf(rs.getString("status")),
            nullableLong(rs, "requested_by"),
            rs.getString("requested_by_name"),
            instant(rs, "requested_at"),
            instant(rs, "token_expires_at"),
            instant(rs, "signed_at"),
            instant(rs, "objected_at"),
            rs.getBoolean("has_certificate"),
            rs.getString("signed_ip"),
            rs.getString("signed_user_agent"),
            rs.getString("objection_note"),
            contact(rs, "c_"),
            contact(rs, "sc_"));

    /**
     * @return null when the {@code LEFT JOIN} found nothing — a sign-off nobody
     *         has signed has no {@code signedByContact}, which is a fact about
     *         the row rather than a missing lookup
     */
    private static ObSignoffAdminDtos.ObContact contact(ResultSet rs, String prefix) throws SQLException {
        rs.getLong(prefix + "id");
        if (rs.wasNull()) {
            return null;
        }
        return new ObSignoffAdminDtos.ObContact(
                rs.getLong(prefix + "id"),
                rs.getString(prefix + "name"),
                rs.getString(prefix + "designation"),
                rs.getString(prefix + "email"),
                rs.getString(prefix + "phone"),
                rs.getBoolean(prefix + "whatsapp_opt_in"),
                instant(rs, prefix + "whatsapp_opt_in_at"),
                rs.getString(prefix + "whatsapp_opt_in_source"),
                rs.getBoolean(prefix + "is_primary"),
                rs.getBoolean(prefix + "is_active"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
