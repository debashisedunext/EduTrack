package com.edunext.edutrack.api.feature.onboarding.signoff;

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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.when;

/**
 * B-119 · CSAT against real MySQL — what {@code ObSignoffCsatServiceTest}
 * (mocks {@code ObSignoffRepository} itself) cannot show:
 *
 * <ol>
 *   <li><b>The session really is still usable for CSAT after {@code accept}
 *       has committed</b> — driven through the real
 *       {@link ObSignoffAcceptService#accept} and then the real
 *       {@link ObSignoffCsatService#submit} on the same session token, not a
 *       mock standing in for either.</li>
 *   <li><b>{@code ck_ob_signoffs_csat} accepts a real write</b> — the
 *       migration's CHECK constraint is exercised on contact with the
 *       server, not only read.</li>
 *   <li><b>"Already surveyed" is a real cross-journey query</b> — two real
 *       {@code GO_LIVE} sign-offs on two real journeys of one client, and the
 *       guard is asked against actual rows rather than a stubbed boolean.</li>
 * </ol>
 *
 * <p>{@link ObSignoffSessions} and {@link UploadPipeline} are mocked, on
 * {@code ObSignoffGoLiveIT}'s own precedent one class over: Redis and a real
 * bucket would each prove their own client rather than anything about this
 * feature. Mocking {@code sessions.resolve} to answer the same signoff id
 * for the life of the test is what stands in for "the key is still there" —
 * the fact that {@link ObSignoffAcceptService} no longer calls
 * {@code sessions.invalidate} for a {@code GO_LIVE} acceptance is what
 * {@code ObSignoffAcceptServiceTest} already proves with a verify(); this
 * class proves what happens on the database once that promise holds.
 */
@SpringBootTest
@Testcontainers
class ObSignoffCsatIT {

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
    private static final String SESSION_A = "session-journey-a";
    private static final String SESSION_B = "session-journey-b";
    private static final String SESSION_STEP = "session-step";

    @Autowired
    ObSignoffAcceptService acceptService;

    @Autowired
    ObSignoffCsatService csatService;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    ObSignoffSessions sessions;

    @MockitoBean
    UploadPipeline uploads;

    private static final AtomicInteger RUN = new AtomicInteger();

    private long clientId;
    private long journeyAId;
    private long journeyBId;
    private long contactId;

    @BeforeEach
    void seed() {
        int run = RUN.incrementAndGet();

        long salesperson = insertUser("it_csat_sales_" + run);
        long ownerA = insertUser("it_csat_ownerA_" + run);
        long ownerB = insertUser("it_csat_ownerB_" + run);

        long productA = insertProduct("ITCSA_" + run, "IT CSAT Product A " + run);
        long templateA = insertTemplate(productA, "IT CSAT Onboarding A " + run);
        long productB = insertProduct("ITCSB_" + run, "IT CSAT Product B " + run);
        long templateB = insertTemplate(productB, "IT CSAT Onboarding B " + run);

        clientId = insertClient("IT CSAT Client " + run, salesperson);
        insertApplication(clientId, productA);
        insertApplication(clientId, productB);
        journeyAId = insertJourney(clientId, productA, templateA);
        journeyBId = insertJourney(clientId, productB, templateB);
        insertStep(journeyAId, "Step A", ownerA);
        insertStep(journeyBId, "Step B", ownerB);
        contactId = insertContact(clientId, "Priya Raman", "priya" + run + "@client.example");
    }

