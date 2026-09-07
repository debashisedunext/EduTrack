package com.edunext.edutrack.worker.onboarding.tat;

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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C-113 · the TAT scanner against a real MySQL 8.4 — {@code SlaScannerIT}'s
 * own shape, one module over.
 *
 * <p>The clock is fixed to a Monday mid-morning, exactly {@code SlaScannerIT}'s
 * own choice and for the same reason: every assertion here is about a moment
 * relative to {@code due_at}, and "now" meaning something different on every
 * run would make the working-calendar assertions unrepeatable.
 */
@Testcontainers
@SpringBootTest(classes = com.edunext.edutrack.worker.WorkerApplication.class)
@Import(ObTatScannerIT.FixedClock.class)
class ObTatScannerIT {

    private static final Instant NOW = Instant.parse("2026-08-10T10:00:00Z");

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_ob_tat_it")
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
        // Every scanner and digest sharing this process is a fixedDelay that
        // fires at context startup — SlaScannerIT's own account of the
        // deadlock this avoids. Every test here drives scanOnce() directly.
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
    @Autowired ObTatScanner scanner;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private long owner;
    private long backupOwner;
    private long clientId;
    private long productId;
    private long journeyId;
    private int run;

    @BeforeEach
    void seed() {
        run = SEQ.incrementAndGet();
        owner = insertUser("own" + run);
        backupOwner = insertUser("bkp" + run);
        clientId = insertClient("Horizon Academy " + run);
        productId = insertProduct("P" + run, "Learning Management " + run);
        long templateId = insertTemplate(productId);
        journeyId = insertJourney(clientId, productId, templateId);
    }

    // ------------------------------------------------------------ detection

    @Test
    @DisplayName("an in-progress step past its due date is flagged")
    void anOverdueStepIsFlagged() {
        long step = insertStep("Data migration", "IN_PROGRESS", owner, null, NOW.minusSeconds(3600));

        assertThat(scanner.scanOnce()).isEqualTo(1);

        assertThat(tatBreachedAt(step)).isNotNull();
    }

    @Test
    @DisplayName("a step still inside its due date is left alone")
    void aStepWithTimeLeftIsUntouched() {
        long step = insertStep("Data migration", "IN_PROGRESS", owner, null, NOW.plusSeconds(3600));

        assertThat(scanner.scanOnce()).isZero();

        assertThat(tatBreachedAt(step)).isNull();
    }

    @Test
    @DisplayName("§5.7: internal BLOCKED does not pause the clock, so it still breaches")
    void aBlockedStepStillBreaches() {
        long step = insertStep("Config", "BLOCKED", owner, null, NOW.minusSeconds(3600));

        assertThat(scanner.scanOnce()).isEqualTo(1);

        assertThat(tatBreachedAt(step)).isNotNull();
    }

    @Test
    @DisplayName("§5.7: WAITING_ON_CLIENT has a paused clock and is never flagged")
    void aWaitingOnClientStepIsNeverFlagged() {
        // The frozen due_at from before the pause — exactly the shape resume()
        // recomputes. Flagging it here would announce a breach the step has
        // not actually run into while its clock is stopped.
        long step = insertStep("Docs", "WAITING_ON_CLIENT", owner, null, NOW.minusSeconds(3600));

        assertThat(scanner.scanOnce()).isZero();

        assertThat(tatBreachedAt(step)).isNull();
    }

    @Test
    @DisplayName("a done step is not a candidate, however old its due date")
    void aDoneStepIsInvisibleToTheScan() {
        long step = insertStep("Finished late", "DONE", owner, null, NOW.minusSeconds(864000));

        assertThat(scanner.scanOnce()).isZero();
        assertThat(tatBreachedAt(step)).isNull();
    }

    @Test
    @DisplayName("a second pass does not flag the same step again")
    void flaggingHappensOnce() {
        insertStep("Data migration", "IN_PROGRESS", owner, null, NOW.minusSeconds(3600));
        scanner.scanOnce();
        int afterFirst = countOutboxRows();

        assertThat(scanner.scanOnce()).isZero();

        // Otherwise every fifteen minutes, forever, until somebody notices.
        assertThat(countOutboxRows()).isEqualTo(afterFirst);
    }

    // ------------------------------------------------------ what is waiting

    @Test
    @DisplayName("a journey still behind the prerequisite gate is not scanned")
    void lockedJourneysAreNotScanned() {
        jdbc.update("UPDATE ob_journeys SET gate_status = 'LOCKED', gate_opened_at = NULL WHERE id = ?",
                journeyId);
        long step = insertStep("Data migration", "IN_PROGRESS", owner, null, NOW.minusSeconds(3600));

        assertThat(scanner.scanOnce()).isZero();
        assertThat(tatBreachedAt(step)).isNull();
    }

    @Test
    @DisplayName("a client somebody put on hold is not chased")
    void onHoldClientsAreNotChased() {
        jdbc.update("UPDATE ob_clients SET overall_status = 'ON_HOLD' WHERE id = ?", clientId);
        long step = insertStep("Data migration", "IN_PROGRESS", owner, null, NOW.minusSeconds(3600));

        assertThat(scanner.scanOnce()).isZero();
        assertThat(tatBreachedAt(step)).isNull();
    }

    // --------------------------------------------------------- the record of it

    @Test
    @DisplayName("the breach is recorded in ob_step_history, attributed to nobody")
    void theBreachIsRecordedAsSystem() {
        long step = insertStep("Data migration", "IN_PROGRESS", owner, null, NOW.minusSeconds(3600));

        scanner.scanOnce();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM ob_step_history WHERE step_id = ? AND event_type = 'TAT_BREACHED'", step);

        assertThat(row.get("journey_id")).isEqualTo(journeyId);
        assertThat(row.get("actor_type")).isEqualTo("SYSTEM");
        assertThat(row.get("actor_id")).isNull();
        assertThat((String) row.get("remarks")).contains("working hours");
    }

