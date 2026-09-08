package com.edunext.edutrack.api.feature.onboarding.clients;

import com.edunext.edutrack.api.feature.onboarding.ObStepRag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B-102 · the client master against real MySQL.
 *
 * <p>What earns a container, given that {@code ObClientWriteServiceTest} covers
 * every decision made in Java:
 *
 * <ol>
 *   <li><b>The atomicity of the create.</b> Five tables and C-103's
 *       instantiation service in one transaction — the property the contract
 *       calls "the module's widest side effect" cannot be asserted against
 *       mocks.</li>
 *   <li><b>That {@code uq_ob_clients_pan_blind} fires.</b> The service check is
 *       the good error message; the index is the thing that is true under a
 *       race. A-113's {@code PanStorageIT} proves the constraint in isolation;
 *       this proves the feature actually writes the column it guards.</li>
 *   <li><b>The scope predicate in SQL.</b> A-112's rule is expressed twice —
 *       once as a JPA specification, once as the SQL in {@link ObClientScope} —
 *       and only one of them is exercised by this feature.</li>
 *   <li><b>That the SQL and Java RAG formulas agree.</b>
 *       {@code ObStepRag.worstOverSteps} exists because the list has to filter
 *       in the {@code WHERE} clause; its own javadoc names this test as the
 *       thing standing between that and two formulas that drift.</li>
 * </ol>
 *
 * <p>Fixtures use usernames and product codes no seed migration will claim, for
 * the reason {@code AuthLoginIT} records.
 */
