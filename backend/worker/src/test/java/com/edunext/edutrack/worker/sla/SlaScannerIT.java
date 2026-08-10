package com.edunext.edutrack.worker.sla;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-020 · the SLA scanner against a real schema.
 *
 * <p>The clock is fixed rather than {@code systemUTC}, because every assertion
 * here is about a moment relative to a Planned Close Date and a test that says
 * "now" means something different every time it runs.
 */
@Testcontainers
@SpringBootTest(classes = com.edunext.edutrack.worker.WorkerApplication.class)
class SlaScannerIT {

    /** A Monday, mid-morning — a working hour on any sane calendar. */
    private static final Instant NOW = Instant.parse("2026-08-10T10:00:00Z");

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
        // The worker never migrates in production — api owns that — so
        // application.yml disables Flyway and the test has to turn it back on
        // to get a schema at all.
        registry.add("spring.flyway.enabled", () -> true);
        // The scheduled trigger is not under test; every case here drives
        // scanOnce() directly so nothing fires behind the assertions.
        registry.add("edutrack.sla.scan-interval", () -> "PT1H");
        registry.add("edutrack.outbox.enabled", () -> "false");
    }

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SlaScanner scanner;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private long assignee;
    private long manager;
    private long projectManager;
    private long projectId;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM email_log");
        jdbc.update("DELETE FROM tickets");

        int n = SEQ.incrementAndGet();
        manager = insertUser("rm" + n, null);
        assignee = insertUser("dev" + n, manager);
        projectManager = insertUser("pm" + n, null);
        projectId = insertProject("P" + n, projectManager);
    }

    // ------------------------------------------------------------ detection

    @Test
    @DisplayName("a ticket past its planned close date is escalated")
    void anOverdueTicketBecomesCritical() {
        long id = insertTicket("overdue", NOW.minusSeconds(3600), "HIGH");

        assertThat(scanner.scanOnce()).isEqualTo(1);

        assertThat(levelOf(id)).isEqualTo("CRITICAL");
        assertThat(isDelayed(id)).isTrue();
        assertThat(delayedSince(id)).isNotNull();
    }

    @Test
    @DisplayName("a ticket still inside its date is left alone")
    void aTicketWithTimeLeftIsUntouched() {
        long id = insertTicket("in-time", NOW.plusSeconds(3600), "HIGH");

        assertThat(scanner.scanOnce()).isZero();

        assertThat(levelOf(id)).isEqualTo("HIGH");
        assertThat(isDelayed(id)).isFalse();
    }

    @Test
    @DisplayName("a closed ticket never breaches, however old its date")
    void aClosedTicketIsInvisibleToTheScan() {
        long id = insertTicket("closed", NOW.minusSeconds(864000), "HIGH");
        jdbc.update("UPDATE tickets SET actual_close_date = ?, status = 'CLOSED' WHERE id = ?",
                java.sql.Timestamp.from(NOW.minusSeconds(3600)), id);

        // This is pcd_open doing its job: the generated column goes NULL on
        // close, so the row leaves the index range entirely.
        assertThat(scanner.scanOnce()).isZero();
        assertThat(isDelayed(id)).isFalse();
    }

    @Test
    @DisplayName("a second pass does not escalate the same ticket again")
    void escalationHappensOnce() {
        insertTicket("overdue", NOW.minusSeconds(3600), "HIGH");
        scanner.scanOnce();
        int notificationsAfterFirst = countNotifications();

        assertThat(scanner.scanOnce()).isZero();

        // Otherwise every fifteen minutes, forever, until somebody closes it.
        assertThat(countNotifications()).isEqualTo(notificationsAfterFirst);
    }

    // ----------------------------------------------------------- who is told

    @Test
    @DisplayName("§16: assignee, reporting manager and project manager")
    void allThreeAreNotified() {
        long id = insertTicket("overdue", NOW.minusSeconds(3600), "HIGH");

        scanner.scanOnce();

        assertThat(notifiedUserIds(id))
                .containsExactlyInAnyOrder(assignee, manager, projectManager);
    }

    @Test
    @DisplayName("one person filling two roles is told once")
    void recipientsAreDeduplicated() {
        // A PM who assigned the ticket to themselves — routine, and three
        // copies of the same breach is how people learn to filter the alert.
        long soloProject = insertProject("SOLO" + SEQ.incrementAndGet(), assignee);
        long id = insertTicketOn(soloProject, "overdue", NOW.minusSeconds(3600), "HIGH", assignee);

        scanner.scanOnce();

        assertThat(notifiedUserIds(id)).containsExactly(assignee);
    }

    @Test
    @DisplayName("an unassigned ticket still escalates, with nobody to tell but the PM")
    void anUnassignedTicketIsStillFlagged() {
        long id = insertTicketOn(projectId, "orphan", NOW.minusSeconds(3600), "HIGH", null);

        assertThat(scanner.scanOnce()).isEqualTo(1);

        assertThat(isDelayed(id)).isTrue();
        assertThat(notifiedUserIds(id)).containsExactly(projectManager);
    }

    @Test
    @DisplayName("the breach mail is queued for every recipient")
    void mailIsQueuedAlongsideTheBellEntry() {
        long id = insertTicket("overdue", NOW.minusSeconds(3600), "HIGH");

        scanner.scanOnce();

        // SLA_BREACHED is mandatory mail (D-036), so no preference suppresses
        // it, and D-031 puts the ticket code at the front of the subject.
        List<String> subjects = jdbc.queryForList(
                "SELECT subject FROM email_log WHERE ticket_id = ?", String.class, id);
        assertThat(subjects).hasSize(3);
        assertThat(subjects).allSatisfy(s -> assertThat(s).startsWith("[overdue]"));
    }

    @Test
    @DisplayName("a deactivated recipient keeps the bell entry but gets no mail")
    void anInactiveUserIsNotMailed() {
        long id = insertTicket("overdue", NOW.minusSeconds(3600), "HIGH");
        jdbc.update("UPDATE users SET is_active = 0 WHERE id = ?", assignee);

        scanner.scanOnce();

        assertThat(notifiedUserIds(id)).contains(assignee);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM email_log WHERE ticket_id = ? AND to_user_id = ?",
                Integer.class, id, assignee)).isZero();
    }

    // ------------------------------------------------------------ D-027

    @Test
    @DisplayName("the alert reports working hours, not wall-clock hours")
    void theOverdueDurationComesFromTheWorkingCalendar() {
        // Breached on Friday evening; it is now Monday morning. Wall-clock is
        // about 63 hours, almost all of it a weekend nobody was working.
        long id = insertTicket("weekend", Instant.parse("2026-08-07T18:00:00Z"), "HIGH");

        scanner.scanOnce();

        String body = jdbc.queryForObject(
                "SELECT body FROM notifications WHERE ticket_id = ? LIMIT 1", String.class, id);
        assertThat(body).contains("working hours");

        BigDecimalAssert.assertReportedHoursAreFarBelowWallClock(body);
    }

    /** Keeps the arithmetic of the assertion above out of the test body. */
    static final class BigDecimalAssert {
        static void assertReportedHoursAreFarBelowWallClock(String body) {
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("Overdue by ([0-9.]+) working hours")
                            .matcher(body);
            assertThat(m.find()).as("the body states a working-hour figure").isTrue();
            double reported = Double.parseDouble(m.group(1));

            // The exact figure depends on B-023's seeded calendar; what must
            // hold is that a weekend was not counted as working time. Wall
            // clock here is ~64h, so anything near it means the calendar was
            // bypassed and D-027 was not honoured.
            assertThat(reported)
                    .as("a weekend must not be billed as working hours")
                    .isLessThan(24.0);
        }
    }

    // ------------------------------------------------------------- helpers

    private long insertTicket(String code, Instant plannedClose, String level) {
        return insertTicketOn(projectId, code, plannedClose, level, assignee);
    }

    private long insertTicketOn(long project, String code, Instant plannedClose,
                                String level, Long assignedTo) {
        jdbc.update("""
                INSERT INTO tickets (ticket_code, project_id, title, task_type_id, level,
                                     original_level, status, current_stage, reported_by,
                                     assigned_to, planned_close_date, date_reported)
                SELECT ?, ?, ?, MIN(tt.id), ?, ?, 'OPEN', 'DEVELOPMENT', ?, ?, ?, ?
                  FROM task_types tt
                """, code, project, "SLA fixture " + code, level, level,
                projectManager, assignedTo,
                java.sql.Timestamp.from(plannedClose),
                java.sql.Timestamp.from(NOW.minusSeconds(864000)));
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertProject(String code, Long managerId) {
        jdbc.update("INSERT INTO projects (project_code, name, manager_id) VALUES (?, ?, ?)",
                code, "Project " + code, managerId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertUser(String username, Long reportingManagerId) {
        Long roleId = jdbc.queryForObject("SELECT id FROM roles ORDER BY id LIMIT 1", Long.class);
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name,
                                   role_id, reporting_manager_id)
                VALUES (?, ?, ?, 'not-a-real-hash', ?, ?, ?)
                """, username, username, username + "@edunext.test", username,
                roleId, reportingManagerId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private String levelOf(long id) {
        return jdbc.queryForObject("SELECT level FROM tickets WHERE id = ?", String.class, id);
    }

    private boolean isDelayed(long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT is_delayed FROM tickets WHERE id = ?", Boolean.class, id));
    }

    private java.sql.Timestamp delayedSince(long id) {
        return jdbc.queryForObject("SELECT delayed_since FROM tickets WHERE id = ?",
                java.sql.Timestamp.class, id);
    }

    private List<Long> notifiedUserIds(long ticketId) {
        return jdbc.queryForList(
                "SELECT user_id FROM notifications WHERE ticket_id = ? AND event_code = 'SLA_BREACHED'",
                Long.class, ticketId);
    }

    private int countNotifications() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM notifications", Integer.class);
        return count == null ? 0 : count;
    }
}
