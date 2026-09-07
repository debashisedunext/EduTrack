package com.edunext.edutrack.api.feature.onboarding.clients;

import com.edunext.edutrack.api.feature.onboarding.ObStepRag;
import com.edunext.edutrack.common.pagination.Cursor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * B-102 · the read side of {@code /onboarding/clients} — OB-03's list and
 * OB-05's detail.
 *
 * <h2>Every statement carries {@link ObClientScope}'s predicate</h2>
 *
 * <p>Including {@link #findDetail}, which is what makes an out-of-scope id
 * answer {@code 404} rather than {@code 403}: the row is simply not returned,
 * and the service cannot tell "no such client" from "not yours" because this
 * class does not tell it. CONVENTIONS.md §7, and CLAUDE.md's no-existence-leak
 * rule.
 *
 * <h2>Four queries for a page, not one</h2>
 *
 * <p>The page itself, then products, primary contacts and nothing else — each
 * keyed by the page's ids. Joining them into the page query would multiply rows
 * by purchases and make {@code LIMIT} mean something other than "fifty
 * clients". This is the same shape {@code ClientQueryRepository} (B-025) uses
 * for S-32's grid and for the same reason.
 *
 * <h2>The RAG filter runs in SQL, and only when it is asked for</h2>
 *
 * <p>{@code ObStepRag.worstOverSteps} is a correlated aggregate — cheap per row
 * and not free — and MySQL cannot reference a select alias in {@code WHERE}. So
 * the colour is in the select list always, and the {@code HAVING} that filters
 * on it is appended <b>only when {@code ?rag=} was sent</b>. With no filter,
 * {@code LIMIT} applies before the colour is computed for anything past the
 * page; with one, it necessarily applies after, over the caller's scoped set.
 * That cost is the honest price of a derived filter no index can cover, and it
 * is bounded by the scope rather than by the table.
 */
@Repository
class ObClientReadRepository {

    /**
     * The client-level colour: worst-wins over the steps of every <b>open</b>
     * journey.
     *
     * <p>Locked journeys are excluded rather than counted GREEN, which is the
     * contract's own rule — "null while every journey is locked; OB-03 renders
     * that as 'Prerequisites pending', which is a gate state and not a colour".
     * A locked journey's steps have no running clock by construction (plan
     * §5.2), so colouring them would report health for work nobody has been
     * asked to start.
     */
    private static final String RAG_EXPRESSION = """
            (SELECT %s
               FROM ob_journeys rj
               JOIN ob_journey_steps rs ON rs.journey_id = rj.id
              WHERE rj.ob_client_id = c.id
                AND rj.archived_at IS NULL
                AND rj.gate_status = 'OPEN')
            """.formatted(ObStepRag.worstOverSteps("rs"));

    /**
     * A client's gate is OPEN once any live journey's is.
     *
     * <p>The gate clears for every one of a client's journeys at once and never
     * re-locks (C-118), so "any" and "all" agree except for a product bought
     * after the gate opened — which instantiates directly OPEN
     * ({@code ObJourneyInstantiationService}), so they agree there too. A client
     * with no journeys at all reads LOCKED, which is the state the OB-03 filter
     * calls "Prerequisites pending" and is what an unbought client is.
     */
    private static final String GATE_EXPRESSION = """
            (SELECT IF(SUM(gj.gate_status = 'OPEN') > 0, 'OPEN', 'LOCKED')
               FROM ob_journeys gj
              WHERE gj.ob_client_id = c.id
                AND gj.archived_at IS NULL)
            """;

    private static final String JOURNEY_COUNT = """
            (SELECT COUNT(*) FROM ob_journeys nj
              WHERE nj.ob_client_id = c.id AND nj.archived_at IS NULL)
            """;

    private static final String JOURNEYS_COMPLETE = """
            (SELECT COUNT(*) FROM ob_journeys cj
              WHERE cj.ob_client_id = c.id AND cj.archived_at IS NULL
                AND cj.completed_at IS NOT NULL)
            """;

    /**
     * Whether a {@code client_accounts} row exists — <b>not whether one should</b>.
     *
     * <p>{@code is_active} is deliberately not consulted: a disabled login is a
     * login that exists, and B-126's OB-05 panel has to offer "re-enable"
     * rather than "create", which it cannot do if a disabled account reads as
     * absent.
     */
    private static final String HAS_PORTAL_LOGIN = """
            EXISTS (SELECT 1 FROM client_accounts pa WHERE pa.ob_client_id = c.id)
            """;

    private static final String LIST_COLUMNS = """
            SELECT c.id                AS id,
                   c.name              AS name,
                   c.onboarding_date   AS onboardingDate,
                   c.overall_status    AS status,
                   c.live_at           AS liveAt,
                   c.sales_person_id   AS salesPersonId,
                   sp.full_name        AS salesPersonName,
                   %s                  AS rag,
                   %s                  AS gateStatus,
                   %s                  AS journeyCount,
                   %s                  AS journeysComplete,
                   %s                  AS hasPortalLogin
              FROM ob_clients c
         LEFT JOIN users sp ON sp.id = c.sales_person_id
            """.formatted(RAG_EXPRESSION, GATE_EXPRESSION, JOURNEY_COUNT, JOURNEYS_COMPLETE, HAS_PORTAL_LOGIN);

    /**
     * The list's filters, all optional and all narrowing.
     *
     * <p>{@code productId} asks "who has a journey for this product", which is
     * the contract's wording. It is the same set as "who bought it" — a create
     * is atomic over the purchase and its journey — but the journey is what the
     * screen is about, and a purchase whose journey was archived is not a row
     * OB-03 should offer under a product filter.
     */
    private static final String LIST_FILTERS = """
             WHERE %s
               AND (:q IS NULL OR c.name LIKE :q)
               AND (:status IS NULL OR c.overall_status = :status)
               AND (:salesPersonId IS NULL OR c.sales_person_id = :salesPersonId)
               AND (:productId IS NULL OR EXISTS (
                     SELECT 1 FROM ob_journeys pj
                      WHERE pj.ob_client_id = c.id
                        AND pj.product_id = :productId
                        AND pj.archived_at IS NULL))
               AND (:gateStatus IS NULL OR %s = :gateStatus)
               AND (:cursorDate IS NULL
                    OR c.onboarding_date < :cursorDate
                    OR (c.onboarding_date = :cursorDate AND c.id < :cursorId))
            """;

    private static final String ORDER_AND_LIMIT = """
             ORDER BY c.onboarding_date DESC, c.id DESC
             LIMIT :limit
            """;

    /**
     * The detail read — the list's columns plus the ones a list must not carry.
     *
     * <p>Written out rather than composed from {@link #LIST_COLUMNS}, because
     * the difference between the two is the point: {@code address},
     * {@code description} and above all {@code pan_ciphertext} appear here and
     * nowhere else. The contract's own line is that "identity data belongs to
     * the detail read, where the masking rule and its audit apply, and a list
     * is the wrong place to leak it a page at a time" — a shared select list
     * with a projection applied afterwards would put the PAN of fifty clients
     * on the wire between MySQL and this process on every page of OB-03, which
     * is the leak one layer down from the one the contract is describing.
     */
    private static final String DETAIL = """
            SELECT c.id                AS id,
                   c.name              AS name,
                   c.onboarding_date   AS onboardingDate,
                   c.overall_status    AS status,
                   c.live_at           AS liveAt,
                   c.sales_person_id   AS salesPersonId,
                   sp.full_name        AS salesPersonName,
                   c.description       AS description,
                   c.address           AS address,
                   c.license_type      AS licenseType,
                   c.status_reason     AS statusReason,
                   c.pan_ciphertext    AS panCiphertext,
                   c.created_by        AS createdBy,
                   cb.full_name        AS createdByName,
                   c.created_at        AS createdAt,
                   %s                  AS rag,
                   %s                  AS gateStatus,
                   %s                  AS journeyCount,
                   %s                  AS journeysComplete,
                   %s                  AS hasPortalLogin
              FROM ob_clients c
         LEFT JOIN users sp ON sp.id = c.sales_person_id
         LEFT JOIN users cb ON cb.id = c.created_by
            """.formatted(RAG_EXPRESSION, GATE_EXPRESSION, JOURNEY_COUNT, JOURNEYS_COMPLETE,
            HAS_PORTAL_LOGIN)
            // The scope predicate is the caller's, so it is applied where it is
            // used rather than baked in here.
            + """
             WHERE c.id = :id
               AND %s
            """;

    private final JdbcClient jdbc;

    ObClientReadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // The page
    // ------------------------------------------------------------------

    List<ListRow> list(ObClientScope scope, String q, String status, String rag, String gateStatus,
                       Long productId, Long salesPersonId, String cursor, int fetchSize) {

        String sql = LIST_COLUMNS + LIST_FILTERS.formatted(scope.predicate("c"), GATE_EXPRESSION.trim())
                // Only when asked for: see the class javadoc on what appending
                // this costs and why it is not appended unconditionally.
                + (rag == null || rag.isBlank() ? "" : " HAVING rag = :rag\n")
                + ORDER_AND_LIMIT;

        Cursor decoded = decodeCursor(cursor);
        var spec = jdbc.sql(sql)
                .param("q", q == null || q.isBlank() ? null : "%" + q.trim() + "%")
                .param("status", blankToNull(status))
                .param("gateStatus", blankToNull(gateStatus))
                .param("productId", productId)
                .param("salesPersonId", salesPersonId)
                .param("cursorDate", decoded == null ? null : Date.valueOf(LocalDate.parse(decoded.sortKey())))
                .param("cursorId", decoded == null ? null : decoded.id())
                .param("limit", fetchSize);
        if (rag != null && !rag.isBlank()) {
            spec = spec.param("rag", rag.trim().toUpperCase(java.util.Locale.ROOT));
        }
        if (!scope.unrestricted()) {
            spec = spec.param(ObClientScope.USER_PARAM, scope.userId());
        }
        return spec.query(LIST_MAPPER).list();
    }

    /** One client, scoped exactly as the list is — empty for "no such row" and "not yours" alike. */
    Optional<DetailRow> findDetail(ObClientScope scope, long id) {
        var spec = jdbc.sql(DETAIL.formatted(scope.predicate("c"))).param("id", id);
        if (!scope.unrestricted()) {
            spec = spec.param(ObClientScope.USER_PARAM, scope.userId());
        }
        return spec.query(DETAIL_MAPPER).optional();
    }

    // ------------------------------------------------------------------
    // The page's companions, and the detail's children
    // ------------------------------------------------------------------

    List<ProductRow> productsOf(Collection<Long> clientIds) {
        if (clientIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT a.ob_client_id AS obClientId, p.id AS id, p.code AS code, p.name AS name
                  FROM ob_client_applications a
                  JOIN ob_products p ON p.id = a.product_id
                 WHERE a.ob_client_id IN (:ids)
                 ORDER BY p.name, p.id
                """).param("ids", clientIds).query(PRODUCT_MAPPER).list();
    }

    /**
     * The primary SPOC of each client on the page.
     *
     * <p>{@code is_primary_key} rather than {@code is_primary = 1}: the
     * generated column is 1 only while the contact is <em>also</em> active, and
     * it is the column {@code uq_ob_client_contacts_primary} is on — so this
     * reads the same fact the database enforces rather than a second one that
     * could drift from it.
     */
    List<ContactRow> primaryContactsOf(Collection<Long> clientIds) {
        if (clientIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(CONTACT_COLUMNS + """
                 WHERE ct.ob_client_id IN (:ids)
                   AND ct.is_primary_key = 1
                """).param("ids", clientIds).query(CONTACT_MAPPER).list();
    }

    /**
     * Every contact of one client, inactive ones included.
     *
     * <p>OB-05 administers the SPOC list, and a contact who has left is
     * deactivated rather than deleted ({@code is_active}, following B-027's
     * precedent) — a screen that could not see them could not reactivate one,
     * and the sign-offs they gave would appear to have come from nobody.
     */
    List<ContactRow> contactsOf(long clientId) {
        return jdbc.sql(CONTACT_COLUMNS + """
                 WHERE ct.ob_client_id = :id
                 ORDER BY ct.is_primary DESC, ct.is_active DESC, ct.name, ct.id
                """).param("id", clientId).query(CONTACT_MAPPER).list();
    }

    List<ApplicationRow> applicationsOf(long clientId) {
        return jdbc.sql("""
                SELECT a.id AS id, a.license_type AS licenseType, a.units AS units,
                       a.license_start AS licenseStart, a.license_end AS licenseEnd,
                       p.id AS productId, p.code AS productCode, p.name AS productName
                  FROM ob_client_applications a
                  JOIN ob_products p ON p.id = a.product_id
                 WHERE a.ob_client_id = :id
                 ORDER BY p.name, p.id
                """).param("id", clientId).query(APPLICATION_MAPPER).list();
    }

    List<String> requirementsOf(long clientId) {
        return jdbc.sql("""
                SELECT body FROM ob_client_requirements
                 WHERE ob_client_id = :id
                 ORDER BY sequence, id
                """).param("id", clientId).query(String.class).list();
    }

    // ------------------------------------------------------------------
    // OB-05's accordion strips
    // ------------------------------------------------------------------

    /**
     * One client's live journeys, with the numbers the collapsed strip prints.
     *
     * <p>Not paginated and not pageable: a client's purchases are a handful,
     * and the accordion needs the set to render any of it. Archived journeys
     * are excluded — an archived journey is a template version that was
     * replaced, and showing it beside its replacement would double every strip.
     */
    List<JourneyRow> journeysOf(long clientId) {
        return jdbc.sql("""
                SELECT j.id                 AS id,
                       j.gate_status        AS gateStatus,
                       j.held_by_journey_id AS blockedByJourneyId,
                       p.id                 AS productId,
                       p.code               AS productCode,
                       p.name               AS productName,
                       (SELECT COUNT(*) FROM ob_journey_steps ts WHERE ts.journey_id = j.id) AS stepCount,
                       (SELECT COUNT(*) FROM ob_journey_steps ds WHERE ds.journey_id = j.id
                         AND ds.status IN ('DONE', 'SKIPPED')) AS stepsSettled,
                       (SELECT COALESCE(SUM(bs.tat_days), 0) FROM ob_journey_steps bs
                         WHERE bs.journey_id = j.id) AS totalTatDays,
                       (SELECT %s FROM ob_journey_steps rs WHERE rs.journey_id = j.id) AS rag
                  FROM ob_journeys j
                  JOIN ob_products p ON p.id = j.product_id
                 WHERE j.ob_client_id = :id
                   AND j.archived_at IS NULL
                 ORDER BY p.name, j.id
                """.formatted(ObStepRag.worstOverSteps("rs")))
                .param("id", clientId).query(JOURNEY_MAPPER).list();
    }

    /** Every dot of every live journey of one client, in template order. */
    List<StepDotRow> stepDotsOf(long clientId) {
        return jdbc.sql("""
                SELECT s.id                 AS id,
                       s.journey_id         AS journeyId,
                       s.sequence           AS sequence,
                       s.name               AS name,
                       s.status             AS status,
                       s.depends_on_step_id AS dependsOnStepId,
                       %s                   AS rag
                  FROM ob_journey_steps s
                  JOIN ob_journeys j ON j.id = s.journey_id
                 WHERE j.ob_client_id = :id
                   AND j.archived_at IS NULL
                 ORDER BY s.journey_id, s.sequence, s.id
                """.formatted(ObStepRag.colourOfStep("s")))
                .param("id", clientId).query(STEP_DOT_MAPPER).list();
    }

    // ------------------------------------------------------------------
    // The duplicate-name guard's candidates
    // ------------------------------------------------------------------

    /**
     * Clients whose name contains the probe word, for
     * {@link SimilarClientNames} to score.
     *
     * <p><b>Unscoped, and capped.</b> Unscoped for the reason
     * {@code ObClientRepository}'s own javadoc gives for the PAN guard: "is
     * this client already on file" is a fact about the organisation, not about
     * the caller, and a scoped check would let two Sales users each board the
     * same client because neither can see the other's. What the <em>caller</em>
     * is then told about an out-of-scope match is
     * {@code ObClientWriteService}'s decision, not this method's.
     *
     * <p>The cap is a stop, not a page. A probe generic enough to return
     * hundreds of rows has already failed to be a probe, and scoring the first
     * few hundred still surfaces a real duplicate; scoring twenty thousand on
     * every wizard submission would make the guard the slowest thing on the
     * screen and, eventually, the thing that gets switched off.
     */
    List<NameRow> namesContaining(String probe, int cap) {
        if (probe == null || probe.isBlank()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT c.id AS id, c.name AS name, c.created_by AS createdBy
                  FROM ob_clients c
                 WHERE c.name LIKE :probe
                 ORDER BY c.id DESC
                 LIMIT :cap
                """)
                .param("probe", "%" + probe + "%")
                .param("cap", cap)
                .query(NAME_MAPPER).list();
    }

    /** Whether a client is visible to this caller — the scope predicate, asked about one id. */
    boolean isVisible(ObClientScope scope, long id) {
        var spec = jdbc.sql("SELECT EXISTS (SELECT 1 FROM ob_clients c WHERE c.id = :id AND "
                + scope.predicate("c") + ")").param("id", id);
        if (!scope.unrestricted()) {
            spec = spec.param(ObClientScope.USER_PARAM, scope.userId());
        }
        return Boolean.TRUE.equals(spec.query(Boolean.class).single());
    }

    // ------------------------------------------------------------------
    // Rows and mappers
    // ------------------------------------------------------------------

    record ListRow(long id, String name, LocalDate onboardingDate, String status, Instant liveAt,
                   Long salesPersonId, String salesPersonName, String rag, String gateStatus,
                   int journeyCount, int journeysComplete, boolean hasPortalLogin) {
    }

    record DetailRow(ListRow summary, String description, String address, String licenseType,
                     String statusReason, byte[] panCiphertext, Long createdBy, String createdByName,
                     Instant createdAt) {
    }

    record ProductRow(long obClientId, long id, String code, String name) {
    }

    record ContactRow(long obClientId, long id, String name, String designation, String email,
                      String phone, boolean whatsappOptIn, boolean isPrimary, boolean isActive) {
    }

    record ApplicationRow(long id, String licenseType, Integer units, LocalDate licenseStart,
                          LocalDate licenseEnd, long productId, String productCode, String productName) {
    }

    record JourneyRow(long id, String gateStatus, Long blockedByJourneyId, long productId,
                      String productCode, String productName, int stepCount, int stepsSettled,
                      int totalTatDays, String rag) {
    }

    record StepDotRow(long id, long journeyId, int sequence, String name, String status,
                      Long dependsOnStepId, String rag) {
    }

    record NameRow(long id, String name, Long createdBy) {
    }

    private static final String CONTACT_COLUMNS = """
            SELECT ct.ob_client_id    AS obClientId,
                   ct.id              AS id,
                   ct.name            AS name,
                   ct.designation     AS designation,
                   ct.email           AS email,
                   ct.phone           AS phone,
                   ct.whatsapp_opt_in AS whatsappOptIn,
                   ct.is_primary      AS isPrimary,
                   ct.is_active       AS isActive
              FROM ob_client_contacts ct
            """;

    private static final RowMapper<ListRow> LIST_MAPPER = (rs, n) -> listRow(rs);

    private static final RowMapper<DetailRow> DETAIL_MAPPER = (rs, n) -> new DetailRow(
            listRow(rs),
            rs.getString("description"),
            rs.getString("address"),
            rs.getString("licenseType"),
            rs.getString("statusReason"),
            rs.getBytes("panCiphertext"),
            nullableLong(rs, "createdBy"),
            rs.getString("createdByName"),
            instant(rs, "createdAt"));

    private static final RowMapper<ProductRow> PRODUCT_MAPPER = (rs, n) -> new ProductRow(
            rs.getLong("obClientId"), rs.getLong("id"), rs.getString("code"), rs.getString("name"));

    private static final RowMapper<ContactRow> CONTACT_MAPPER = (rs, n) -> new ContactRow(
            rs.getLong("obClientId"), rs.getLong("id"), rs.getString("name"), rs.getString("designation"),
            rs.getString("email"), rs.getString("phone"), rs.getBoolean("whatsappOptIn"),
            rs.getBoolean("isPrimary"), rs.getBoolean("isActive"));

    private static final RowMapper<ApplicationRow> APPLICATION_MAPPER = (rs, n) -> new ApplicationRow(
            rs.getLong("id"), rs.getString("licenseType"), nullableInt(rs, "units"),
            localDate(rs, "licenseStart"), localDate(rs, "licenseEnd"),
            rs.getLong("productId"), rs.getString("productCode"), rs.getString("productName"));

    private static final RowMapper<JourneyRow> JOURNEY_MAPPER = (rs, n) -> new JourneyRow(
            rs.getLong("id"), rs.getString("gateStatus"), nullableLong(rs, "blockedByJourneyId"),
            rs.getLong("productId"), rs.getString("productCode"), rs.getString("productName"),
            rs.getInt("stepCount"), rs.getInt("stepsSettled"), rs.getInt("totalTatDays"),
            rs.getString("rag"));

    private static final RowMapper<StepDotRow> STEP_DOT_MAPPER = (rs, n) -> new StepDotRow(
            rs.getLong("id"), rs.getLong("journeyId"), rs.getInt("sequence"), rs.getString("name"),
            rs.getString("status"), nullableLong(rs, "dependsOnStepId"), rs.getString("rag"));

    private static final RowMapper<NameRow> NAME_MAPPER = (rs, n) -> new NameRow(
            rs.getLong("id"), rs.getString("name"), nullableLong(rs, "createdBy"));

    private static ListRow listRow(ResultSet rs) throws SQLException {
        return new ListRow(
                rs.getLong("id"),
                rs.getString("name"),
                localDate(rs, "onboardingDate"),
                rs.getString("status"),
                instant(rs, "liveAt"),
                nullableLong(rs, "salesPersonId"),
                rs.getString("salesPersonName"),
                rs.getString("rag"),
                rs.getString("gateStatus"),
                rs.getInt("journeyCount"),
                rs.getInt("journeysComplete"),
                rs.getBoolean("hasPortalLogin"));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static LocalDate localDate(ResultSet rs, String column) throws SQLException {
        Date value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * A cursor that does not decode is treated as no cursor.
     *
     * <p>{@code ObEscalationReadRepository}'s own choice: the alternative is a
     * 400 on a value the caller never composed by hand, and the first page is
     * an answer the client can recover from.
     */
    private static Cursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            Cursor decoded = Cursor.decode(cursor);
            LocalDate.parse(decoded.sortKey());
            return decoded;
        } catch (IllegalArgumentException | DateTimeParseException e) {
            return null;
        }
    }
}
