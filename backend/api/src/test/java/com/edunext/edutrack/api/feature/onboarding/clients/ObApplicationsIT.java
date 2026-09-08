package com.edunext.edutrack.api.feature.onboarding.clients;

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

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B-104 · the purchases panel against real MySQL.
 *
 * <p>What earns a container, given that {@code ObApplicationServiceTest} covers
 * every decision made in Java:
 *
 * <ol>
 *   <li><b>That a purchase really does leave a journey behind it.</b> The unit
 *       test can only prove {@code instantiate} was called. Whether that
 *       produced a journey, with steps, pinned to the right template, is a
 *       question about six tables and C-103's service — and it is the whole
 *       point of the operation.</li>
 *   <li><b>{@code uq_ob_client_applications}.</b> "One purchase per product per
 *       client" is a database fact, and the service check is the good error
 *       message rather than the thing that is true under a race.</li>
 *   <li><b>{@code ck_ob_client_applications_licence_window} and
 *       {@code ck_ob_client_applications_units}.</b> The service refuses both;
 *       this proves the columns would have refused them too, which is what makes
 *       the guarantee survive a caller that skips the service.</li>
 *   <li><b>{@code fk_ob_journeys_application} is {@code RESTRICT}.</b> The
 *       single most load-bearing fact in this feature — it is why there is no
 *       {@code DELETE} route. An assumption nobody has exercised is an
 *       assumption, and if a future migration ever adds a cascade, the reasoning
 *       in {@code ObApplicationService} silently stops being true.</li>
 *   <li><b>That the whole client document comes back changed</b>, journey strip
 *       included. The write answers with {@code ObClientDetail}, assembled by
 *       six statements this test is the only thing running end to end.</li>
 * </ol>
 *
 * <p>Fixtures use usernames and product codes no seed migration will claim, on
 * {@code ObClientsIT}'s own precedent.
 */
@SpringBootTest
@Testcontainers
class ObApplicationsIT {

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
    ObClientWriteService clientWrites;

    @Autowired
    ObApplicationService applications;

    @Autowired
    JdbcTemplate jdbc;

    /** One suffix per test — nothing here is deleted, on {@code ObContactsIT}'s reasoning. */
    private static final AtomicInteger RUN = new AtomicInteger();

    private long ayush;
    private long clientId;
    private long boughtProduct;
    private long boughtApplicationId;
    private long secondProduct;
    private long retiredProduct;
    private long templatelessProduct;
    private ObClientScope admin;

    /**
     * A client boarded with one product, plus three more products standing by:
     * one sellable with a template, one retired, one with no template.
     *
     * <p>Three spares rather than one because each is a different refusal, and a
     * test that had to mutate {@code ob_products} to reach the next one would be
     * changing the schema's contents underneath its siblings — the suite shares
     * a container.
     */
    @BeforeEach
    void seed() {
        int run = RUN.incrementAndGet();

        ayush = insertUser("it_obapp_" + run);
        admin = new ObClientScope(ObClientScope.OB_ADMIN, ayush);

        boughtProduct = insertProduct("ITAPP_A_" + run, "IT App Product A " + run, true);
        insertTemplate(boughtProduct, "IT App Onboarding A " + run);

        secondProduct = insertProduct("ITAPP_B_" + run, "IT App Product B " + run, true);
        insertTemplate(secondProduct, "IT App Onboarding B " + run);

        retiredProduct = insertProduct("ITAPP_R_" + run, "IT App Retired " + run, false);
        insertTemplate(retiredProduct, "IT App Onboarding R " + run);

        // No template at all — the other half of the two refusals an add makes.
        templatelessProduct = insertProduct("ITAPP_T_" + run, "IT App Templateless " + run, true);

        ObClientDtos.ObClientDetail client = clientWrites.create(admin, ayush,
                new ObClientDtos.ObClientCreateRequest(
                        "IT App Client " + run, null, BOARDED, null, null, null, null,
                        List.of(new ObClientDtos.ObContactWriteRequest(
                                "Founding SPOC", "Principal", "appfounder" + run + "@example.com",
                                "+911111111111", false, null, true)),
                        List.of(new ObClientDtos.ObApplicationWriteRequest(
                                boughtProduct, "ANNUAL", 10, BOARDED, BOARDED.plusYears(1))),
                        List.of(),
                        // acknowledgeSimilarNames, for the reason ObContactsIT
                        // gives: "IT App Client 1", "2", "3" … are exactly what
                        // B-102's fuzzy name guard exists to stop, and that is
                        // its own test's subject rather than this one's.
                        false, true));

        clientId = client.id();
        boughtApplicationId = client.applications().getFirst().id();
    }

