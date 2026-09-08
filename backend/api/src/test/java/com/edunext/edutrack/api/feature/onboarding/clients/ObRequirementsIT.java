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
 * B-106 · the requirements list against real MySQL.
 *
 * <p>What earns a container, given that {@code ObRequirementServiceTest} covers
 * every decision made in Java:
 *
 * <ol>
 *   <li><b>{@code ck_ob_client_requirements_met}.</b> The service moves the flag
 *       and its stamp together; this proves the column would have refused them
 *       apart, which is what makes the rule survive a caller that skips the
 *       service. A guarantee only the service holds is a convention.</li>
 *   <li><b>That a requirement really takes nothing with it when deleted.</b>
 *       {@code ObRequirementService} and the contract both argue that a
 *       {@code DELETE} is safe here <em>because nothing references this
 *       table</em>, in direct contrast to a purchase. An assumption nobody has
 *       exercised is an assumption, and the day a migration adds a foreign key
 *       the reasoning silently stops being true.</li>
 *   <li><b>The declared column type, and a long multi-byte round trip.</b>
 *       {@code body_html} is {@code MEDIUMTEXT} so that §3.9's bound is a rule
 *       rather than the only thing standing between a long requirement and a
 *       column that truncates mid-tag. See {@link #aLongBodyRoundTrips} — the
 *       first draft of that test asserted the stronger claim and it was
 *       false.</li>
 *   <li><b>That the wizard's requirements are sanitised too.</b> The whole
 *       argument for taking the insert out of
 *       {@code ObClientChildWriteRepository} is that two write paths must reduce
 *       a body identically — and only an end-to-end create can show that the
 *       older path really does.</li>
 *   <li><b>{@code sequence} and the gaps a delete leaves.</b> The order OB-05
 *       prints is a fact about a {@code ORDER BY} over rows this test is the
 *       only thing running end to end.</li>
 * </ol>
 *
 * <p>Fixtures use usernames and product codes no seed migration will claim, on
 * {@code ObClientsIT}'s own precedent. Nothing is torn down — one suffix per
 * test, {@code ObContactsIT}'s reasoning.
 */
@SpringBootTest
@Testcontainers
class ObRequirementsIT {

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
    ObRequirementService requirements;

    @Autowired
    JdbcTemplate jdbc;

    private static final AtomicInteger RUN = new AtomicInteger();

    private long ayush;
    private long clientId;
    private ObClientScope admin;

    /** A client boarded with two requirements typed into the wizard's step four. */
    @BeforeEach
    void seed() {
        int run = RUN.incrementAndGet();

        ayush = insertUser("it_obreq_" + run);
        admin = new ObClientScope(ObClientScope.OB_ADMIN, ayush);

        long product = insertProduct("ITREQ_A_" + run, "IT Req Product A " + run);
        insertTemplate(product, "IT Req Onboarding A " + run);

        ObClientDtos.ObClientDetail client = clientWrites.create(admin, ayush,
                new ObClientDtos.ObClientCreateRequest(
                        "IT Req Client " + run, null, BOARDED, null, null, null, null,
                        List.of(new ObClientDtos.ObContactWriteRequest(
                                "Founding SPOC", "Principal", "reqfounder" + run + "@example.com",
                                "+911111111111", false, null, true)),
                        List.of(new ObClientDtos.ObApplicationWriteRequest(
                                product, "ANNUAL", 10, BOARDED, BOARDED.plusYears(1))),
                        List.of(new ObClientDtos.ObRequirementWriteRequest(
                                        "SSO", "<p>Against their <strong>Azure AD</strong></p>", null),
                                // Blank, and dropped rather than refused — B-102's
                                // behaviour, kept: a wizard textarea produces these
                                // by accident.
                                new ObClientDtos.ObRequirementWriteRequest(null, "   ", null),
                                new ObClientDtos.ObRequirementWriteRequest(
                                        null, "<p>Tally import for FY25-26</p>", null)),
                        // acknowledgeSimilarNames, on ObApplicationsIT's reason:
                        // "IT Req Client 1", "2", "3" … are what B-102's fuzzy
                        // name guard exists to stop, and that is its own subject.
                        false, true));

        clientId = client.id();
    }

    // ── the wizard's path, which is the older half of the sanitiser boundary ──

    /**
     * The whole reason the insert moved out of
     * {@code ObClientChildWriteRepository}.
     *
     * <p>Two write paths reduce a body identically or one of them is a hole. This
     * is the path that predates the sanitiser, so it is the one worth proving
     * end to end rather than asserting through a mock.
     */
    @Test
    @DisplayName("the wizard's requirements are sanitised, numbered, and blanks dropped")
    void theWizardWritesSanitisedRows() {
        ObClientDtos.ObClientDetail client = detail();

        assertThat(client.requirements()).hasSize(2);
        assertThat(client.requirements())
                .extracting(ObClientDtos.ObRequirement::sequence)
                .containsExactly(0, 1);
        assertThat(client.requirements().getFirst().title()).isEqualTo("SSO");
        assertThat(client.requirements().getFirst().bodyHtml())
                .contains("<strong>Azure AD</strong>");
        assertThat(client.requirements().getFirst().bodyText())
                .isEqualTo("Against their Azure AD");
        assertThat(client.requirements()).allSatisfy(requirement -> {
            assertThat(requirement.isMet()).isFalse();
            assertThat(requirement.metAt()).isNull();
            assertThat(requirement.metBy()).isNull();
            assertThat(requirement.createdBy()).isNotNull();
        });
    }

    /** Storing what arrived rather than what survived is the failure that renders as a working page. */
    @Test
    @DisplayName("script markup submitted through the wizard is not what reaches the column")
    void theWizardStoresTheSanitisedValue() {
        ObClientDtos.ObClientDetail after = requirements.add(admin, clientId,
                new ObClientDtos.ObRequirementWriteRequest(
                        null, "<p>Data migration<script>steal()</script></p>", null));

        String stored = jdbc.queryForObject(
                "SELECT body_html FROM ob_client_requirements WHERE id = ?", String.class,
                after.requirements().getLast().id());

        assertThat(stored).doesNotContain("script").contains("Data migration");
    }

    // ── sequence, and the gaps a delete leaves ──────────────────────────────

    @Test
    @DisplayName("a new requirement lands after every one already there, and a delete leaves the gap")
    void sequencingAndGaps() {
        ObClientDtos.ObClientDetail added = requirements.add(admin, clientId,
                new ObClientDtos.ObRequirementWriteRequest(null, "<p>Branding pack</p>", null));

        assertThat(added.requirements())
                .extracting(ObClientDtos.ObRequirement::sequence)
                .containsExactly(0, 1, 2);

        long middle = added.requirements().get(1).id();
        ObClientDtos.ObClientDetail afterDelete = requirements.delete(admin, clientId, middle);

        // 0 and 2 — the surviving rows keep the numbers they had. Renumbering
        // would rewrite every following row to tidy a column nobody reads.
        assertThat(afterDelete.requirements())
                .extracting(ObClientDtos.ObRequirement::sequence)
                .containsExactly(0, 2);

        // And the next add still lands last rather than reusing the freed 2 —
        // MAX(sequence) + 1, not a count.
        ObClientDtos.ObClientDetail afterReadd = requirements.add(admin, clientId,
                new ObClientDtos.ObRequirementWriteRequest(null, "<p>Handover doc</p>", null));
        assertThat(afterReadd.requirements())
                .extracting(ObClientDtos.ObRequirement::sequence)
                .containsExactly(0, 2, 3);
    }

    // ── the met stamp, and the constraint behind it ─────────────────────────

    @Test
    @DisplayName("marking one met stamps who and when, and un-marking clears both")
    void meetingAndUnmeeting() {
        long first = detail().requirements().getFirst().id();

        ObRequirementUpdateRequest met = new ObRequirementUpdateRequest();
        met.setIsMet(true);
        ObClientDtos.ObRequirement afterMet =
                requirements.update(admin, clientId, first, met).requirements().getFirst();

        assertThat(afterMet.isMet()).isTrue();
        assertThat(afterMet.metAt()).isNotNull();
        assertThat(afterMet.metBy()).isNotNull();
        assertThat(afterMet.metBy().id()).isEqualTo(ayush);

        ObRequirementUpdateRequest unmet = new ObRequirementUpdateRequest();
        unmet.setIsMet(false);
        ObClientDtos.ObRequirement afterUnmet =
                requirements.update(admin, clientId, first, unmet).requirements().getFirst();

        assertThat(afterUnmet.isMet()).isFalse();
        assertThat(afterUnmet.metAt()).isNull();
        assertThat(afterUnmet.metBy()).isNull();
    }

    /**
     * The rule the three-column design exists for, proved against the database
     * rather than against a captor.
     */
    @Test
    @DisplayName("an edit that leaves isMet alone does not re-date the stamp")
    void anUnrelatedEditDoesNotRestamp() {
        long first = detail().requirements().getFirst().id();

        ObRequirementUpdateRequest met = new ObRequirementUpdateRequest();
        met.setIsMet(true);
        java.time.Instant stamped =
                requirements.update(admin, clientId, first, met).requirements().getFirst().metAt();

        ObRequirementUpdateRequest reword = new ObRequirementUpdateRequest();
        reword.setBodyHtml("<p>Against their Entra ID</p>");
        ObClientDtos.ObRequirement after =
                requirements.update(admin, clientId, first, reword).requirements().getFirst();

        assertThat(after.bodyText()).isEqualTo("Against their Entra ID");
        assertThat(after.isMet()).isTrue();
        assertThat(after.metAt()).isEqualTo(stamped);
    }

    /**
     * The service holds this rule and so does the column. A guarantee only the
     * service holds is a convention.
     */
    @Test
    @DisplayName("ck_ob_client_requirements_met refuses a flag without its stamp")
    void theColumnRefusesAFlagWithoutAStamp() {
        long first = detail().requirements().getFirst().id();

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE ob_client_requirements SET is_met = 1 WHERE id = ?", first))
                .hasMessageContaining("ck_ob_client_requirements_met");

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE ob_client_requirements SET met_at = NOW(6) WHERE id = ?", first))
                .hasMessageContaining("ck_ob_client_requirements_met");
    }

    // ── the delete, and what it does not take with it ───────────────────────

    /**
     * The claim {@code ObRequirementService.delete} and the contract both make in
     * prose: a requirement is safe to remove <em>because nothing references this
     * table</em>, unlike a purchase. Exercised rather than asserted, because the
     * argument stops being true the day somebody adds a key.
     */
    @Test
    @DisplayName("removing a requirement leaves the client, its journeys and its steps untouched")
    void deletingTakesNothingWithIt() {
        int journeysBefore = count("SELECT COUNT(*) FROM ob_journeys WHERE ob_client_id = " + clientId);
        int stepsBefore = count("""
                SELECT COUNT(*) FROM ob_journey_steps s
                  JOIN ob_journeys j ON j.id = s.journey_id
                 WHERE j.ob_client_id = %d
                """.formatted(clientId));

        long first = detail().requirements().getFirst().id();
        ObClientDtos.ObClientDetail after = requirements.delete(admin, clientId, first);

        assertThat(after.requirements()).hasSize(1);
        assertThat(count("SELECT COUNT(*) FROM ob_client_requirements WHERE id = " + first))
                .isZero();
        assertThat(count("SELECT COUNT(*) FROM ob_clients WHERE id = " + clientId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM ob_journeys WHERE ob_client_id = " + clientId))
                .isEqualTo(journeysBefore);
        assertThat(count("""
                SELECT COUNT(*) FROM ob_journey_steps s
                  JOIN ob_journeys j ON j.id = s.journey_id
                 WHERE j.ob_client_id = %d
                """.formatted(clientId))).isEqualTo(stepsBefore);
    }

    // ── the column width the ticket baseline could not have ─────────────────

    /**
     * The columns are {@code MEDIUMTEXT}, and a long multi-byte body survives
     * the round trip byte for byte.
     *
     * <p><b>The first draft of this test asserted something false</b>, and the
     * correction is worth keeping. It claimed a body at §3.9's limit exceeds
     * {@code TEXT}'s 65 535 bytes: it does not. The bound is enforced over the
     * <em>sanitised</em> value, so the stored string is at most 20 000 UTF-16
     * units, which is at most 60 000 bytes of utf8mb4. {@code ticket_comments}
     * is not silently truncating today.
     *
     * <p>What is true is narrower and still worth a column: that arrangement has
     * a <em>service</em> check standing between §3.9 and a <em>column</em>
     * limit, and the two have no relationship. Raise the 20 000, or reach the
     * column through a caller that skips the service, and it truncates mid-tag
     * and stores markup that never parses again. {@code CommentSanitizer} could
     * not fix that because its column was already applied; V20260908_1210 could,
     * so it did.
     *
     * <p>Hence two assertions rather than an arithmetic one: the declared type,
     * which is the decision, and a real long body round-tripping, which is the
     * behaviour that decision is for.
     */
    @Test
    @DisplayName("the body columns are MEDIUMTEXT, and a long multi-byte body round-trips intact")
    void aLongBodyRoundTrips() {
        assertThat(columnType("body_html")).isEqualTo("mediumtext");
        assertThat(columnType("body_text")).isEqualTo("mediumtext");

        // Multi-byte on purpose: a byte limit is invisible to ASCII.
        String submitted = "<p>" + "पंजीकरण ".repeat(2_000) + "</p>";
        assertThat(submitted.length()).isLessThan(20_000);

        ObClientDtos.ObClientDetail after = requirements.add(admin, clientId,
                new ObClientDtos.ObRequirementWriteRequest(null, submitted, null));

        ObClientDtos.ObRequirement stored = after.requirements().getLast();
        assertThat(stored.bodyHtml()).endsWith("</p>");
        assertThat(stored.bodyText()).startsWith("पंजीकरण");
        // Byte length well past what a per-character assumption would predict,
        // which is the half of the arithmetic that is real.
        assertThat(stored.bodyHtml().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isGreaterThan(stored.bodyHtml().length());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private ObClientDtos.ObClientDetail detail() {
        return requirements.readable(admin, clientId);
    }

    private String columnType(String column) {
        return jdbc.queryForObject("""
                SELECT data_type FROM information_schema.columns
                 WHERE table_schema = DATABASE()
                   AND table_name = 'ob_client_requirements'
                   AND column_name = ?
                """, String.class, column);
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