    @Test
    @DisplayName("the session survives accept and CSAT persists on it")
    void sessionSurvivesAcceptAndCsatPersists() {
        long signoffA = insertGoLiveSignoff(journeyAId, contactId);
        when(sessions.resolve(SESSION_A)).thenReturn(OptionalLong.of(signoffA));

        acceptService.accept(SESSION_A, "Priya Raman", null, null);

        // The one fact this whole task turns on: the session that accept
        // just consumed for its own purpose still resolves to the same row
        // afterwards, and submit() can use it.
        csatService.submit(SESSION_A, 4, "Smooth once we got going.");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT csat_score, csat_comment, csat_submitted_at FROM ob_signoffs WHERE id = ?",
                signoffA);
        assertThat(row.get("csat_score")).isEqualTo(4);
        assertThat(row.get("csat_comment")).isEqualTo("Smooth once we got going.");
        assertThat(row.get("csat_submitted_at")).isNotNull();
    }

    @Test
    @DisplayName("a STEP sign-off's session is refused with 422, not the surface's 401")
    void stepSessionIs422() {
        long stepSignoff = insertStepSignoff(journeyAId, contactId);
        when(sessions.resolve(SESSION_STEP)).thenReturn(OptionalLong.of(stepSignoff));

        assertThatExceptionOfType(CsatNotOfferedException.class)
                .isThrownBy(() -> csatService.submit(SESSION_STEP, 5, null));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT csat_score, csat_submitted_at FROM ob_signoffs WHERE id = ?", stepSignoff);
        assertThat(row.get("csat_score")).isNull();
        assertThat(row.get("csat_submitted_at")).isNull();
    }

    @Test
    @DisplayName("a second journey's session is refused once the client has already been surveyed")
    void alreadySurveyedAcrossJourneys() {
        long signoffA = insertGoLiveSignoff(journeyAId, contactId);
        when(sessions.resolve(SESSION_A)).thenReturn(OptionalLong.of(signoffA));
        acceptService.accept(SESSION_A, "Priya Raman", null, null);
        csatService.submit(SESSION_A, 5, "Great experience.");

        long signoffB = insertGoLiveSignoff(journeyBId, contactId);
        when(sessions.resolve(SESSION_B)).thenReturn(OptionalLong.of(signoffB));
        acceptService.accept(SESSION_B, "Priya Raman", null, null);

        // Journey B's own session has never itself been surveyed, and the
        // 422 still fires — the guard is "has this client answered", not
        // "has this row".
        assertThatExceptionOfType(CsatAlreadySubmittedException.class)
                .isThrownBy(() -> csatService.submit(SESSION_B, 2, "Second try"));

        Map<String, Object> rowB = jdbc.queryForMap(
                "SELECT csat_score FROM ob_signoffs WHERE id = ?", signoffB);
        assertThat(rowB.get("csat_score")).isNull();
    }

    // ------------------------------------------------------------------
    // fixtures — ObSignoffGoLiveIT's own pattern, one class over
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

    private void insertApplication(long clientId, long productId) {
        jdbc.update("INSERT INTO ob_client_applications (ob_client_id, product_id) VALUES (?, ?)",
                clientId, productId);
    }

    private long insertJourney(long clientId, long productId, long templateId) {
        jdbc.update("""
                INSERT INTO ob_journeys (ob_client_id, product_id, template_id, gate_status)
                VALUES (?, ?, ?, 'OPEN')
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

    private void insertStep(long journeyId, String name, long ownerId) {
        jdbc.update("""
                INSERT INTO ob_journey_steps (journey_id, sequence, name, tat_days, owner_user_id,
                                               status, started_at, due_at)
                VALUES (?, 1, ?, 2, ?, 'DONE', ?, ?)
                """, journeyId, name, ownerId,
                Timestamp.from(REQUESTED_AT), Timestamp.from(REQUESTED_AT.plusSeconds(2 * 86_400)));
    }

    private long insertGoLiveSignoff(long journeyId, long contactId) {
        jdbc.update("""
                INSERT INTO ob_signoffs (ob_client_id, journey_id, step_id, kind, status,
                                         token_hash, token_expires_at, requested_at, sent_to_contact_id)
                VALUES (?, ?, NULL, 'GO_LIVE', 'PENDING', ?, ?, ?, ?)
                """, clientId, journeyId,
                String.format("%064d", RUN.incrementAndGet()),
                Timestamp.from(REQUESTED_AT.plusSeconds(86_400)),
                Timestamp.from(REQUESTED_AT), contactId);
        return lastId();
    }

    /** A PENDING STEP sign-off — no accept needed, since the 422 fires on kind alone. */
    private long insertStepSignoff(long journeyId, long contactId) {
        Long stepId = jdbc.queryForObject(
                "SELECT id FROM ob_journey_steps WHERE journey_id = ? ORDER BY id LIMIT 1", Long.class, journeyId);
        jdbc.update("""
                INSERT INTO ob_signoffs (ob_client_id, journey_id, step_id, kind, status,
                                         token_hash, token_expires_at, requested_at, sent_to_contact_id)
                VALUES (?, ?, ?, 'STEP', 'PENDING', ?, ?, ?, ?)
                """, clientId, journeyId, stepId,
                String.format("%064d", RUN.incrementAndGet()),
                Timestamp.from(REQUESTED_AT.plusSeconds(86_400)),
                Timestamp.from(REQUESTED_AT), contactId);
        return lastId();
    }

    private long lastId() {
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
