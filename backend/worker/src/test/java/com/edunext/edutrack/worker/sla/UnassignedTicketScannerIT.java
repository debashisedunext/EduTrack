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
 * D-026 · the unassigned-ticket triage alert.
 *
 * <p>Calendar is Asia/Kolkata 09:30–18:30 (B-023), so instants are chosen in
 * IST and written in UTC. NOW is <b>Monday 2026-08-10, 14:00 IST</b> —
 * mid-morning on a working day, so a ticket raised at 11:00 IST has waited
 * three working hours and one raised at 13:30 has waited half of one.
 *
 * <p><b>{@code @Import(FixedClock.class)} is required</b>: a nested
 * {@code @TestConfiguration} is auto-detected only when {@code @SpringBootTest}
 * declares no {@code classes}, and this one does.
 */
@Testcontainers
@SpringBootTest(classes = com.edunext.edutrack.worker.WorkerApplication.class)
@org.springframework.context.annotation.Import(UnassignedTicketScannerIT.FixedClock.class)
class UnassignedTicketScannerIT {

    /** Monday 2026-08-10, 14:00 IST. */
    private static final Instant NOW = Instant.parse("2026-08-10T08:30:00Z");

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
        registry.add("edutrack.sla.unassigned-scan-interval", () -> "PT6H");
        registry.add("edutrack.sla.scan-interval", () -> "PT6H");
        registry.add("edutrack.sla.stage-scan-interval", () -> "PT6H");
        registry.add("edutrack.sla.pre-breach-scan-interval", () -> "PT6H");
        registry.add("edutrack.sla.stale-scan-interval", () -> "PT6H");
        registry.add("edutrack.sla.l2-scan-interval", () -> "PT6H");
        registry.add("edutrack.sla.ping-pong-scan-interval", () -> "PT6H");
        registry.add("edutrack.outbox.enabled", () -> "false");
    }

    @TestConfiguration
    public static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UnassignedTicketScanner scanner;

    @Autowired
    UnassignedTicketAlert alert;

    @Autowired
    Clock clock;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private long projectManager;
    private long supportAgent;
    private long developer;
    private long projectId;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM email_log");

        int n = SEQ.incrementAndGet();
        projectManager = insertUser("utpm" + n, "PM");
        supportAgent = insertUser("utsup" + n, "SUPPORT");
        developer = insertUser("utdev" + n, "DEVELOPER");
        projectId = insertProject("UT" + n, projectManager);
        addMember(projectId, supportAgent, null);
        addMember(projectId, developer, null);
    }

    @Test
    @DisplayName("the injected clock is the fixed one, not the wall clock")
    void theFixtureClockIsActuallyApplied() {
        assertThat(clock.instant()).isEqualTo(NOW);
    }

    // ----------------------------------------------------------- the window

    @Test
    @DisplayName("unassigned for three working hours — triage is told")
    void anUntouchedTicketIsRaised() {
        long ticket = insertTicket(Instant.parse("2026-08-10T05:30:00Z"));   // Mon 11:00 IST

        scanner.scanOnce();

        assertThat(alertsFor(ticket)).isEqualTo(1);
        assertThat(notifiedUserIds(ticket)).containsExactlyInAnyOrder(projectManager, supportAgent);
    }

    @Test
    @DisplayName("unassigned for half a working hour — left alone")
    void arecentTicketIsLeftAlone() {
        long ticket = insertTicket(Instant.parse("2026-08-10T08:00:00Z"));   // Mon 13:30 IST

        scanner.scanOnce();

        assertThat(alertsFor(ticket)).isZero();
        assertThat(notifiedUserIds(ticket)).isEmpty();
    }

    @Test
    @DisplayName("raised after hours on Friday, still quiet on Saturday — the calendar decides")
    void theWeekendDoesNotCountTowardsTheWindow() {
        // Friday 2026-08-07, 18:00 IST — half an hour before the day ends. By
        // the following Saturday afternoon only 30 working minutes have passed,
        // however many wall-clock hours have. A wall-clock threshold would have
        // raised this at 20:00 on Friday, to nobody who was working.
        long ticket = insertTicket(Instant.parse("2026-08-07T12:30:00Z"));

        boolean alerted = alert.alertIfUntouched(
                candidate(ticket, Instant.parse("2026-08-07T12:30:00Z"), null),
                Instant.parse("2026-08-08T08:30:00Z"));            // Sat 14:00 IST

        assertThat(alerted)
                .as("30 working minutes, not two working hours")
                .isFalse();
    }

    @Test
    @DisplayName("that same Friday ticket is raised on Monday morning")
    void theSameTicketIsRaisedOnceTheWeekIsBack() {
        long ticket = insertTicket(Instant.parse("2026-08-07T12:30:00Z"));   // Fri 18:00 IST

        // NOW is Monday 14:00 IST: 30 minutes on Friday plus 4.5 hours today.
        scanner.scanOnce();

        assertThat(alertsFor(ticket)).isEqualTo(1);
    }

    @Test
    @DisplayName("a backdated report time counts from the date on the ticket")
    void backdatingCountsFromTheReportedDate() {
        // §7.5 lets Admin and PM backdate date_reported. A call that came in on
        // Friday and was typed up on Monday has been waiting since Friday, and
        // that is the number triage should be judged on.
        long ticket = insertTicket(Instant.parse("2026-08-06T04:00:00Z"));   // Thu 09:30 IST

        scanner.scanOnce();

        assertThat(alertsFor(ticket)).isEqualTo(1);
    }

    // ------------------------------------------------------------- who, and
    // ------------------------------------------------------------- who not

    @Test
    @DisplayName("an assigned ticket is never triage's problem")
    void anAssignedTicketIsIgnored() {
        long ticket = insertTicket(Instant.parse("2026-08-10T05:30:00Z"));
        jdbc.update("UPDATE tickets SET assigned_to = ? WHERE id = ?", developer, ticket);

        scanner.scanOnce();

        assertThat(alertsFor(ticket)).isZero();
    }

    @Test
    @DisplayName("a closed ticket that was never assigned is not raised either")
    void aclosedTicketIsIgnored() {
        long ticket = insertTicket(Instant.parse("2026-08-10T05:30:00Z"));
        jdbc.update("UPDATE tickets SET actual_close_date = ?, status = 'CLOSED' WHERE id = ?",
                java.sql.Timestamp.from(NOW.minusSeconds(600)), ticket);

        scanner.scanOnce();

        assertThat(alertsFor(ticket)).isZero();
    }

    @Test
    @DisplayName("only the project's own support desk, not every support agent")
    void supportIsScopedToTheProject() {
        long outsider = insertUser("utsup-out" + SEQ.incrementAndGet(), "SUPPORT");
        long ticket = insertTicket(Instant.parse("2026-08-10T05:30:00Z"));

        scanner.scanOnce();

        // Mailing somebody about a ticket their scope would answer 404 for is
        // worse than not mailing them: it teaches the desk the alerts are noise.
        assertThat(notifiedUserIds(ticket))
                .containsExactlyInAnyOrder(projectManager, supportAgent)
                .doesNotContain(outsider);
    }

    @Test
    @DisplayName("a per-project role overrides the global one")
    void theProjectRoleWins() {
        // A-003: "a Developer globally can be mapped as QA on one project."
        // Here the reverse — a developer standing in on the desk this month.
        long standIn = insertUser("utstand" + SEQ.incrementAndGet(), "DEVELOPER");
        addMember(projectId, standIn, "SUPPORT");
        long ticket = insertTicket(Instant.parse("2026-08-10T05:30:00Z"));

        scanner.scanOnce();

        assertThat(notifiedUserIds(ticket)).contains(standIn);
    }

    @Test
    @DisplayName("an inactive support agent is not told")
    void aninactiveAgentIsSkipped() {
        jdbc.update("UPDATE users SET is_active = 0 WHERE id = ?", supportAgent);
        long ticket = insertTicket(Instant.parse("2026-08-10T05:30:00Z"));

        scanner.scanOnce();

        assertThat(notifiedUserIds(ticket)).containsExactly(projectManager);
    }

    // ----------------------------------------------------------- repeating

    @Test
    @DisplayName("the same ticket is not raised again on the next pass")
    void alertingDoesNotRepeatImmediately() {
        long ticket = insertTicket(Instant.parse("2026-08-10T05:30:00Z"));
        scanner.scanOnce();

        scanner.scanOnce();

        assertThat(notifiedUserIds(ticket)).as("two recipients, once each").hasSize(2);
    }

    @Test
    @DisplayName("still unassigned a working day later — raised again")
    void analertRepeatsAfterAWorkingDay() {
        long ticket = insertTicket(Instant.parse("2026-08-06T04:00:00Z"));
        scanner.scanOnce();

        // Backdate the whole row to Thursday morning: more than one nine-hour
        // working day has passed by Monday afternoon. Both columns, so that
        // `first_alerted_at` afterwards says something — backdating only
        // `alerted_at` would leave the two trivially equal and the invariant
        // below unprovable either way.
        java.sql.Timestamp thursday =
                java.sql.Timestamp.from(Instant.parse("2026-08-06T04:30:00Z"));
        jdbc.update("""
                UPDATE unassigned_ticket_alerts
                   SET alerted_at = ?, first_alerted_at = ?
                 WHERE ticket_id = ?
                """, thursday, thursday, ticket);

        scanner.scanOnce();

        assertThat(notifiedUserIds(ticket)).as("two recipients, twice").hasSize(4);
        assertThat(alertedAt(ticket))
                .as("the repeat moves the last-alerted stamp forward")
                .isEqualTo(java.sql.Timestamp.from(NOW));
        assertThat(firstAlertedAt(ticket))
                .as("the first alert is never rewritten — it is how long triage has been failing")
                .isEqualTo(thursday);
    }

    @Test
    @DisplayName("losing the insert race does not tell the desk twice")
    void alostInsertRaceDoesNotAlertAgain() {
        long ticket = insertTicket(Instant.parse("2026-08-10T05:30:00Z"));
        scanner.scanOnce();
        assertThat(notifiedUserIds(ticket)).hasSize(2);

        // The row a second worker would be holding: read before the first
        // committed, so it believes nobody has been told. This is the exact
        // shape of the defect D-025 found in D-022 — the fallback UPDATE's
        // guard going vacuously true — and the reason `repeatableIfBefore`
        // returns EPOCH rather than `now` for a never-alerted ticket.
        boolean alertedAgain = alert.alertIfUntouched(
                candidate(ticket, Instant.parse("2026-08-10T05:30:00Z"), null), NOW);

        assertThat(alertedAgain).isFalse();
        assertThat(notifiedUserIds(ticket)).hasSize(2);
    }

    @Test
    @DisplayName("being picked up clears the alert, so a later unassignment is immediate")
    void assigningForgetsTheAlert() {
        long ticket = insertTicket(Instant.parse("2026-08-06T04:00:00Z"));
        scanner.scanOnce();
        assertThat(alertsFor(ticket)).isEqualTo(1);

        jdbc.update("UPDATE tickets SET assigned_to = ? WHERE id = ?", developer, ticket);
        scanner.scanOnce();
        assertThat(alertsFor(ticket)).as("forgotten once somebody owns it").isZero();

        // Reassigned away — S-24 does this when a resource leaves. Without the
        // forget, the stale row would suppress this alert for a working day,
        // which is exactly when triage most needs telling.
        jdbc.update("UPDATE tickets SET assigned_to = NULL WHERE id = ?", ticket);
        scanner.scanOnce();

        assertThat(notifiedUserIds(ticket)).as("raised again, promptly").hasSize(4);
    }

    // ------------------------------------------------------------- helpers

    private UnassignedTicketRepository.UnassignedTicket candidate(
            long id, Instant reported, java.sql.Timestamp lastAlerted) {
        return new UnassignedTicketRepository.UnassignedTicket(
                id, "UT-race", "race", "MEDIUM", projectId,
                java.sql.Timestamp.from(reported), projectManager, lastAlerted);
    }

    private long insertTicket(Instant reported) {
        String code = "UT-" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO tickets (ticket_code, project_id, title, task_type_id, level,
                                     original_level, status, current_stage, reported_by,
                                     assigned_to, planned_close_date, date_reported)
                SELECT ?, ?, ?, MIN(tt.id), 'MEDIUM', 'MEDIUM', 'NEW', 'INTAKE', ?, NULL, ?, ?
                  FROM task_types tt
                """, code, projectId, "Unassigned fixture " + code, projectManager,
                java.sql.Timestamp.from(NOW.plusSeconds(2592000)),
                java.sql.Timestamp.from(reported));
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertProject(String code, long managerId) {
        jdbc.update("INSERT INTO projects (project_code, name, manager_id) VALUES (?, ?, ?)",
                code, "Project " + code, managerId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void addMember(long project, long user, String roleInProject) {
        jdbc.update("INSERT INTO project_members (project_id, user_id, role_in_project) VALUES (?, ?, ?)",
                project, user, roleInProject);
    }

    private long insertUser(String username, String roleCode) {
        Long roleId = jdbc.queryForObject(
                "SELECT id FROM roles WHERE code = ? LIMIT 1", Long.class, roleCode);
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name,
                                   role_id, reporting_manager_id)
                VALUES (?, ?, ?, 'not-a-real-hash', ?, ?, NULL)
                """, username, username, username + "@edunext.test", username, roleId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private int alertsFor(long ticketId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM unassigned_ticket_alerts WHERE ticket_id = ?",
                Integer.class, ticketId);
        return n == null ? 0 : n;
    }

    private java.sql.Timestamp alertedAt(long ticketId) {
        return jdbc.queryForObject(
                "SELECT alerted_at FROM unassigned_ticket_alerts WHERE ticket_id = ?",
                java.sql.Timestamp.class, ticketId);
    }

    private java.sql.Timestamp firstAlertedAt(long ticketId) {
        return jdbc.queryForObject(
                "SELECT first_alerted_at FROM unassigned_ticket_alerts WHERE ticket_id = ?",
                java.sql.Timestamp.class, ticketId);
    }

    private List<Long> notifiedUserIds(long ticketId) {
        return jdbc.queryForList("""
                SELECT user_id FROM notifications
                 WHERE ticket_id = ? AND event_code = 'NEW_UNASSIGNED_TICKET'
                """, Long.class, ticketId);
    }
}
