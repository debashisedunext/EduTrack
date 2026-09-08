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
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B-103 · the SPOC panel against real MySQL.
 *
 * <p>What earns a container, given that {@code ObContactServiceTest} covers
 * every decision made in Java:
 *
 * <ol>
 *   <li><b>{@code uq_ob_client_contacts_primary} and the promote/demote
 *       sequence.</b> The index is on a generated column, so "one primary per
 *       client" is a database fact rather than a service rule, and the only way
 *       to know the demotion beats the promotion to the row is to make MySQL
 *       adjudicate.</li>
 *   <li><b>{@code ck_ob_client_contacts_consent}.</b> The service refuses a
 *       {@code true} with no basis; this proves the column would have refused it
 *       too, which is what makes the guarantee survive a future caller that
 *       skips the service.</li>
 *   <li><b>The two triggers on the consent journal.</b> A guarantee nobody has
 *       exercised is a guarantee nobody has.</li>
 *   <li><b>That the whole client document comes back changed.</b> The write
 *       answers with {@code ObClientDetail}, which is assembled by six
 *       statements this test is the only thing running end to end.</li>
 * </ol>
 *
 * <p>Fixtures use usernames and product codes no seed migration will claim, on
 * {@code ObClientsIT}'s own precedent.
 */
@SpringBootTest
@Testcontainers
class ObContactsIT {

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
    ObContactService contacts;

    @Autowired
    JdbcTemplate jdbc;

    /** One suffix per test, because nothing here is ever deleted — see {@link #seed()}. */
    private static final AtomicInteger RUN = new AtomicInteger();

    private long ayush;
    private long clientId;
    private long primaryId;
    private String founderEmail;
    private String replacementEmail;
    private String secondEmail;
    private ObClientScope admin;

    /**
     * A fresh client per test, and <b>no cleanup between them</b>.
     *
     * <p>{@code ObClientsIT} deletes its rows in {@code @BeforeEach}; this class
     * cannot, and the reason is the thing under test.
     * {@code ob_contact_consent_events} has a {@code BEFORE DELETE} trigger that
     * refuses every removal, and {@code fk_ob_contact_consent_contact} is
     * deliberately without {@code ON DELETE CASCADE} — so a teardown that tried
     * to clear the SPOCs would be refused by the guarantee this class exists to
     * prove. Disabling the trigger to tidy up would leave the suite proving
     * something about a schema it had modified.
     *
     * <p>So every fixture is suffixed with a counter and nothing is ever
     * removed. Rows accumulate for the length of one container, which is a few
     * dozen, and every assertion below is keyed by an id rather than by a
     * {@code LIKE}.
     */
    @BeforeEach
    void seed() {
        int run = RUN.incrementAndGet();

        ayush = insertUser("it_obcontact_" + run);
        admin = new ObClientScope(ObClientScope.OB_ADMIN, ayush);

        long product = insertProduct("ITSPOC_" + run, "IT SPOC Product " + run);
        insertTemplate(product, "IT SPOC Onboarding " + run);

        ObClientDtos.ObClientDetail client = clientWrites.create(admin, ayush,
                new ObClientDtos.ObClientCreateRequest(
                        "IT SPOC Client " + run, null, BOARDED, null, null, null, null,
                        List.of(new ObClientDtos.ObContactWriteRequest(
                                "Founding SPOC", "Principal", "founder" + run + "@example.com",
                                "+911111111111", true, "VERBAL", true)),
                        List.of(new ObClientDtos.ObApplicationWriteRequest(
                                product, "ANNUAL", 10, BOARDED, BOARDED.plusYears(1))),
                        List.of(),
                        // acknowledgeSimilarNames, because the fixtures below are
                        // "IT SPOC Client 1", "2", "3" … and B-102's fuzzy name
                        // guard is quite right to think those are the same
                        // company. It is that guard's test's subject, not this
                        // one's.
                        false, true));

        clientId = client.id();
        primaryId = client.contacts().getFirst().id();
        founderEmail = client.contacts().getFirst().email();
        replacementEmail = "replacement" + run + "@example.com";
        secondEmail = "second" + run + "@example.com";
    }

    // ── the create's own consent stamp ──────────────────────────────────────

    /**
     * The wizard is where the consent conversation happened, so OB-04 stamps it
     * rather than leaving the column for the SPOC panel to fill in later.
     */
    @Test
    @DisplayName("boarding a client stamps its SPOCs' consent and opens the journal")
    void theCreateStampsConsent() {
        Map<String, Object> row = contactRow(primaryId);

        // Connector/J maps TINYINT(1) to Boolean, so the assertion is on true
        // rather than on 1 — the same column reads as an int through JdbcClient's
        // typed query and as a Boolean through queryForMap.
        assertThat(row.get("whatsapp_opt_in")).isEqualTo(true);
        assertThat(row.get("whatsapp_opt_in_source")).isEqualTo("VERBAL");
        assertThat(row.get("whatsapp_opt_in_at")).isNotNull();
        assertThat(((Number) row.get("whatsapp_opt_in_by")).longValue()).isEqualTo(ayush);

        assertThat(consentEvents(primaryId)).isEqualTo(1);
    }

