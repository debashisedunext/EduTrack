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
 * D-023 · the stage-SLA scanner, against a real schema.
 */
@Testcontainers
@SpringBootTest(classes = com.edunext.edutrack.worker.WorkerApplication.class)
class StageSlaScannerIT {

    /** Monday mid-morning, so "now" sits inside a working day. */
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
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("edutrack.sla.stage-scan-interval", () -> "PT1H");
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
    StageSlaScanner scanner;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private long stageOwner;
    private long manager;
    private long projectManager;
    private long projectId;
    private long templateId;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM stage_sla_alerts");
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM email_log");
        jdbc.update("DELETE FROM ticket_stage_transitions");
        jdbc.update("DELETE FROM tickets");

        int n = SEQ.incrementAndGet();
        manager = insertUser("srm" + n, null);
        stageOwner = insertUser("sqa" + n, manager);
        projectManager = insertUser("spm" + n, null);
        projectId = insertProject("SP" + n, projectManager);
        templateId = insertTemplate("T" + n, "QA", 4.00);
    }

    // ------------------------------------------------------------ the point

    @Test
    @DisplayName("§16 3b: a ticket inside its PCD can still be rotting in one stage")
    void aStageBreachesEvenWhenTheTicketIsNotLate() {
        // Planned Close Date is a week away — D-020 would never look at this.
        long ticket = insertTicket("STG-1", NOW.plusSeconds(604800));
        enterStage(ticket, "QA", Instant.parse("2026-08-06T09:00:00Z"));

        assertThat(scanner.scanOnce()).isEqualTo(1);

        assertThat(isDelayed(ticket))
                .as("the ticket-level SLA is untouched — this is a different alarm")
                .isFalse();
        assertThat(notifiedUserIds(ticket)).contains(stageOwner);
    }

    @Test
    @DisplayName("a stage still inside its budget is not announced")
    void aFreshStageIsLeftAlone() {
        long ticket = insertTicket("STG-2", NOW.plusSeconds(604800));
        enterStage(ticket, "QA", NOW.minusSeconds(1800));

        assertThat(scanner.scanOnce()).isZero();
        assertThat(countAlerts()).isZero();
    }

    @Test
    @DisplayName("a sealed stage is not a stage anybody is sitting in")
    void aClosedSegmentIsIgnored() {
        long ticket = insertTicket("STG-3", NOW.plusSeconds(604800));
        long transition = enterStage(ticket, "QA", Instant.parse("2026-08-03T09:00:00Z"));
        jdbc.update("UPDATE ticket_stage_transitions SET exited_at = ?, is_current = 0 WHERE id = ?",
                java.sql.Timestamp.from(NOW.minusSeconds(3600)), transition);

        assertThat(scanner.scanOnce()).isZero();
    }

    @Test
    @DisplayName("a stage with no SLA configured never breaches")
    void aStageWithoutABudgetIsSkipped() {
        long noSla = insertTemplate("N" + SEQ.incrementAndGet(), "QA", null);
        long ticket = insertTicketOn(noSla, "STG-4", NOW.plusSeconds(604800));
        enterStage(ticket, "QA", Instant.parse("2026-08-03T09:00:00Z"));

        assertThat(scanner.scanOnce()).isZero();
    }

    // --------------------------------------------------------- D-027 again

    @Test
    @DisplayName("the stage budget is working hours, so a weekend does not spend it")
    void aWeekendDoesNotConsumeTheStageSla() {
        // Handed to QA at 17:00 on Friday with a 4-working-hour SLA. By Monday
        // 10:00 the wall clock says 65 hours; the working calendar does not.
        // Comparing wall-clock elapsed against a working-hours budget is the
        // Friday-evening bug from §5, one level down.
        long ticket = insertTicket("STG-5", NOW.plusSeconds(604800));
        enterStage(ticket, "QA", Instant.parse("2026-08-07T17:00:00Z"));

        int announced = scanner.scanOnce();

        assertThat(announced)
                .as("only about an hour of working time has passed, against a 4-hour SLA")
                .isZero();
    }

    @Test
    @DisplayName("the alert quotes elapsed working hours and the budget")
    void theAlertSaysHowFarOver() {
        long ticket = insertTicket("STG-6", NOW.plusSeconds(604800));
        enterStage(ticket, "QA", Instant.parse("2026-08-06T09:00:00Z"));

        scanner.scanOnce();

        String body = jdbc.queryForObject(
                "SELECT body FROM notifications WHERE ticket_id = ? LIMIT 1", String.class, ticket);
        assertThat(body).contains("working hours in this stage against a 4.00-hour SLA");
    }

    // ------------------------------------------------------------- once only

    @Test
    @DisplayName("a stuck stage is announced once, not every fifteen minutes")
    void announcementHappensOnce() {
        long ticket = insertTicket("STG-7", NOW.plusSeconds(604800));
        enterStage(ticket, "QA", Instant.parse("2026-08-06T09:00:00Z"));
        scanner.scanOnce();
        int after = countNotifications();

        assertThat(scanner.scanOnce()).isZero();
        assertThat(countNotifications()).isEqualTo(after);
    }

    @Test
    @DisplayName("re-entering the same stage is a new segment and can breach again")
    void asecondVisitToAStageIsItsOwnBreach() {
        long ticket = insertTicket("STG-8", NOW.plusSeconds(604800));
        long first = enterStage(ticket, "QA", Instant.parse("2026-08-05T09:00:00Z"));
        scanner.scanOnce();

        // QA failed, back to DEV, then returned to QA — a rework loop, which is
        // exactly the case §16 3b cares about.
        jdbc.update("UPDATE ticket_stage_transitions SET exited_at = ?, is_current = 0 WHERE id = ?",
                java.sql.Timestamp.from(Instant.parse("2026-08-05T12:00:00Z")), first);
        enterStage(ticket, "QA", Instant.parse("2026-08-06T09:00:00Z"));

        assertThat(scanner.scanOnce())
                .as("the second visit is its own segment with its own budget")
                .isEqualTo(1);
        assertThat(countAlerts()).isEqualTo(2);
    }

    // ------------------------------------------------------------- who hears

    @Test
    @DisplayName("the stage owner is told, not the ticket assignee")
    void theStageOwnerIsTheOneWhoCanUnstickIt() {
        long otherAssignee = insertUser("other" + SEQ.incrementAndGet(), null);
        long ticket = insertTicket("STG-9", NOW.plusSeconds(604800));
        jdbc.update("UPDATE tickets SET assigned_to = ? WHERE id = ?", otherAssignee, ticket);
        enterStage(ticket, "QA", Instant.parse("2026-08-06T09:00:00Z"));

        scanner.scanOnce();

        // The person who can move it is whoever it was handed to.
        assertThat(notifiedUserIds(ticket))
                .contains(stageOwner)
                .doesNotContain(otherAssignee);
    }

    @Test
    void theStageOwnersManagerAndTheProjectManagerAreToldToo() {
        long ticket = insertTicket("STG-10", NOW.plusSeconds(604800));
        enterStage(ticket, "QA", Instant.parse("2026-08-06T09:00:00Z"));

        scanner.scanOnce();

        assertThat(notifiedUserIds(ticket))
                .containsExactlyInAnyOrder(stageOwner, manager, projectManager)
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("an unowned stage still records the breach")
    void aQueuedStageWithNoOwnerIsStillAnnounced() {
        long ticket = insertTicket("STG-11", NOW.plusSeconds(604800));
        long transition = enterStage(ticket, "QA", Instant.parse("2026-08-06T09:00:00Z"));
        jdbc.update("UPDATE ticket_stage_transitions SET to_user_id = NULL WHERE id = ?", transition);

        assertThat(scanner.scanOnce()).isEqualTo(1);
        assertThat(notifiedUserIds(ticket)).containsExactly(projectManager);
    }

    // ------------------------------------------------------------- helpers

    private long insertTicket(String code, Instant plannedClose) {
        return insertTicketOn(templateId, code, plannedClose);
    }

    private long insertTicketOn(long template, String code, Instant plannedClose) {
        jdbc.update("""
                INSERT INTO tickets (ticket_code, project_id, title, task_type_id, level,
                                     original_level, status, current_stage, reported_by,
                                     assigned_to, planned_close_date, date_reported,
                                     workflow_template_id)
                SELECT ?, ?, ?, MIN(tt.id), 'HIGH', 'HIGH', 'OPEN', 'QA', ?, ?, ?, ?, ?
                  FROM task_types tt
                """, code, projectId, "Stage fixture " + code, projectManager, stageOwner,
                java.sql.Timestamp.from(plannedClose),
                java.sql.Timestamp.from(NOW.minusSeconds(864000)), template);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    /** One open segment: entered, never exited. */
    private long enterStage(long ticketId, String stage, Instant enteredAt) {
        Integer seq = jdbc.queryForObject(
                "SELECT COALESCE(MAX(seq_no), 0) + 1 FROM ticket_stage_transitions WHERE ticket_id = ?",
                Integer.class, ticketId);
        jdbc.update("""
                INSERT INTO ticket_stage_transitions
                       (ticket_id, cycle_no, seq_no, from_stage, to_stage, to_user_id,
                        action_code, entered_at, is_current)
                VALUES (?, 1, ?, 'DEVELOPMENT', ?, ?, 'FORWARD', ?, 1)
                """, ticketId, seq, stage, stageOwner, java.sql.Timestamp.from(enteredAt));
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertTemplate(String code, String stage, Double slaHours) {
        jdbc.update("INSERT INTO workflow_templates (name, is_active) VALUES (?, 1)",
                "Template " + code);
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO workflow_stages (template_id, seq, stage_code, display_name,
                                             owner_role, sla_hours)
                VALUES (?, 1, ?, ?, 'QA', ?)
                """, id, stage, stage, slaHours);
        return id;
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

    private boolean isDelayed(long ticketId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT is_delayed FROM tickets WHERE id = ?", Boolean.class, ticketId));
    }

    private List<Long> notifiedUserIds(long ticketId) {
        return jdbc.queryForList("""
                SELECT user_id FROM notifications
                 WHERE ticket_id = ? AND event_code = 'STAGE_SLA_BREACHED'
                """, Long.class, ticketId);
    }

    private int countAlerts() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM stage_sla_alerts", Integer.class);
        return n == null ? 0 : n;
    }

    private int countNotifications() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM notifications", Integer.class);
        return n == null ? 0 : n;
    }
}
