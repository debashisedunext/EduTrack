package com.edunext.edutrack.api.feature.onboarding.signoff;

import com.edunext.edutrack.api.feature.onboarding.clients.ObClientScope;
import com.edunext.edutrack.api.security.module.ObModuleRoleRules;
import com.edunext.edutrack.domain.onboarding.ObSignoffKind;
import com.edunext.edutrack.domain.onboarding.ObSignoffStatus;
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

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The staff sign-off routes against real MySQL — what earns a container, given
 * that {@link ObSignoffAdminServiceTest} already covers every decision made in
 * Java:
 *
 * <ol>
 *   <li><b>A-112's scope is a real predicate over a real {@code ob_clients}
 *       row.</b> {@link ObSignoffAdminRepository} composes
 *       {@link ObClientScope#predicate} into four different queries, and a
 *       mocked repository cannot show that the composition actually excludes a
 *       Sales caller who did not create the client — which is the whole
 *       security property.</li>
 *   <li><b>The cancellation columns are new.</b> V20260915_1130 adds them with
 *       {@code ck_ob_signoffs_cancelled} binding the timestamp to the reason; a
 *       unit test asserting setters proves the Java, not that MySQL accepts the
 *       row.</li>
 *   <li><b>The keyset page is SQL.</b> {@code (requested_at, id)} ordering and
 *       the strict-older comparison decide whether a row can be skipped between
 *       pages, and getting it wrong loses rows silently rather than failing.</li>
 * </ol>
 *
 * <p>Nothing here mocks the outbox: {@code ob_notification_outbox} is a real
 * table with a real unique index on {@code queued_dedupe_key}, and whether a
 * resend's mail survives that index is exactly the sort of thing a mock would
 * answer wrongly and confidently.
 */
@SpringBootTest
@Testcontainers
class ObSignoffAdminIT {

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

    private static final AtomicInteger RUN = new AtomicInteger();

    @Autowired
    ObSignoffAdminService service;

    @Autowired
    JdbcTemplate jdbc;

    private long salesperson;
    private long clientId;
    private long journeyId;
    private long stepId;
    private long contactId;
    private ObClientScope owningSales;
    private ObClientScope otherSales;
    private ObClientScope admin;

    @BeforeEach
    void seed() {
        int run = RUN.incrementAndGet();

        salesperson = insertUser("it_obsa_a_" + run);
        long colleague = insertUser("it_obsa_b_" + run);
        owningSales = new ObClientScope(ObModuleRoleRules.OB_SALES, salesperson);
        otherSales = new ObClientScope(ObModuleRoleRules.OB_SALES, colleague);
        admin = new ObClientScope(ObModuleRoleRules.OB_ADMIN, colleague);

        long product = insertProduct("ITSA_" + run, "IT Signoff Admin Product " + run);
        long template = insertTemplate(product, "IT Signoff Admin Onboarding " + run);
        clientId = insertClient("IT Signoff Admin Client " + run, salesperson);
        insertApplication(clientId, product);
        journeyId = insertJourney(clientId, product, template, "IT Signoff Admin Service " + run);
        contactId = insertContact(clientId, "Priya Raman", "priya.sa" + run + "@client.example");
        stepId = insertStep(journeyId, "Data migration", true, "IN_PROGRESS");
    }

    private ObSignoffAdminDtos.ObSignoffRequestBody stepRequest() {
        return new ObSignoffAdminDtos.ObSignoffRequestBody(ObSignoffKind.STEP, stepId, contactId);
    }

    @Test
    @DisplayName("a request writes a PENDING row and queues exactly one mail to the named contact")
    void requestWritesAndMails() {
        ObSignoffAdminDtos.ObSignoff created = service.request(owningSales, journeyId, stepRequest(), salesperson);

        assertThat(created.status()).isEqualTo(ObSignoffStatus.PENDING);
        assertThat(created.stepId()).isEqualTo(stepId);
        assertThat(created.sentToContact().id()).isEqualTo(contactId);
        assertThat(created.requestedBy().id()).isEqualTo(salesperson);

        Integer queued = jdbc.queryForObject("""
                SELECT COUNT(*) FROM ob_notification_outbox
                 WHERE event_key = 'SIGNOFF_REQUESTED' AND recipient_contact_id = ?
                """, Integer.class, contactId);
        assertThat(queued).isEqualTo(1);
    }

    /**
     * The property the record depends on: reading the table must not yield a
     * working link. Asserted against the column rather than the DTO, because
     * the DTO could omit it while the column held it.
     */
    @Test
    @DisplayName("only a SHA-256 reaches the table — the plaintext token is never stored")
    void storesOnlyTheHash() {
        ObSignoffAdminDtos.ObSignoff created = service.request(owningSales, journeyId, stepRequest(), salesperson);

        String hash = jdbc.queryForObject(
                "SELECT token_hash FROM ob_signoffs WHERE id = ?", String.class, created.id());
        String link = jdbc.queryForObject("""
                SELECT JSON_UNQUOTE(JSON_EXTRACT(payload, '$.action_url'))
                  FROM ob_notification_outbox
                 WHERE event_key = 'SIGNOFF_REQUESTED' AND recipient_contact_id = ?
                """, String.class, contactId);

        assertThat(hash).matches("[0-9a-f]{64}");
        assertThat(link).startsWith("/signoff?token=");
        assertThat(link).doesNotContain(hash);
    }

    @Test
    @DisplayName("a second request for the same step is refused — one decision, one live link")
    void secondRequestIsRefused() {
        service.request(owningSales, journeyId, stepRequest(), salesperson);

        assertThatExceptionOfType(ObSignoffAlreadyPendingException.class)
                .isThrownBy(() -> service.request(owningSales, journeyId, stepRequest(), salesperson));
    }

    /**
     * {@code fk_ob_signoffs_contact} constrains the contact to exist, not to
     * belong to this client — so this refusal has to be ours, and has to be
     * shown against a real foreign key that would have allowed it.
     */
    @Test
    @DisplayName("a contact belonging to another client is refused, though the foreign key would take it")
    void foreignContactIsRefused() {
        long otherClient = insertClient("IT Signoff Other " + RUN.incrementAndGet(), salesperson);
        long foreignContact = insertContact(otherClient, "Someone Else", "else@other.example");

        var body = new ObSignoffAdminDtos.ObSignoffRequestBody(ObSignoffKind.STEP, stepId, foreignContact);

        assertThatExceptionOfType(ObSignoffContactInvalidException.class)
                .isThrownBy(() -> service.request(owningSales, journeyId, body, salesperson));
    }

    @Test
    @DisplayName("out of scope answers the same 404 as absent — Sales who did not create this client")
    void outOfScopeIsIndistinguishableFromNotFound() {
        ObSignoffAdminDtos.ObSignoff created = service.request(owningSales, journeyId, stepRequest(), salesperson);

        assertThatExceptionOfType(ObSignoffNotFoundException.class)
                .isThrownBy(() -> service.get(otherSales, created.id()));
        assertThatExceptionOfType(ObSignoffNotFoundException.class)
                .isThrownBy(() -> service.request(otherSales, journeyId, stepRequest(), salesperson));
        assertThat(service.list(otherSales, clientId, journeyId, null, null, null, 50).data())
                .isEmpty();
    }

    @Test
    @DisplayName("an unrestricted role reaches a client it did not create")
    void unrestrictedRoleSeesEveryClient() {
        ObSignoffAdminDtos.ObSignoff created = service.request(owningSales, journeyId, stepRequest(), salesperson);

        assertThat(service.get(admin, created.id()).signoff().id()).isEqualTo(created.id());
        assertThat(service.list(admin, clientId, journeyId, null, null, null, 50).data()).hasSize(1);
    }

    /** V20260915_1130's columns, and the CHECK that binds two of them. */
    @Test
    @DisplayName("cancelling records who withdrew it, when and why — and the row stays")
    void cancelPersistsTheWithdrawal() {
        ObSignoffAdminDtos.ObSignoff created = service.request(owningSales, journeyId, stepRequest(), salesperson);

        service.cancel(owningSales, created.id(), "Sent to the wrong SPOC.", salesperson);

        var row = jdbc.queryForMap("""
                SELECT status, cancelled_by, cancellation_reason, cancelled_at
                  FROM ob_signoffs WHERE id = ?
                """, created.id());

        assertThat(row.get("status")).isEqualTo("CANCELLED");
        assertThat(row.get("cancelled_by")).isEqualTo(salesperson);
        assertThat(row.get("cancellation_reason")).isEqualTo("Sent to the wrong SPOC.");
        assertThat(row.get("cancelled_at")).isNotNull();
    }

    /**
     * A cancelled step is free to be asked again — the contract names
     * {@code EXPIRED} and {@code CANCELLED} as the two a fresh request is
     * accepted on, and the panel offers one on exactly those.
     */
    @Test
    @DisplayName("a fresh request is accepted once the previous one is withdrawn")
    void requestAgainAfterCancel() {
        ObSignoffAdminDtos.ObSignoff first = service.request(owningSales, journeyId, stepRequest(), salesperson);
        service.cancel(owningSales, first.id(), "wrong contact", salesperson);

        ObSignoffAdminDtos.ObSignoff second =
                service.request(owningSales, journeyId, stepRequest(), salesperson);

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.status()).isEqualTo(ObSignoffStatus.PENDING);
    }

    /**
     * The resent mail carries the only live token. {@code queued_dedupe_key} is
     * unique across PENDING and SENDING rows, so a dedupe key that did not
     * change would silently swallow it.
     */
    @Test
    @DisplayName("a resend replaces the token and queues a second mail past the dedupe index")
    void resendMintsAndMailsAgain() {
        ObSignoffAdminDtos.ObSignoff created = service.request(owningSales, journeyId, stepRequest(), salesperson);
        String before = jdbc.queryForObject(
                "SELECT token_hash FROM ob_signoffs WHERE id = ?", String.class, created.id());

        service.resend(owningSales, created.id());

        String after = jdbc.queryForObject(
                "SELECT token_hash FROM ob_signoffs WHERE id = ?", String.class, created.id());
        Integer mails = jdbc.queryForObject("""
                SELECT COUNT(*) FROM ob_notification_outbox
                 WHERE event_key = 'SIGNOFF_REQUESTED' AND recipient_contact_id = ?
                """, Integer.class, contactId);

        assertThat(after).isNotEqualTo(before);
        assertThat(mails).isEqualTo(2);
    }

    /**
     * Two rows sharing a {@code requested_at} is the case the {@code id}
     * tiebreak exists for. Without it one of them is skipped between pages and
     * nothing fails — the page is simply short by a row nobody notices.
     */
    @Test
    @DisplayName("the keyset returns every row across pages, even when two share a timestamp")
    void keysetPagingLosesNoRows() {
        long stepTwo = insertStep(journeyId, "Training", true, "IN_PROGRESS");
        long stepThree = insertStep(journeyId, "UAT", true, "IN_PROGRESS");

        service.request(owningSales, journeyId, stepRequest(), salesperson);
        service.request(owningSales, journeyId,
                new ObSignoffAdminDtos.ObSignoffRequestBody(ObSignoffKind.STEP, stepTwo, contactId), salesperson);
        service.request(owningSales, journeyId,
                new ObSignoffAdminDtos.ObSignoffRequestBody(ObSignoffKind.STEP, stepThree, contactId), salesperson);

        jdbc.update("UPDATE ob_signoffs SET requested_at = '2026-09-15 09:00:00.000000' WHERE journey_id = ?",
                journeyId);

        var first = service.list(owningSales, clientId, journeyId, null, null, null, 2);
        assertThat(first.data()).hasSize(2);
        assertThat(first.meta().hasMore()).isTrue();

        var second = service.list(owningSales, clientId, journeyId, null, null,
                first.meta().nextCursor(), 2);

        List<Long> seen = java.util.stream.Stream.concat(
                        first.data().stream(), second.data().stream())
                .map(ObSignoffAdminDtos.ObSignoff::id)
                .toList();

        assertThat(seen).hasSize(3).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("a step whose template never asked for a sign-off is refused")
    void unflaggedStepIsRefused() {
        long unflagged = insertStep(journeyId, "Kick-off call", false, "IN_PROGRESS");
        var body = new ObSignoffAdminDtos.ObSignoffRequestBody(ObSignoffKind.STEP, unflagged, contactId);

        assertThatExceptionOfType(ObSignoffNotSignoffableException.class)
                .isThrownBy(() -> service.request(owningSales, journeyId, body, salesperson));
    }

    @Test
    @DisplayName("a go-live is refused while any service is unfinished, and accepted once none is")
    void goLiveWaitsForTheJourney() {
        var goLive = new ObSignoffAdminDtos.ObSignoffRequestBody(ObSignoffKind.GO_LIVE, null, contactId);

        assertThatExceptionOfType(ObSignoffJourneyIncompleteException.class)
                .isThrownBy(() -> service.request(owningSales, journeyId, goLive, salesperson));

        jdbc.update("UPDATE ob_journey_steps SET status = 'DONE' WHERE journey_id = ?", journeyId);

        ObSignoffAdminDtos.ObSignoff created = service.request(owningSales, journeyId, goLive, salesperson);
        assertThat(created.kind()).isEqualTo(ObSignoffKind.GO_LIVE);
        assertThat(created.stepId()).isNull();
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private long insertUser(String username) {
        Long roleId = jdbc.queryForObject("SELECT id FROM roles ORDER BY id LIMIT 1", Long.class);
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id)
                VALUES (?, ?, ?, 'not-a-real-hash', ?, ?)
                """, username, username, username + "@example.com", username, roleId);
        return lastId();
    }

    private long insertProduct(String code, String name) {
        jdbc.update("INSERT INTO ob_products (code, name, is_active) VALUES (?, ?, 1)", code, name);
        return lastId();
    }

    private long insertTemplate(long productId, String name) {
        jdbc.update("""
                INSERT INTO ob_journey_templates (product_id, name, version, is_active, sequence)
                VALUES (?, ?, 1, 1, 0)
                """, productId, name);
        return lastId();
    }

    private long insertClient(String name, long createdBy) {
        jdbc.update("""
                INSERT INTO ob_clients (name, onboarding_date, sales_person_id, created_by)
                VALUES (?, '2026-09-01', ?, ?)
                """, name, createdBy, createdBy);
        return lastId();
    }

    /** {@code fk_ob_journeys_application} requires the purchase to exist before the journey does. */
    private void insertApplication(long clientId, long productId) {
        jdbc.update("INSERT INTO ob_client_applications (ob_client_id, product_id) VALUES (?, ?)",
                clientId, productId);
    }

    /**
     * A journey hangs off a project since V20260911_1800 — the clients-to-projects
     * refactor made {@code project_id} and {@code service_name} NOT NULL. The
     * older signoff ITs beside this one still insert the pre-refactor shape and
     * fail on it; flagged rather than edited, since they belong to finished tasks.
     */
    private long insertProject(long clientId, long productId, String name) {
        jdbc.update("""
                INSERT INTO ob_projects (ob_client_id, product_id, name, start_date)
                VALUES (?, ?, ?, '2026-09-01')
                """, clientId, productId, name);
        return lastId();
    }

    private long insertJourney(long clientId, long productId, long templateId, String serviceName) {
        long projectId = insertProject(clientId, productId, serviceName + " project");
        jdbc.update("""
                INSERT INTO ob_journeys (project_id, ob_client_id, product_id, service_name, template_id)
                VALUES (?, ?, ?, ?, ?)
                """, projectId, clientId, productId, serviceName, templateId);
        return lastId();
    }

    private long insertContact(long clientId, String name, String email) {
        jdbc.update("""
                INSERT INTO ob_client_contacts (ob_client_id, name, email, is_primary)
                VALUES (?, ?, ?, 1)
                """, clientId, name, email);
        return lastId();
    }

    private final AtomicInteger sequence = new AtomicInteger();

    private long insertStep(long journeyId, String name, boolean requiresSignoff, String status) {
        jdbc.update("""
                INSERT INTO ob_journey_steps (journey_id, name, sequence, status, requires_signoff, tat_days)
                VALUES (?, ?, ?, ?, ?, 1)
                """, journeyId, name, sequence.incrementAndGet(), status, requiresSignoff);
        return lastId();
    }

    private long lastId() {
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
