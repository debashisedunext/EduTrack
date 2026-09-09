package com.edunext.edutrack.api.feature.onboarding.signoff;

import com.edunext.edutrack.api.feature.onboarding.instances.ObJourneyStepLifecycleService;
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

import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.when;

/**
 * B-117 · {@code objectObSignoff} against real MySQL — what {@code
 * ObSignoffObjectServiceTest} (mocks, no container) cannot show:
 *
 * <ol>
 *   <li><b>The revert is a real transition on a real
 *       {@code ob_journey_steps} row</b>, through the real {@link
 *       ObJourneyStepLifecycleService} rather than a mock returning a
 *       canned {@link ObJourneyStepLifecycleService.ObjectionResult}.</li>
 *   <li><b>The objection is a real, hash-chained
 *       {@code ob_step_history} row</b> — {@code ObStepJournal}'s locking
 *       and chaining is exactly what a mocked {@code ObStepHistoryRepository}
 *       cannot exercise.</li>
 *   <li><b>The owner notification is a real {@code ob_notification_outbox}
 *       row</b>, on the same reasoning.</li>
 * </ol>
 *
 * <p>{@link ObSignoffSessions} is mocked, on {@code ObSignoffCertificateIT}'s
 * own precedent for {@code UploadPipeline}: a Redis container would prove the
 * Redis client rather than anything about this feature. What is real here is
 * the table, the journal and the outbox.
 */
@SpringBootTest
@Testcontainers
class ObSignoffObjectServiceIT {

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
    private static final String SESSION = "a-session-minted-by-verify";

    @Autowired
    ObSignoffObjectService service;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    ObSignoffSessions sessions;

    private static final AtomicInteger RUN = new AtomicInteger();

    private long ownerId;
    private long clientId;
    private long journeyId;
    private long contactId;
    private long stepId;

    @BeforeEach
    void seed() {
        int run = RUN.incrementAndGet();

        ownerId = insertUser("it_obobj_owner_" + run);
        long salesperson = insertUser("it_obobj_sales_" + run);

        long product = insertProduct("ITOBJ_" + run, "IT Object Product " + run);
        long template = insertTemplate(product, "IT Object Onboarding " + run);
        clientId = insertClient("IT Object Client " + run, salesperson);
        insertApplication(clientId, product);
        journeyId = insertJourney(clientId, product, template);
        contactId = insertContact(clientId, "Priya Raman", "priya" + run + "@client.example");
        stepId = insertStep(journeyId, "Collect signed agreement", "WAITING_ON_CLIENT");
    }

