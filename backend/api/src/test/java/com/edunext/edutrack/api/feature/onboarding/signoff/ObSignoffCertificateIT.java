package com.edunext.edutrack.api.feature.onboarding.signoff;

import com.edunext.edutrack.api.feature.onboarding.clients.ObClientScope;
import com.edunext.edutrack.api.security.module.ObModuleRoleRules;
import com.edunext.edutrack.api.upload.UploadPipeline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * B-116 · {@code getObSignoffCertificate} against real MySQL — what earns a
 * container, given that {@link ObSignoffCertificateRendererTest} and
 * {@code ObSignoffAcceptServiceTest} cover every decision made in Java:
 *
 * <ol>
 *   <li><b>The 404-before-SIGNED case is a fact about a row, not a branch in
 *       Java.</b> {@code pdf_storage_key} is only ever set by
 *       {@link ObSignoffCertificateService#archive}, called from
 *       {@code ObSignoffAcceptService} after a real acceptance — a fixture
 *       that inserted a PENDING row with a key already on it would prove
 *       nothing about the route.</li>
 *   <li><b>A-112 scope is a real predicate over a real {@code ob_clients}
 *       row</b> — {@link ObSignoffCertificateVisibility} composes
 *       {@link ObClientScope#predicate}, and a mock repository cannot show
 *       that composition actually excludes a Sales caller who did not create
 *       the client.</li>
 * </ol>
 *
 * <p>{@link UploadPipeline} is mocked, on {@code ObClientAttachmentsIT}'s own
 * reasoning for {@code AttachmentStorage}: a MinIO container would prove the
 * S3 client rather than anything about this feature. What is real here is the
 * table and the scope query.
 */
@SpringBootTest
@Testcontainers
class ObSignoffCertificateIT {

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

    private static final Instant REQUESTED_AT = Instant.parse("2026-09-01T09:00:00Z");
    private static final Instant SIGNED_AT = Instant.parse("2026-09-02T10:15:00Z");
    private static final byte[] PDF = "%PDF-1.7\nfixture body\n%%EOF".getBytes(StandardCharsets.UTF_8);

    @Autowired
    ObSignoffCertificateService certificates;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    UploadPipeline uploads;

    private static final AtomicInteger RUN = new AtomicInteger();

    private long salesperson;
    private long colleague;
    private long clientId;
    private long journeyId;
    private long contactId;
    private ObClientScope owningSales;
    private ObClientScope otherSales;
    private ObClientScope admin;

    @BeforeEach
    void seed() {
        int run = RUN.incrementAndGet();

        salesperson = insertUser("it_obcert_a_" + run);
        colleague = insertUser("it_obcert_b_" + run);
        owningSales = new ObClientScope(ObModuleRoleRules.OB_SALES, salesperson);
        otherSales = new ObClientScope(ObModuleRoleRules.OB_SALES, colleague);
        admin = new ObClientScope(ObModuleRoleRules.OB_ADMIN, colleague);

        long product = insertProduct("ITCERT_" + run, "IT Cert Product " + run);
        long template = insertTemplate(product, "IT Cert Onboarding " + run);
        clientId = insertClient("IT Cert Client " + run, salesperson);
        insertApplication(clientId, product);
        journeyId = insertJourney(clientId, product, template);
        contactId = insertContact(clientId, "Priya Raman", "priya" + run + "@client.example");
    }

    @Test
    @DisplayName("404 before the sign-off is SIGNED — a certificate for a decision nobody made does not exist")
    void notFoundBeforeSigned() {
        long signoffId = insertSignoff(clientId, journeyId, contactId, "PENDING", null, null);

        assertThatExceptionOfType(ObSignoffCertificateNotFoundException.class)
                .isThrownBy(() -> certificates.read(owningSales, signoffId));
    }

    @Test
    @DisplayName("404 for no such sign-off at all — indistinguishable from the row existing but unsigned")
    void notFoundForAnUnknownId() {
        assertThatExceptionOfType(ObSignoffCertificateNotFoundException.class)
                .isThrownBy(() -> certificates.read(owningSales, 999_999_999L));
    }

    @Test
    @DisplayName("streams the archived bytes once SIGNED and a key is on the row")
    void returnsTheArchivedBytesOnceSigned() {
        long signoffId = insertSignoff(clientId, journeyId, contactId, "SIGNED", SIGNED_AT, null);
        setPdfStorageKey(signoffId, "onboarding/signoff-certificates/" + signoffId
                + "/11111111-1111-1111-1111-111111111111");

        when(uploads.read(any())).thenReturn(Optional.of(PDF));

        byte[] bytes = certificates.read(owningSales, signoffId);

        assertThat(bytes).isEqualTo(PDF);
    }

    @Test
    @DisplayName("404 once SIGNED if the object behind the key is gone — never a 500")
    void notFoundWhenTheObjectIsMissing() {
        long signoffId = insertSignoff(clientId, journeyId, contactId, "SIGNED", SIGNED_AT, null);
        setPdfStorageKey(signoffId, "onboarding/signoff-certificates/" + signoffId
                + "/11111111-1111-1111-1111-111111111111");

        when(uploads.read(any())).thenReturn(Optional.empty());

        assertThatExceptionOfType(ObSignoffCertificateNotFoundException.class)
                .isThrownBy(() -> certificates.read(owningSales, signoffId));
    }

    @Test
    @DisplayName("out of scope answers the same 404 as absent — Sales who did not create this client")
    void outOfScopeIsIndistinguishableFromNotFound() {
        long signoffId = insertSignoff(clientId, journeyId, contactId, "SIGNED", SIGNED_AT, null);
        setPdfStorageKey(signoffId, "onboarding/signoff-certificates/" + signoffId
                + "/11111111-1111-1111-1111-111111111111");

        assertThatExceptionOfType(ObSignoffCertificateNotFoundException.class)
                .isThrownBy(() -> certificates.read(otherSales, signoffId));
    }

    @Test
    @DisplayName("an unrestricted role reaches a client it did not create")
    void unrestrictedRoleSeesEveryClient() {
        long signoffId = insertSignoff(clientId, journeyId, contactId, "SIGNED", SIGNED_AT, null);
        setPdfStorageKey(signoffId, "onboarding/signoff-certificates/" + signoffId
                + "/11111111-1111-1111-1111-111111111111");
        when(uploads.read(any())).thenReturn(Optional.of(PDF));

        byte[] bytes = certificates.read(admin, signoffId);

        assertThat(bytes).isEqualTo(PDF);
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

    private long insertJourney(long clientId, long productId, long templateId) {
        jdbc.update("""
                INSERT INTO ob_journeys (ob_client_id, product_id, template_id)
                VALUES (?, ?, ?)
                """, clientId, productId, templateId);
        return lastId();
    }

    private long insertContact(long clientId, String name, String email) {
        jdbc.update("""
                INSERT INTO ob_client_contacts (ob_client_id, name, email, is_primary)
                VALUES (?, ?, ?, 1)
                """, clientId, name, email);
        return lastId();
    }

    /** {@code ObReportsIT.insertSignoff}'s own pattern, extended with the STEP/GO_LIVE and pdf key arms. */
    private long insertSignoff(long clientId, long journeyId, long contactId, String status,
                               Instant signedAt, String pdfStorageKey) {
        jdbc.update("""
                INSERT INTO ob_signoffs (ob_client_id, journey_id, step_id, kind, status,
                                         token_hash, token_expires_at, requested_at,
                                         sent_to_contact_id, signed_by_contact_id, signed_name,
                                         signed_at, pdf_storage_key)
                VALUES (?, ?, NULL, 'GO_LIVE', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, clientId, journeyId, status,
                String.format("%064d", RUN.incrementAndGet()),
                Timestamp.from(REQUESTED_AT.plusSeconds(86_400)),
                Timestamp.from(REQUESTED_AT), contactId,
                signedAt == null ? null : contactId,
                signedAt == null ? null : "Priya Raman",
                signedAt == null ? null : Timestamp.from(signedAt),
                pdfStorageKey);
        return lastId();
    }

    private void setPdfStorageKey(long signoffId, String key) {
        jdbc.update("UPDATE ob_signoffs SET pdf_storage_key = ? WHERE id = ?", key, signoffId);
    }

    private long lastId() {
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