    // ── the add, and the journey it exists to produce ───────────────────────

    /**
     * The operation's whole reason for existing, and the one thing a mock cannot
     * assert: not that {@code instantiate} was called, but that a journey with
     * steps now stands behind the purchase.
     */
    @Test
    @DisplayName("adding a purchase instantiates its journey, with the template's steps")
    void addingAPurchaseInstantiatesItsJourney() {
        ObClientDtos.ObClientDetail after = applications.add(admin, clientId,
                purchase(secondProduct, "ANNUAL", 25, BOARDED, BOARDED.plusYears(1)));

        assertThat(after.applications()).hasSize(2);
        assertThat(after.journeys()).hasSize(2);

        assertThat(count("SELECT COUNT(*) FROM ob_journeys WHERE ob_client_id = " + clientId
                + " AND product_id = " + secondProduct)).isEqualTo(1);
        // The two steps insertTemplate writes. A journey with no steps is a
        // client being onboarded through nothing, which is the state the purchase
        // was supposed to prevent rather than produce.
        assertThat(count("""
                SELECT COUNT(*) FROM ob_journey_steps s
                  JOIN ob_journeys j ON j.id = s.journey_id
                 WHERE j.ob_client_id = %d AND j.product_id = %d
                """.formatted(clientId, secondProduct))).isEqualTo(2);
    }

    /**
     * The licence window round-trips through {@code DATE} unshifted — <b>on both
     * sides</b>, which is the assertion that earned its keep.
     *
     * <p>The two halves are checked separately on purpose, because when this test
     * was written they disagreed. The raw read passed and the read through
     * {@code ObClientDetail} came back <b>a day early</b>: {@code
     * ObClientReadRepository.localDate} was {@code rs.getDate(..).toLocalDate()},
     * which renders an instant through the JVM default zone, so a date stored in
     * a UTC database and read on an IST machine lost a day. That is A-067's
     * defect, already fixed with a comment in {@code TicketReportRepository},
     * {@code ReportScheduleRepository} and {@code WidgetRepository}; B-102's
     * repository was the last one carrying it, and nothing had noticed because no
     * assertion anywhere compared a date written through this package against the
     * same date read back out of it. It was wrong for {@code onboardingDate} too.
     *
     * <p>{@code ObContactWriteRepository.Consent.atTimestamp} is the write-side
     * counterpart of the same family of bug, on a {@code DATETIME(6)}. A
     * {@code DATE} has no instant and must not be given one.
     */
    @Test
    @DisplayName("the licence window survives the round trip to DATE, read either way")
    void theLicenceWindowRoundTrips() {
        LocalDate start = LocalDate.of(2026, 10, 1);
        LocalDate end = LocalDate.of(2027, 9, 30);

        ObClientDtos.ObClientDetail after =
                applications.add(admin, clientId, purchase(secondProduct, "ANNUAL", 25, start, end));

        assertThat(jdbc.queryForObject("""
                SELECT license_start FROM ob_client_applications
                 WHERE ob_client_id = ? AND product_id = ?
                """, LocalDate.class, clientId, secondProduct)).isEqualTo(start);
        assertThat(jdbc.queryForObject("""
                SELECT license_end FROM ob_client_applications
                 WHERE ob_client_id = ? AND product_id = ?
                """, LocalDate.class, clientId, secondProduct)).isEqualTo(end);

        ObClientDtos.ObApplication read = after.applications().stream()
                .filter(a -> a.product().id() == secondProduct)
                .findFirst()
                .orElseThrow();
        assertThat(read.licenseStart()).isEqualTo(start);
        assertThat(read.licenseEnd()).isEqualTo(end);
    }

    /** The same shift, on the column every OB-03 row and every OB-05 header prints. */
    @Test
    @DisplayName("onboardingDate reads back as the day it was boarded on")
    void theOnboardingDateDoesNotShift() {
        ObClientDtos.ObClientDetail after = applications.add(admin, clientId, purchase(secondProduct));

        assertThat(after.onboardingDate()).isEqualTo(BOARDED);
    }

