package com.edunext.edutrack.worker.onboarding.escalation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C-115 · the escalation matrix against a real MySQL 8.4 — {@code
 * ObTatScannerIT}'s own shape one class over.
 *
 * <p>L2 and L3 need "well past the threshold" and "well short of it" fixture
 * dates rather than fixture dates tuned to the exact seeded working
 * calendar, on purpose: a two- or three-calendar-day gap is past 4 or 8
 * working hours under any working calendar this suite could seed, and a
 * ten-minute gap is short of it under any of them too. The precise
 * working-hours arithmetic is {@code WorkingHoursServiceTest}'s job, not
 * this scanner's.
 */
@Testcontainers
@SpringBootTest(classes = com.edunext.edutrack.worker.WorkerApplication.class)
@Import(ObEscalationScannerIT.FixedClock.class)
class ObEscalationScannerIT {

    private static final Instant NOW = Instant.parse("2026-08-10T10:00:00Z");

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_ob_escalation_it")
            .withCommand(
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                    "--default-time-zone=+00:00",
                    "--log-bin-trust-function-creators=1")
            .withUrlParam("allowPublicKeyRetrieval", "true")
            .withUrlParam("useSSL", "false")
            .withUrlParam("connectionTimeZone", "UTC");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("edutrack.onboarding.escalation.initial-delay", () -> "PT24H");
        registry.add("edutrack.onboarding.tat.initial-delay", () -> "PT24H");
        registry.add("edutrack.sla.initial-delay", () -> "PT24H");
        registry.add("edutrack.stats.enabled", () -> "false");
        registry.add("edutrack.outbox.enabled", () -> "false");
        registry.add("edutrack.ob-outbox.enabled", () -> "false");
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
    }

    @TestConfiguration
    public static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired ObEscalationScanner scanner;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private long owner;
    private long manager;
    private long clientId;
    private long journeyId;
    private int sequence = 0;

    @BeforeEach
    void seed() {
        int run = SEQ.incrementAndGet();
        manager = insertUser("mgr" + run, null);
        owner = insertUser("own" + run, manager);
        clientId = insertClient("Horizon Academy " + run);
        long productId = insertProduct("P" + run, "Learning Management " + run);
        long templateId = insertTemplate(productId);
        journeyId = insertJourney(clientId, productId, templateId);
    }

    // -------------------------------------------------------------- L1

    @Test
    @DisplayName("L1 fires the moment a step has breached, to its owner")
    void l1FiresAtBreach() {
        long step = insertBreachedStep("Data migration", owner, NOW.minusSeconds(600));

        assertThat(scanner.scanOnce()).isEqualTo(1);

        assertThat(escalatedTo(step, "L1")).isEqualTo(owner);
    }

    @Test
    @DisplayName("a step that has not breached is not a candidate for any rung")
    void anUnbreachedStepRaisesNothing() {
        insertStep("Data migration", owner, "IN_PROGRESS", null);

        assertThat(scanner.scanOnce()).isZero();
    }

    @Test
    @DisplayName("a second pass does not raise the same rung twice")
    void aRungIsRaisedOnce() {
        insertBreachedStep("Data migration", owner, NOW.minusSeconds(600));
        scanner.scanOnce();

        assertThat(scanner.scanOnce()).isZero();
        assertThat(countRungs("L1")).isEqualTo(1);
    }

    // -------------------------------------------------------------- L2 / L3

    @Test
    @DisplayName("L2 does not fire before four working hours have passed since the breach")
    void l2NotYetDue() {
        long step = insertBreachedStep("Data migration", owner, NOW.minusSeconds(600));
        scanner.scanOnce(); // raises L1 only

        assertThat(escalatedTo(step, "L2")).isNull();
    }

    @Test
    @DisplayName("L2 fires to the owner's reporting manager once well past four working hours")
    void l2FiresToTheManager() {
        // NOW (2026-08-10) is a Monday; five calendar days back clears at
        // least two full weekdays before it however the weekly-off falls,
        // so this is well past 4 working hours under any seeded calendar.
        long step = insertBreachedStep("Data migration", owner, NOW.minus(java.time.Duration.ofDays(5)));

        scanner.scanOnce();

        assertThat(escalatedTo(step, "L2")).isEqualTo(manager);
    }

    @Test
    @DisplayName("L3 fires to the earliest live OB Admin once well past eight working hours")
    void l3FiresToObAdmin() {
        long admin = insertUser("adm", null);
        grantObAdmin(admin);
        long step = insertBreachedStep("Data migration", owner, NOW.minus(java.time.Duration.ofDays(9)));

        scanner.scanOnce();

        assertThat(escalatedTo(step, "L3")).isEqualTo(admin);
    }

    @Test
    @DisplayName("L2 raised with no manager resolved leaves escalatedTo null rather than substituting anybody")
    void l2WithNoManagerIsStillRaised() {
        long ownerWithNoManager = insertUser("orphanOwner", null);
        long step = insertBreachedStep("Data migration", ownerWithNoManager, NOW.minus(java.time.Duration.ofDays(5)));

        scanner.scanOnce();

        assertThat(rungExists(step, "L2")).isTrue();
        assertThat(escalatedTo(step, "L2")).isNull();
    }

    // -------------------------------------------------------------- record & notify

    @Test
    @DisplayName("the rung is recorded in ob_step_history, attributed to nobody")
    void theRungIsRecordedAsSystem() {
        long step = insertBreachedStep("Data migration", owner, NOW.minusSeconds(600));

        scanner.scanOnce();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM ob_step_history WHERE step_id = ? AND event_type = 'ESCALATION_RAISED'", step);
        assertThat(row.get("actor_type")).isEqualTo("SYSTEM");
        assertThat(row.get("actor_id")).isNull();
        assertThat(row.get("new_value")).isEqualTo("L1");
    }

    @Test
    @DisplayName("the resolved recipient is notified by mail and bell")
    void theRecipientIsNotified() {
        insertBreachedStep("Data migration", owner, NOW.minusSeconds(600));

        scanner.scanOnce();

        Long emailRecipient = jdbc.queryForObject(
                "SELECT recipient_user_id FROM ob_notification_outbox "
                        + "WHERE event_key = 'ESCALATION_RAISED' AND channel = 'EMAIL' AND journey_id = ?",
                Long.class, journeyId);
        assertThat(emailRecipient).isEqualTo(owner);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ob_notification_outbox "
                        + "WHERE event_key = 'ESCALATION_RAISED' AND channel = 'IN_APP' AND journey_id = ?",
                Integer.class, journeyId)).isEqualTo(1);
    }

    // ------------------------------------------------------------- helpers

    private long insertUser(String username, Long reportingManagerId) {
        Long roleId = jdbc.queryForObject("SELECT id FROM roles ORDER BY id LIMIT 1", Long.class);
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id, reporting_manager_id)
                VALUES (?, ?, ?, 'not-a-real-hash', ?, ?, ?)
                """, username, username, username + "@edunext.test", username, roleId, reportingManagerId);
        return lastInsertId();
    }

    private void grantObAdmin(long userId) {
        jdbc.update("""
                INSERT INTO user_module_access (user_id, module, module_role, granted_at)
                VALUES (?, 'ONBOARDING', 'OB_ADMIN', ?)
                """, userId, Timestamp.from(NOW.minusSeconds(3600)));
    }

    private long insertClient(String name) {
        jdbc.update("INSERT INTO ob_clients (name, onboarding_date) VALUES (?, '2026-08-01')", name);
        return lastInsertId();
    }

    private long insertProduct(String code, String name) {
        jdbc.update("INSERT INTO ob_products (code, name) VALUES (?, ?)", code, name);
        return lastInsertId();
    }

    private long insertTemplate(long product) {
        jdbc.update("INSERT INTO ob_journey_templates (product_id, name) VALUES (?, 'Standard')", product);
        return lastInsertId();
    }

    private long insertJourney(long client, long product, long template) {
        jdbc.update("INSERT INTO ob_client_applications (ob_client_id, product_id) VALUES (?, ?)",
                client, product);
        jdbc.update("""
                INSERT INTO ob_journeys (ob_client_id, product_id, template_id,
                                         gate_status, gate_opened_at, started_at)
                VALUES (?, ?, ?, 'OPEN', ?, ?)
                """, client, product, template,
                Timestamp.from(NOW.minusSeconds(30L * 86_400)), Timestamp.from(NOW.minusSeconds(30L * 86_400)));
        return lastInsertId();
    }

    private long insertStep(String name, long ownerId, String status, Instant tatBreachedAt) {
        jdbc.update("""
                INSERT INTO ob_journey_steps (journey_id, sequence, name, tat_days, owner_user_id,
                                              status, due_at, tat_breached_at, started_at, created_at)
                VALUES (?, ?, ?, 3, ?, ?, ?, ?, ?, ?)
                """, journeyId, ++sequence, name, ownerId, status,
                Timestamp.from(NOW.minusSeconds(3600)),
                tatBreachedAt == null ? null : Timestamp.from(tatBreachedAt),
                Timestamp.from(NOW.minusSeconds(864000)), Timestamp.from(NOW.minusSeconds(864000)));
        return lastInsertId();
    }

    private long insertBreachedStep(String name, long ownerId, Instant tatBreachedAt) {
        return insertStep(name, ownerId, "IN_PROGRESS", tatBreachedAt);
    }

    private long lastInsertId() {
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? 0L : id;
    }

    /** {@code null} both when no such rung exists yet and when it exists with nobody resolved. */
    private Long escalatedTo(long stepId, String level) {
        var rows = jdbc.queryForList(
                "SELECT escalated_to FROM ob_escalations WHERE step_id = ? AND level = ?",
                Long.class, stepId, level);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private boolean rungExists(long stepId, String level) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ob_escalations WHERE step_id = ? AND level = ?",
                Integer.class, stepId, level);
        return count != null && count > 0;
    }

    private int countRungs(String level) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ob_escalations WHERE journey_id = ? AND level = ?",
                Integer.class, journeyId, level);
        return count == null ? 0 : count;
    }
}