@SpringBootTest
@Testcontainers
class ObClientsIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_it")
            .withCommand(
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                    "--default-time-zone=+00:00",
                    "--sql-mode=ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,"
                            + "ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION",
                    "--log-bin-trust-function-creators=1")
            .withUrlParam("allowPublicKeyRetrieval", "true")
            .withUrlParam("useSSL", "false")
            .withUrlParam("connectionTimeZone", "UTC");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
        registry.add("spring.flyway.user", MYSQL::getUsername);
        registry.add("spring.flyway.password", MYSQL::getPassword);
    }

    private static final LocalDate BOARDED = LocalDate.of(2026, 9, 7);

    @Autowired
    ObClientWriteService writes;

    @Autowired
    ObClientService reads;

    @Autowired
    JdbcTemplate jdbc;

    private long ayush;
    private long divyansh;
    private long product;
    private long secondProduct;

    private ObClientScope admin;
    private ObClientScope salesAyush;
    private ObClientScope salesDivyansh;

    @BeforeEach
    void seed() {
        // Children first, then their parents — every one of these references
        // the row below it.
        jdbc.update("DELETE FROM ob_journey_steps WHERE journey_id IN "
                + "(SELECT id FROM ob_journeys WHERE ob_client_id IN "
                + "(SELECT id FROM ob_clients WHERE name LIKE 'IT %'))");
        jdbc.update("DELETE FROM ob_journeys WHERE ob_client_id IN "
                + "(SELECT id FROM ob_clients WHERE name LIKE 'IT %')");
        jdbc.update("DELETE FROM ob_client_requirements WHERE ob_client_id IN "
                + "(SELECT id FROM ob_clients WHERE name LIKE 'IT %')");
        jdbc.update("DELETE FROM ob_client_applications WHERE ob_client_id IN "
                + "(SELECT id FROM ob_clients WHERE name LIKE 'IT %')");
        jdbc.update("DELETE FROM ob_client_contacts WHERE ob_client_id IN "
                + "(SELECT id FROM ob_clients WHERE name LIKE 'IT %')");
        jdbc.update("DELETE FROM ob_clients WHERE name LIKE 'IT %'");
        jdbc.update("DELETE FROM ob_journey_template_steps WHERE template_id IN "
                + "(SELECT id FROM ob_journey_templates WHERE name LIKE 'IT %')");
        jdbc.update("DELETE FROM ob_journey_templates WHERE name LIKE 'IT %'");
        jdbc.update("DELETE FROM ob_products WHERE code LIKE 'IT_%'");
        jdbc.update("DELETE FROM users WHERE username LIKE 'it_obclient_%'");

        ayush = insertUser("it_obclient_ayush");
        divyansh = insertUser("it_obclient_divyansh");

        product = insertProduct("IT_ERP", "IT ERP");
        secondProduct = insertProduct("IT_LMS", "IT LMS");
        insertTemplate(product, "IT ERP Onboarding");
        insertTemplate(secondProduct, "IT LMS Onboarding");

        admin = new ObClientScope(ObClientScope.OB_ADMIN, ayush);
        salesAyush = new ObClientScope(ObClientScope.OB_SALES, ayush);
        salesDivyansh = new ObClientScope(ObClientScope.OB_SALES, divyansh);
    }

    // ── the create, whole or not at all ─────────────────────────────────────

    @Test
    @DisplayName("one request boards the client, its SPOCs, its purchases, its requirements and a locked journey per product")
    void oneRequestWritesEverything() {
        ObClientDtos.ObClientDetail created = writes.create(admin, ayush,
                request("IT Horizon Academy", null, List.of(product, secondProduct)));

        assertThat(created.name()).isEqualTo("IT Horizon Academy");
        assertThat(created.contacts()).hasSize(1);
        assertThat(created.applications()).hasSize(2);
        // B-106 · rows rather than strings, in the order they were entered,
        // each one having been through §3.9's allow-list on the way in.
        assertThat(created.requirements())
                .extracting(ObClientDtos.ObRequirement::bodyText)
                .containsExactly("Single sign-on", "Data migration");
        assertThat(created.requirements())
                .extracting(ObClientDtos.ObRequirement::sequence)
                .containsExactly(0, 1);
        assertThat(created.requirements()).allSatisfy(requirement -> {
            assertThat(requirement.isMet()).isFalse();
            assertThat(requirement.metAt()).isNull();
            assertThat(requirement.createdBy()).isNotNull();
        });
        assertThat(created.journeys()).hasSize(2);
        assertThat(created.journeys()).allSatisfy(journey ->
                assertThat(journey.gateStatus()).isEqualTo("LOCKED"));

        // Plan §5.2: steps visible from day one, clocks dead. Every step is
        // PENDING and no due date has been set, so nothing can breach before
        // the client has been asked for anything.
        assertThat(created.journeys().getFirst().steps()).hasSize(2);
        assertThat(created.journeys().getFirst().steps())
                .allSatisfy(step -> assertThat(step.status()).isEqualTo("PENDING"));
        assertThat(created.journeys().getFirst().percentComplete()).isZero();
        assertThat(created.journeys().getFirst().totalTatDays()).isEqualTo(5);
    }

    /**
     * The atomicity claim, tested at the one point where it is not obvious:
     * the journeys are instantiated last, so a failure there has to unwind four
     * tables' worth of writes.
     */
    @Test
    @DisplayName("a product with no published template boards nothing at all")
    void productWithoutTemplateRollsEverythingBack() {
        jdbc.update("UPDATE ob_journey_templates SET is_active = 0 WHERE product_id = ?", product);

        assertThatThrownBy(() -> writes.create(admin, ayush,
                request("IT Rollback Academy", null, List.of(product))))
                .isInstanceOf(ProductWithoutTemplateException.class);

        assertThat(count("SELECT COUNT(*) FROM ob_clients WHERE name = 'IT Rollback Academy'"))
                .isZero();
    }

    @Test
    @DisplayName("a client is readable by the person who boarded it and by nobody else in Sales")
    void createdByIsTheSalesScope() {
        ObClientDtos.ObClientDetail created = writes.create(salesAyush, ayush,
                request("IT Bluebell Schools", null, List.of(product)));

        assertThat(reads.findDetail(salesAyush, created.id())).isPresent();
        // Not 403 and not an empty field — absent, which is what the 404 rule
        // needs the read to produce.
        assertThat(reads.findDetail(salesDivyansh, created.id())).isEmpty();
        assertThat(reads.list(salesDivyansh, null, null, null, null, null, null, null, 50).data())
                .noneSatisfy(row -> assertThat(row.name()).isEqualTo("IT Bluebell Schools"));
    }

    // ── the PAN guard ───────────────────────────────────────────────────────

    @Test
    @DisplayName("a second client with the same PAN is refused, whatever case it was typed in")
    void panIsUniqueAcrossClients() {
        writes.create(admin, ayush, request("IT Trinity College", "ABCDE1234F", List.of(product)));

        assertThatThrownBy(() -> writes.create(admin, ayush,
                request("IT Trinity Institute", "ABCDE1234F", List.of(product))))
                .isInstanceOf(DuplicateClientPanException.class);

        assertThat(count("SELECT COUNT(*) FROM ob_clients WHERE name LIKE 'IT Trinity%'")).isEqualTo(1);
    }

    /**
     * The constraint, not the service check.
     *
     * <p>The whole two-column design rests on this index firing, and the design
     * it replaced — a UNIQUE over randomised ciphertext — would "apply cleanly,
     * look exactly like a working constraint, and silently never fire". So the
     * assertion is that a second row carrying the same blind index is refused
     * by MySQL with the service out of the way entirely.
     */
    @Test
    @DisplayName("the UNIQUE index behind the guard is real, not just the service check")
    void theBlindIndexConstraintFires() {
        ObClientDtos.ObClientDetail first = writes.create(admin, ayush,
                request("IT Meridian School", "ZYXWV9876E", List.of(product)));

        byte[] index = jdbc.queryForObject(
                "SELECT pan_blind_index FROM ob_clients WHERE id = ?", byte[].class, first.id());
        assertThat(index).hasSize(32);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO ob_clients (name, onboarding_date, pan_ciphertext, pan_blind_index)
                VALUES ('IT Meridian Twin', '2026-09-07', ?, ?)
                """, new byte[]{1, 2, 3}, index))
                .hasMessageContaining("uq_ob_clients_pan_blind");
    }

    @Test
    @DisplayName("the PAN on the detail read is masked to the last four, for every role")
    void panIsMasked() {
        ObClientDtos.ObClientDetail created = writes.create(admin, ayush,
                request("IT Masked Academy", "ABCDE1234F", List.of(product)));

        assertThat(created.pan()).endsWith("234F").doesNotContain("ABCDE");
    }

    // ── the name guard ──────────────────────────────────────────────────────

    @Test
    @DisplayName("a near-duplicate name warns once and proceeds when acknowledged")
    void similarNamesWarnThenProceed() {
        writes.create(admin, ayush, request("IT Acme Private Limited", null, List.of(product)));

        assertThatThrownBy(() -> writes.create(admin, ayush,
                request("IT Acme Pvt Ltd", null, List.of(secondProduct))))
                .isInstanceOf(SimilarClientNameException.class);

        ObClientDtos.ObClientCreateRequest acknowledged = acknowledge(
                request("IT Acme Pvt Ltd", null, List.of(secondProduct)));
        assertThat(writes.create(admin, ayush, acknowledged).name()).isEqualTo("IT Acme Pvt Ltd");
    }

    // ── the edit ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("LIVE is refused, and a hold is recorded with its reason")
    void statusRules() {
        ObClientDtos.ObClientDetail created = writes.create(admin, ayush,
                request("IT Status Academy", null, List.of(product)));

        ObClientUpdateRequest live = new ObClientUpdateRequest();
        live.setStatus("LIVE");
        assertThatThrownBy(() -> writes.update(admin, created.id(), live))
                .isInstanceOf(LiveStatusNotEarnedException.class);

        ObClientUpdateRequest hold = new ObClientUpdateRequest();
        hold.setStatus("ON_HOLD");
        hold.setStatusReason("Waiting on the client's data extract");
        ObClientDtos.ObClientDetail held = writes.update(admin, created.id(), hold);

        assertThat(held.status()).isEqualTo("ON_HOLD");
        assertThat(held.statusReason()).isEqualTo("Waiting on the client's data extract");

        // Lifting the hold clears the reason, or OB-05 would keep printing why
        // a client that is no longer held was held.
        ObClientUpdateRequest resume = new ObClientUpdateRequest();
        resume.setStatus("ONBOARDING");
        assertThat(writes.update(admin, created.id(), resume).statusReason()).isNull();
    }

    @Test
    @DisplayName("a client out of scope cannot be edited, and is not admitted to exist")
    void editingIsScoped() {
        ObClientDtos.ObClientDetail created = writes.create(salesAyush, ayush,
                request("IT Scoped Academy", null, List.of(product)));

        ObClientUpdateRequest rename = new ObClientUpdateRequest();
        rename.setName("IT Renamed By Somebody Else");

        assertThatThrownBy(() -> writes.update(salesDivyansh, created.id(), rename))
                .isInstanceOf(ObClientNotFoundException.class);
    }

    // ── RAG ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a client whose journeys are all locked has no colour — that is a gate state, not a colour")
    void lockedClientsHaveNoRag() {
        ObClientDtos.ObClientDetail created = writes.create(admin, ayush,
                request("IT Locked Academy", null, List.of(product)));

        assertThat(created.rag()).isNull();
        assertThat(created.gateStatus()).isEqualTo("LOCKED");
    }

    /**
     * The assertion {@code ObStepRag.worstOverSteps}' own javadoc names.
     *
     * <p>Three steps, one per colour, each read back through the SQL formula
     * and compared against {@link ObStepRag#of} over the same values. Every
     * instant is minutes away from a boundary, so the two clocks — Java's
     * milliseconds and MySQL's microseconds — cannot disagree by rounding.
     */
    @Test
    @DisplayName("the SQL and Java RAG formulas answer the same for the same rows")
    void sqlAndJavaRagAgree() {
        ObClientDtos.ObClientDetail created = writes.create(admin, ayush,
                request("IT Colour Academy", null, List.of(product)));
        long journeyId = created.journeys().getFirst().id();
        jdbc.update("UPDATE ob_journeys SET gate_status = 'OPEN', gate_opened_at = NOW(6) WHERE id = ?",
                journeyId);

        Instant now = Instant.now();
        Instant green = now.plus(10, ChronoUnit.HOURS);
        Instant amber = now.plus(1, ChronoUnit.HOURS);
        Instant red = now.minus(2, ChronoUnit.HOURS);
        Instant startedTenHoursAgo = now.minus(10, ChronoUnit.HOURS);

        setStepClock(journeyId, 1, startedTenHoursAgo, green);
        setStepClock(journeyId, 2, startedTenHoursAgo, amber);

        assertThat(sqlColourOf(journeyId, 1)).isEqualTo(ObStepRag.of(startedTenHoursAgo, green, now));
        assertThat(sqlColourOf(journeyId, 2)).isEqualTo(ObStepRag.of(startedTenHoursAgo, amber, now));

        // Worst wins: one AMBER step among GREEN ones colours the client AMBER.
        assertThat(detailOf(created.id()).rag()).isEqualTo(ObStepRag.AMBER);

        setStepClock(journeyId, 1, startedTenHoursAgo, red);
        assertThat(sqlColourOf(journeyId, 1)).isEqualTo(ObStepRag.of(startedTenHoursAgo, red, now));
        assertThat(detailOf(created.id()).rag()).isEqualTo(ObStepRag.RED);
    }

    @Test
    @DisplayName("the list's rag filter and the row's own colour are the same answer")
    void ragFilterAgreesWithTheColumn() {
        ObClientDtos.ObClientDetail created = writes.create(admin, ayush,
                request("IT Filtered Academy", null, List.of(product)));
        long journeyId = created.journeys().getFirst().id();
        jdbc.update("UPDATE ob_journeys SET gate_status = 'OPEN', gate_opened_at = NOW(6) WHERE id = ?",
                journeyId);
        setStepClock(journeyId, 1, Instant.now().minus(10, ChronoUnit.HOURS),
                Instant.now().minus(2, ChronoUnit.HOURS));

        List<ObClientDtos.ObClientSummary> red =
                reads.list(admin, "IT Filtered", null, ObStepRag.RED, null, null, null, null, 50).data();
        List<ObClientDtos.ObClientSummary> green =
                reads.list(admin, "IT Filtered", null, ObStepRag.GREEN, null, null, null, null, 50).data();

        assertThat(red).extracting(ObClientDtos.ObClientSummary::name)
                .containsExactly("IT Filtered Academy");
        assertThat(red.getFirst().rag()).isEqualTo(ObStepRag.RED);
        assertThat(green).isEmpty();
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private ObClientDtos.ObClientDetail detailOf(long id) {
        Optional<ObClientDtos.ObClientDetail> detail = reads.findDetail(admin, id);
        assertThat(detail).isPresent();
        return detail.orElseThrow();
    }

    private String sqlColourOf(long journeyId, int sequence) {
        return jdbc.queryForObject(
                "SELECT " + ObStepRag.colourOfStep("s")
                        + " FROM ob_journey_steps s WHERE s.journey_id = ? AND s.sequence = ?",
                String.class, journeyId, sequence);
    }

    private void setStepClock(long journeyId, int sequence, Instant startedAt, Instant dueAt) {
        jdbc.update("UPDATE ob_journey_steps SET started_at = ?, due_at = ?, status = 'IN_PROGRESS' "
                        + "WHERE journey_id = ? AND sequence = ?",
                Timestamp.from(startedAt), Timestamp.from(dueAt), journeyId, sequence);
    }

    private static ObClientDtos.ObClientCreateRequest request(String name, String pan,
                                                              List<Long> productIds) {
        return new ObClientDtos.ObClientCreateRequest(
                name, "Boarded by an integration test", BOARDED, pan, "12 Test Road", null, "ANNUAL",
                // B-103 · whatsappOptIn is false here, and it is not an
                // oversight. A consented SPOC opens ob_contact_consent_events,
                // which is insert-only by trigger and referenced without a
                // cascade — so the DELETE in seed() above could never tear this
                // client down again, and every test after the first would fail
                // in its fixture rather than in its subject. Consent capture is
                // ObContactsIT's subject; it builds a fresh client per test and
                // removes nothing, which is the shape any fixture touching a
                // consented contact has to take.
                List.of(new ObClientDtos.ObContactWriteRequest(
                        "IT SPOC", "Principal", "it.spoc@example.com", "+911234567890", false,
                        null, true)),
                productIds.stream()
                        .map(id -> new ObClientDtos.ObApplicationWriteRequest(
                                id, "ANNUAL", 100, BOARDED, BOARDED.plusYears(1)))
                        .toList(),
                List.of(new ObClientDtos.ObRequirementWriteRequest(null, "Single sign-on", null),
                        new ObClientDtos.ObRequirementWriteRequest(null, "Data migration", null)),
                false, false);
    }

    private static ObClientDtos.ObClientCreateRequest acknowledge(
            ObClientDtos.ObClientCreateRequest request) {
        return new ObClientDtos.ObClientCreateRequest(
                request.name(), request.description(), request.onboardingDate(), request.pan(),
                request.address(), request.salesPersonId(), request.licenseType(),
                request.contacts(), request.applications(), request.requirements(),
                false, true);
    }

    private long insertUser(String username) {
        Long roleId = jdbc.queryForObject("SELECT id FROM roles ORDER BY id LIMIT 1", Long.class);
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id)
                VALUES (?, ?, ?, 'not-a-real-hash', ?, ?)
                """, username, username, username + "@example.com", username, roleId);
        return idOfLastInsert();
    }

    private long insertProduct(String code, String name) {
        jdbc.update("INSERT INTO ob_products (code, name, is_active) VALUES (?, ?, 1)", code, name);
        return idOfLastInsert();
    }

    /** Two steps, five TAT days between them — enough for a strip and a roll-up. */
    private void insertTemplate(long productId, String name) {
        jdbc.update("""
                INSERT INTO ob_journey_templates (product_id, name, version, is_active, sequence)
                VALUES (?, ?, 1, 1, 0)
                """, productId, name);
        long templateId = idOfLastInsert();
        jdbc.update("""
                INSERT INTO ob_journey_template_steps (template_id, sequence, name, tat_days)
                VALUES (?, 1, 'Kickoff', 2), (?, 2, 'Configuration', 3)
                """, templateId, templateId);
    }

    private long idOfLastInsert() {
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? 0L : id;
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }
}