    /**
     * The service refuses this with a 409 and never reaches the index. Asserted
     * against MySQL so the guarantee survives a caller that skips the service —
     * a fixture, an import, or the next feature in this package.
     */
    @Test
    @DisplayName("uq_ob_client_applications refuses a second row for one product")
    void theUniqueIndexRefusesASecondPurchaseOfOneProduct() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO ob_client_applications (ob_client_id, product_id, units)
                VALUES (?, ?, 5)
                """, clientId, boughtProduct))
                .hasMessageContaining("uq_ob_client_applications");
    }

    @Test
    @DisplayName("the service refuses the duplicate first, and names the purchase to edit")
    void theServiceRefusesTheDuplicateWithSomethingToDo() {
        assertThatThrownBy(() -> applications.add(admin, clientId,
                purchase(boughtProduct, "ANNUAL", 500, BOARDED, BOARDED.plusYears(1))))
                .isInstanceOfSatisfying(DuplicateApplicationProductException.class,
                        e -> assertThat(e.existingApplicationId()).isEqualTo(boughtApplicationId));

        // And nothing was written on the way to the refusal.
        assertThat(count("SELECT COUNT(*) FROM ob_client_applications WHERE ob_client_id = "
                + clientId)).isEqualTo(1);
    }

    @Test
    @DisplayName("a retired product cannot be bought, and a templateless one cannot either")
    void bothProductGuardsHold() {
        assertThatThrownBy(() -> applications.add(admin, clientId, purchase(retiredProduct)))
                .isInstanceOf(ObClientValidationException.class);
        assertThatThrownBy(() -> applications.add(admin, clientId, purchase(templatelessProduct)))
                .isInstanceOf(ProductWithoutTemplateException.class);

        assertThat(count("SELECT COUNT(*) FROM ob_client_applications WHERE ob_client_id = "
                + clientId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM ob_journeys WHERE ob_client_id = "
                + clientId)).isEqualTo(1);
    }

    // ── the two CHECK constraints ───────────────────────────────────────────

    @Test
    @DisplayName("ck_ob_client_applications_licence_window refuses an inverted window")
    void theCheckConstraintRefusesAnInvertedWindow() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO ob_client_applications
                    (ob_client_id, product_id, license_start, license_end)
                VALUES (?, ?, '2027-01-01', '2026-12-31')
                """, clientId, secondProduct))
                .hasMessageContaining("ck_ob_client_applications_licence_window");
    }

    @Test
    @DisplayName("ck_ob_client_applications_units refuses zero seats")
    void theCheckConstraintRefusesZeroSeats() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO ob_client_applications (ob_client_id, product_id, units)
                VALUES (?, ?, 0)
                """, clientId, secondProduct))
                .hasMessageContaining("ck_ob_client_applications_units");
    }

    // ── the edit, which is what a renewal is ────────────────────────────────

    @Test
    @DisplayName("a renewal moves the end date and leaves the journey alone")
    void aRenewalMovesTheEndDate() {
        LocalDate renewed = BOARDED.plusYears(2);

        ObClientDtos.ObClientDetail after = applications.update(admin, clientId, boughtApplicationId,
                purchase(boughtProduct, "ANNUAL", 10, BOARDED, renewed));

        assertThat(after.applications().getFirst().licenseEnd()).isEqualTo(renewed);
        // An edit is an edit. A second journey for one product would be refused
        // by uq_ob_journeys_client_product anyway; this proves none was attempted.
        assertThat(count("SELECT COUNT(*) FROM ob_journeys WHERE ob_client_id = "
                + clientId)).isEqualTo(1);
    }

    /**
     * A product retired after it was sold is the case this protects: its clients
     * are still onboarding through it, and refusing to renew their licence would
     * make a retirement retroactively strand everyone who already bought it.
     */
    @Test
    @DisplayName("a purchase of a since-retired product can still be renewed")
    void aRetiredProductsPurchaseCanStillBeEdited() {
        jdbc.update("UPDATE ob_products SET is_active = 0 WHERE id = ?", boughtProduct);

        ObClientDtos.ObClientDetail after = applications.update(admin, clientId, boughtApplicationId,
                purchase(boughtProduct, "ANNUAL", 40, BOARDED, BOARDED.plusYears(3)));

        assertThat(after.applications().getFirst().units()).isEqualTo(40);
    }

    @Test
    @DisplayName("a PATCH naming a different product is refused, and writes nothing")
    void repointingIsRefused() {
        assertThatThrownBy(() -> applications.update(admin, clientId, boughtApplicationId,
                purchase(secondProduct, "ANNUAL", 10, BOARDED, BOARDED.plusYears(1))))
                .isInstanceOf(ApplicationProductImmutableException.class);

        assertThat(jdbc.queryForObject(
                "SELECT product_id FROM ob_client_applications WHERE id = ?",
                Long.class, boughtApplicationId)).isEqualTo(boughtProduct);
    }

    // ── the fact that makes there be no DELETE ──────────────────────────────

    /**
     * {@code fk_ob_journeys_application} has no {@code ON DELETE} clause, so it
     * is {@code RESTRICT} — and every purchase carries a journey from the moment
     * it is made. That pair is the entire argument for why B-104 ships two routes
     * and not three, and it is an argument that stops being true the day somebody
     * adds a cascade to that key.
     *
     * <p>So it is exercised rather than asserted in prose. If this test ever goes
     * green in the other direction, {@code ObApplicationService}'s class javadoc
     * is wrong and a {@code DELETE} route becomes buildable.
     */
    @Test
    @DisplayName("a purchase with a journey behind it cannot be deleted — which is why there is no DELETE")
    void theForeignKeyRefusesDeletingAPurchaseWithAJourney() {
        assertThat(count("SELECT COUNT(*) FROM ob_journeys WHERE ob_client_id = " + clientId
                + " AND product_id = " + boughtProduct)).isEqualTo(1);

        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM ob_client_applications WHERE id = ?", boughtApplicationId))
                .hasMessageContaining("fk_ob_journeys_application");
    }

    /**
     * And archiving the journey does not help, which is the half somebody would
     * reach for next. {@code archived_at} clears {@code live_key} and leaves the
     * row exactly where it is, so {@code RESTRICT} refuses just the same.
     */
    @Test
    @DisplayName("archiving the journey does not release the purchase either")
    void archivingDoesNotReleaseThePurchase() {
        jdbc.update("UPDATE ob_journeys SET archived_at = NOW(6) WHERE ob_client_id = ? "
                + "AND product_id = ?", clientId, boughtProduct);

        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM ob_client_applications WHERE id = ?", boughtApplicationId))
                .hasMessageContaining("fk_ob_journeys_application");
    }

    // ── the response ────────────────────────────────────────────────────────

    /**
     * The write answers the whole client document rather than the purchase it
     * wrote. {@code ObClientETag} hashes that document, journeys included, so a
     * purchase-shaped response would leave every other card on OB-05 editing
     * against a tag that is already stale.
     */
    @Test
    @DisplayName("every purchase write answers the whole client document")
    void theWriteAnswersTheWholeDocument() {
        ObClientDtos.ObClientDetail after = applications.add(admin, clientId, purchase(secondProduct));

        assertThat(after.id()).isEqualTo(clientId);
        assertThat(after.contacts()).isNotEmpty();
        assertThat(after.journeys()).hasSize(2);
        assertThat(after.products()).hasSize(2);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static ObClientDtos.ObApplicationWriteRequest purchase(long productId) {
        return purchase(productId, "ANNUAL", 10, BOARDED, BOARDED.plusYears(1));
    }

    private static ObClientDtos.ObApplicationWriteRequest purchase(
            long productId, String licenseType, Integer units, LocalDate start, LocalDate end) {
        return new ObClientDtos.ObApplicationWriteRequest(productId, licenseType, units, start, end);
    }

    private long insertUser(String username) {
        Long roleId = jdbc.queryForObject("SELECT id FROM roles ORDER BY id LIMIT 1", Long.class);
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id)
                VALUES (?, ?, ?, 'not-a-real-hash', ?, ?)
                """, username, username, username + "@example.com", username, roleId);
        return idOfLastInsert();
    }

    private long insertProduct(String code, String name, boolean active) {
        jdbc.update("INSERT INTO ob_products (code, name, is_active) VALUES (?, ?, ?)",
                code, name, active);
        return idOfLastInsert();
    }

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