    /**
     * The service refuses this with a 400 and never reaches the column. Asserted
     * directly against MySQL so the guarantee survives a caller that skips the
     * service — a fixture, an import, or the next feature in this package.
     */
    @Test
    @DisplayName("ck_ob_client_contacts_consent refuses a consent with no basis")
    void theCheckConstraintRefusesABareTrue() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO ob_client_contacts (ob_client_id, name, email, whatsapp_opt_in)
                VALUES (?, 'Bare', 'bare@example.com', 1)
                """, clientId))
                .hasMessageContaining("ck_ob_client_contacts_consent");
    }

    // ── the primary slot ────────────────────────────────────────────────────

    /**
     * The whole reason the demotion and the promotion are in one transaction:
     * {@code uq_ob_client_contacts_primary} would refuse the second active
     * primary, and only MySQL can say whether the ordering actually beats it.
     */
    @Test
    @DisplayName("adding a primary demotes the incumbent, and the index is satisfied")
    void promotingSwapsTheSlotAtomically() {
        ObClientDtos.ObClientDetail after = contacts.add(admin, ayush, clientId,
                upsert("Replacement", replacementEmail, false, null, true, null));

        assertThat(after.contacts()).filteredOn(ObClientDtos.ObContact::isPrimary)
                .extracting(ObClientDtos.ObContact::email)
                .containsExactly(replacementEmail);
        assertThat(after.primaryContact().email()).isEqualTo(replacementEmail);
        assertThat(activePrimaries()).isEqualTo(1);
    }

    /**
     * The refusal is what makes the module's mail addressable at all — see
     * {@link LastPrimaryContactException} for why this is stricter than the
     * ticketing master.
     */
    @Test
    @DisplayName("the only primary cannot be removed")
    void theLastPrimaryIsRefused() {
        assertThatThrownBy(() -> contacts.remove(admin, clientId, primaryId))
                .isInstanceOf(LastPrimaryContactException.class);
        assertThat(activePrimaries()).isEqualTo(1);
    }

    /** And the refusal is not a dead end: one request installs the successor. */
    @Test
    @DisplayName("the departing primary is removable once a successor holds the slot")
    void aSuccessorFreesTheDepartingPrimary() {
        contacts.add(admin, ayush, clientId,
                upsert("Replacement", replacementEmail, false, null, true, null));

        ObClientDtos.ObClientDetail after = contacts.remove(admin, clientId, primaryId);

        assertThat(after.contacts())
                .filteredOn(contact -> contact.id() == primaryId)
                .extracting(ObClientDtos.ObContact::isActive)
                .containsExactly(false);
        assertThat(activePrimaries()).isEqualTo(1);
    }

    // ── deactivation keeps the row ──────────────────────────────────────────

    /**
     * A removed SPOC stays in the document. OB-05 has to show them as removed,
     * be able to bring them back, and still explain whose name is on a past
     * sign-off.
     */
    @Test
    @DisplayName("removal deactivates and the contact is still in the client document")
    void removalDeactivatesRatherThanDeleting() {
        ObClientDtos.ObClientDetail added = contacts.add(admin, ayush, clientId,
                upsert("Second", secondEmail, false, null, false, null));
        long secondId = idOf(added, secondEmail);

        ObClientDtos.ObClientDetail after = contacts.remove(admin, clientId, secondId);

        assertThat(after.contacts()).extracting(ObClientDtos.ObContact::email)
                .contains(secondEmail);
        assertThat(count("SELECT COUNT(*) FROM ob_client_contacts WHERE id = " + secondId))
                .isEqualTo(1);
    }

    /** B-014's UNCHANGED argument, against the real row. */
    @Test
    @DisplayName("removing an already-removed contact is not an error")
    void removalIsIdempotent() {
        ObClientDtos.ObClientDetail added = contacts.add(admin, ayush, clientId,
                upsert("Second", secondEmail, false, null, false, null));
        long secondId = idOf(added, secondEmail);

        contacts.remove(admin, clientId, secondId);
        ObClientDtos.ObClientDetail after = contacts.remove(admin, clientId, secondId);

        assertThat(after.contacts()).filteredOn(contact -> contact.id() == secondId)
                .extracting(ObClientDtos.ObContact::isActive)
                .containsExactly(false);
    }

    // ── the email guard ─────────────────────────────────────────────────────

    /**
     * Matched the way {@code utf8mb4_0900_ai_ci} matches, so the service refuses
     * exactly what {@code uq_ob_client_contacts_email} would have refused rather
     * than a narrower set.
     */
    @Test
    @DisplayName("a duplicate email is refused case-insensitively, as the index would")
    void theEmailGuardAgreesWithTheIndex() {
        assertThatThrownBy(() -> contacts.add(admin, ayush, clientId,
                upsert("Impostor", founderEmail.toUpperCase(java.util.Locale.ROOT), false, null, false, null)))
                .isInstanceOf(DuplicateContactEmailException.class);
    }

    // ── the consent journal ─────────────────────────────────────────────────

    /**
     * Withdrawal clears the row and keeps the journal, which is the entire
     * reason the journal exists: the row says where consent stands, the journal
     * is what shows it once stood.
     */
    @Test
    @DisplayName("withdrawing clears the stamp and leaves both events in the journal")
    void withdrawalKeepsTheEvidence() {
        contacts.update(admin, ayush, clientId, primaryId,
                upsert("Founding SPOC", founderEmail, false, null, true, null));

        Map<String, Object> row = contactRow(primaryId);
        assertThat(row.get("whatsapp_opt_in")).isEqualTo(false);
        assertThat(row.get("whatsapp_opt_in_at")).isNull();
        assertThat(row.get("whatsapp_opt_in_source")).isNull();

        assertThat(consentEvents(primaryId)).isEqualTo(2);
        assertThat(jdbc.queryForList(
                "SELECT opted_in FROM ob_contact_consent_events "
                        + "WHERE ob_client_contact_id = ? ORDER BY id", Integer.class, primaryId))
                .containsExactly(1, 0);
    }

    /** An unrelated edit must not re-date the consent, and must not add a row. */
    @Test
    @DisplayName("editing a phone number leaves the consent date and the journal alone")
    void anUnrelatedEditIsNotAConsentEvent() {
        Object before = contactRow(primaryId).get("whatsapp_opt_in_at");

        contacts.update(admin, ayush, clientId, primaryId,
                upsert("Founding SPOC", founderEmail, true, "VERBAL", true,
                        "+919999999999"));

        assertThat(contactRow(primaryId).get("whatsapp_opt_in_at")).isEqualTo(before);
        assertThat(contactRow(primaryId).get("phone")).isEqualTo("+919999999999");
        assertThat(consentEvents(primaryId)).isEqualTo(1);
    }

    /**
     * A guarantee nobody has exercised is a guarantee nobody has. Both
     * directions, because MySQL needs one trigger per verb.
     */
    @Test
    @DisplayName("the consent journal refuses an UPDATE and a DELETE")
    void theJournalIsInsertOnly() {
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE ob_contact_consent_events SET opted_in = 0 WHERE ob_client_contact_id = ?",
                primaryId))
                .hasMessageContaining("cannot be updated");

        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM ob_contact_consent_events WHERE ob_client_contact_id = ?", primaryId))
                .hasMessageContaining("cannot be deleted");
    }

    // ── the backfill ────────────────────────────────────────────────────────

    /**
     * The one value no caller may write, and the reason it is visible: a SPOC
     * whose consent predates its capture has to be re-approached, and a row that
     * looked like an ordinary consent would never be.
     */
    @Test
    @DisplayName("UNRECORDED reads back and cannot be sent")
    void unrecordedIsReadableAndUnwritable() {
        jdbc.update("""
                UPDATE ob_client_contacts
                   SET whatsapp_opt_in_source = 'UNRECORDED'
                 WHERE id = ?
                """, primaryId);

        assertThat(contacts.readable(admin, clientId).contacts())
                .filteredOn(contact -> contact.id() == primaryId)
                .extracting(ObClientDtos.ObContact::whatsappOptInSource)
                .containsExactly("UNRECORDED");

        assertThatThrownBy(() -> contacts.update(admin, ayush, clientId, primaryId,
                upsert("Founding SPOC", founderEmail, true, "UNRECORDED", true, null)))
                .isInstanceOf(ObClientValidationException.class);
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private static ObContactDtos.ObContactUpsertRequest upsert(
            String name, String email, boolean optIn, String source, boolean primary, String phone) {

        return new ObContactDtos.ObContactUpsertRequest(
                name, null, email, phone, optIn, source, primary, null);
    }

    private static long idOf(ObClientDtos.ObClientDetail client, String email) {
        return client.contacts().stream()
                .filter(contact -> contact.email().equalsIgnoreCase(email))
                .findFirst()
                .orElseThrow()
                .id();
    }

    private Map<String, Object> contactRow(long contactId) {
        return jdbc.queryForMap("SELECT * FROM ob_client_contacts WHERE id = ?", contactId);
    }

    private int consentEvents(long contactId) {
        return count("SELECT COUNT(*) FROM ob_contact_consent_events "
                + "WHERE ob_client_contact_id = " + contactId);
    }

    private int activePrimaries() {
        return count("SELECT COUNT(*) FROM ob_client_contacts "
                + "WHERE ob_client_id = " + clientId + " AND is_primary_key = 1");
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