    @Test
    @DisplayName("a step flagged once carries exactly one such history row")
    void theHistoryRowIsWrittenOnce() {
        long step = insertStep("Data migration", "IN_PROGRESS", owner, null, NOW.minusSeconds(3600));

        scanner.scanOnce();
        scanner.scanOnce();

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ob_step_history WHERE step_id = ? AND event_type = 'TAT_BREACHED'",
                Integer.class, step)).isEqualTo(1);
    }

    // ------------------------------------------------------------- who is told

    @Test
    @DisplayName("the owner and the backup owner are both notified, by mail and bell")
    void ownerAndBackupOwnerAreNotified() {
        insertStep("Data migration", "IN_PROGRESS", owner, backupOwner, NOW.minusSeconds(3600));

        scanner.scanOnce();

        List<Long> recipients = jdbc.queryForList(
                "SELECT recipient_user_id FROM ob_notification_outbox "
                        + "WHERE event_key = 'TAT_BREACHED' AND channel = 'EMAIL' AND journey_id = ?",
                Long.class, journeyId);
        assertThat(recipients).containsExactlyInAnyOrder(owner, backupOwner);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ob_notification_outbox "
                        + "WHERE event_key = 'TAT_BREACHED' AND channel = 'IN_APP' AND journey_id = ?",
                Integer.class, journeyId)).isEqualTo(2);
    }

    @Test
    @DisplayName("a step with no owner and no backup owner still breaches, with nobody to tell")
    void anUnresolvedStepIsStillFlagged() {
        long step = insertStepNoOwner("Orphan", "IN_PROGRESS", NOW.minusSeconds(3600));

        assertThat(scanner.scanOnce()).isEqualTo(1);

        assertThat(tatBreachedAt(step)).isNotNull();
        assertThat(countOutboxRows()).isZero();
    }

    // ------------------------------------------------------------- D-027

    @Test
    @DisplayName("the overrun reported is working hours, not wall-clock hours")
    void theOverdueDurationComesFromTheWorkingCalendar() {
        // Overdue since Friday evening; it is now Monday morning. Wall clock
        // is about 64 hours, almost all of it a weekend nobody was working.
        long step = insertStep("Weekend", "IN_PROGRESS", owner, null,
                Instant.parse("2026-08-07T18:00:00Z"));

        scanner.scanOnce();

        String remarks = jdbc.queryForObject(
                "SELECT remarks FROM ob_step_history WHERE step_id = ? AND event_type = 'TAT_BREACHED'",
                String.class, step);
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("Overdue by ([0-9.]+) working hours").matcher(remarks);
        assertThat(m.find()).as("the remark states a working-hour figure").isTrue();
        double reported = Double.parseDouble(m.group(1));

        assertThat(reported)
                .as("a weekend must not be billed as working time (wall clock is ~64h)")
                .isLessThan(24.0);
        assertThat(reported)
                .as("Monday morning is working time, so this cannot be zero")
                .isGreaterThan(0.0);
    }

    // ------------------------------------------------------------- helpers

    private long insertUser(String username) {
        Long roleId = jdbc.queryForObject("SELECT id FROM roles ORDER BY id LIMIT 1", Long.class);
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id)
                VALUES (?, ?, ?, 'not-a-real-hash', ?, ?)
                """, username, username, username + "@edunext.test", username, roleId);
        return lastInsertId();
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

    private int sequence = 0;

    private long insertStep(String name, String status, long ownerId, Long backupOwnerId, Instant dueAt) {
        jdbc.update("""
                INSERT INTO ob_journey_steps (journey_id, sequence, name, tat_days, owner_user_id,
                                              backup_owner_user_id, status, blocked_reason_code,
                                              due_at, started_at, created_at)
                VALUES (?, ?, ?, 3, ?, ?, ?, ?, ?, ?, ?)
                """, journeyId, ++sequence, name, ownerId, backupOwnerId, status,
                "BLOCKED".equals(status) ? "CLIENT_DEPENDENCY" : null,
                Timestamp.from(dueAt), Timestamp.from(NOW.minusSeconds(864000)),
                Timestamp.from(NOW.minusSeconds(864000)));
        return lastInsertId();
    }

    private long insertStepNoOwner(String name, String status, Instant dueAt) {
        jdbc.update("""
                INSERT INTO ob_journey_steps (journey_id, sequence, name, tat_days,
                                              status, due_at, started_at, created_at)
                VALUES (?, ?, ?, 3, ?, ?, ?, ?)
                """, journeyId, ++sequence, name, status,
                Timestamp.from(dueAt), Timestamp.from(NOW.minusSeconds(864000)),
                Timestamp.from(NOW.minusSeconds(864000)));
        return lastInsertId();
    }

    private long lastInsertId() {
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? 0L : id;
    }

    private Timestamp tatBreachedAt(long stepId) {
        return jdbc.queryForObject(
                "SELECT tat_breached_at FROM ob_journey_steps WHERE id = ?", Timestamp.class, stepId);
    }

    /**
     * Scoped to this test's own journey. Nothing is deleted between tests —
     * {@code ObManagerDigestIT}'s own note applies here too: {@code
     * ob_step_history} is append-only, and the steps its rows point at
     * cannot be removed while it holds rows against them — so an unscoped
     * count would depend on every earlier test in the class.
     */
    private int countOutboxRows() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ob_notification_outbox WHERE event_key = 'TAT_BREACHED' AND journey_id = ?",
                Integer.class, journeyId);
        return count == null ? 0 : count;
    }
}