    @Test
    @DisplayName("reverts the step, journals the objection and notifies the owner")
    void happyPath() {
        long signoffId = insertSignoff(clientId, journeyId, stepId, contactId, "PENDING");
        when(sessions.resolve(SESSION)).thenReturn(OptionalLong.of(signoffId));

        PublicSignoffObjectDtos.SignoffDetail result = service.object(SESSION, "The invoice total is wrong.");

        assertThat(result.status().name()).isEqualTo("OBJECTED");

        Map<String, Object> step = jdbc.queryForMap(
                "SELECT status, due_at FROM ob_journey_steps WHERE id = ?", stepId);
        assertThat(step.get("status")).isEqualTo("IN_PROGRESS");

        List<Map<String, Object>> history = jdbc.queryForList(
                "SELECT event_type, old_value, new_value, actor_type, actor_contact_id, remarks, row_hash "
                        + "FROM ob_step_history WHERE step_id = ?", stepId);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).get("event_type")).isEqualTo("OBJECTED");
        assertThat(history.get(0).get("old_value")).isEqualTo("WAITING_ON_CLIENT");
        assertThat(history.get(0).get("new_value")).isEqualTo("IN_PROGRESS");
        assertThat(history.get(0).get("actor_type")).isEqualTo("CLIENT");
        assertThat(((Number) history.get(0).get("actor_contact_id")).longValue()).isEqualTo(contactId);
        assertThat(history.get(0).get("remarks")).isEqualTo("The invoice total is wrong.");
        assertThat(history.get(0).get("row_hash")).isNotNull();

        List<Map<String, Object>> clockEvents = jdbc.queryForList(
                "SELECT event_type FROM ob_step_clock_events WHERE step_id = ?", stepId);
        assertThat(clockEvents).extracting(row -> row.get("event_type")).containsExactly("RESUMED");

        List<Map<String, Object>> outbox = jdbc.queryForList(
                "SELECT event_key, channel, recipient_user_id FROM ob_notification_outbox "
                        + "WHERE step_id = ? ORDER BY channel", stepId);
        assertThat(outbox).hasSize(2);
        assertThat(outbox).allSatisfy(row -> {
            assertThat(row.get("event_key")).isEqualTo("SIGNOFF_OBJECTED");
            assertThat(((Number) row.get("recipient_user_id")).longValue()).isEqualTo(ownerId);
        });
    }

    @Test
    @DisplayName("an IN_PROGRESS step reverts with no clock event — nothing paused it")
    void inProgressStepNeedsNoClockEvent() {
        jdbc.update("UPDATE ob_journey_steps SET status = 'IN_PROGRESS' WHERE id = ?", stepId);
        long signoffId = insertSignoff(clientId, journeyId, stepId, contactId, "PENDING");
        when(sessions.resolve(SESSION)).thenReturn(OptionalLong.of(signoffId));

        service.object(SESSION, "Please re-check the figures.");

        Map<String, Object> step = jdbc.queryForMap("SELECT status FROM ob_journey_steps WHERE id = ?", stepId);
        assertThat(step.get("status")).isEqualTo("IN_PROGRESS");

        List<Map<String, Object>> clockEvents = jdbc.queryForList(
                "SELECT event_type FROM ob_step_clock_events WHERE step_id = ?", stepId);
        assertThat(clockEvents).isEmpty();
    }

    @Test
    @DisplayName("an unknown session is the surface's one generic refusal")
    void unknownSession() {
        when(sessions.resolve("nope")).thenReturn(OptionalLong.empty());

        assertThatExceptionOfType(InvalidSignoffTokenException.class)
                .isThrownBy(() -> service.object("nope", "Anything"));
    }

    @Test
    @DisplayName("an already-signed row cannot be objected to — there is no un-accept")
    void alreadySignedIsRefused() {
        long signoffId = insertSignoff(clientId, journeyId, stepId, contactId, "SIGNED");
        when(sessions.resolve(SESSION)).thenReturn(OptionalLong.of(signoffId));

        assertThatExceptionOfType(InvalidSignoffTokenException.class)
                .isThrownBy(() -> service.object(SESSION, "Too late, I changed my mind"));

        Map<String, Object> step = jdbc.queryForMap("SELECT status FROM ob_journey_steps WHERE id = ?", stepId);
        assertThat(step.get("status")).isEqualTo("WAITING_ON_CLIENT");
        assertThat(jdbc.queryForList("SELECT 1 FROM ob_step_history WHERE step_id = ?", stepId)).isEmpty();
        assertThat(jdbc.queryForList("SELECT 1 FROM ob_notification_outbox WHERE step_id = ?", stepId)).isEmpty();
    }

    @Test
    @DisplayName("an already-objected row cannot be objected to twice — no un-object, no re-object")
    void alreadyObjectedIsRefused() {
        long signoffId = insertSignoff(clientId, journeyId, stepId, contactId, "OBJECTED");
        when(sessions.resolve(SESSION)).thenReturn(OptionalLong.of(signoffId));

        assertThatExceptionOfType(InvalidSignoffTokenException.class)
                .isThrownBy(() -> service.object(SESSION, "Again"));
    }

    // ------------------------------------------------------------------
    // fixtures — ObSignoffCertificateIT's own pattern, one file over
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

    private long insertStep(long journeyId, String name, String status) {
        jdbc.update("""
                INSERT INTO ob_journey_steps (journey_id, sequence, name, tat_days, owner_user_id,
                                               status, started_at, due_at)
                VALUES (?, 1, ?, 2, ?, ?, ?, ?)
                """, journeyId, name, ownerId, status,
                Timestamp.from(REQUESTED_AT), Timestamp.from(REQUESTED_AT.plusSeconds(2 * 86_400)));
        long id = lastId();
        if ("WAITING_ON_CLIENT".equals(status)) {
            jdbc.update("""
                    INSERT INTO ob_step_clock_events (step_id, journey_id, event_type, pause_reason,
                                                        attributed_to, occurred_at, actor_id, actor_type)
                    VALUES (?, ?, 'PAUSED', 'WAITING_ON_CLIENT', 'CLIENT', ?, ?, 'USER')
                    """, id, journeyId, Timestamp.from(REQUESTED_AT.plusSeconds(3_600)), ownerId);
        }
        return id;
    }

    private long insertSignoff(long clientId, long journeyId, long stepId, long contactId, String status) {
        jdbc.update("""
                INSERT INTO ob_signoffs (ob_client_id, journey_id, step_id, kind, status,
                                         token_hash, token_expires_at, requested_at, sent_to_contact_id)
                VALUES (?, ?, ?, 'STEP', ?, ?, ?, ?, ?)
                """, clientId, journeyId, stepId, status,
                String.format("%064d", RUN.incrementAndGet()),
                Timestamp.from(REQUESTED_AT.plusSeconds(86_400)),
                Timestamp.from(REQUESTED_AT), contactId);
        return lastId();
    }

    private long lastId() {
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
