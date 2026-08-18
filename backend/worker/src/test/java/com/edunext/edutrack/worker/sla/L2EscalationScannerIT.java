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
 * D-024 · the escalation matrix and the second level.
 *
 * <p>Calendar is Asia/Kolkata 09:30–18:30 (B-023), so 48 working hours is a
 * bit over five working days. Instants are chosen in IST and written in UTC.
 *
 * <p><b>{@code @Import(FixedClock.class)} is required</b> — a nested
 * {@code @TestConfiguration} is auto-detected only when {@code @SpringBootTest}
 * declares no {@code classes}.
 */
@Testcontainers
@SpringBootTest(classes = com.edunext.edutrack.worker.WorkerApplication.class)
@org.springframework.context.annotation.Import(L2EscalationScannerIT.FixedClock.class)
class L2EscalationScannerIT {

    /** Monday 2026-08-17, 14:00 IST. */
    private static final Instant NOW = Instant.parse("2026-08-17T08:30:00Z");

    /** Monday 2026-08-03, 14:00 IST — ten working days earlier. */
    private static final Instant LONG_PAST_DUE = Instant.parse("2026-08-03T08:30:00Z");

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
        // The seven sla scanners are `@Scheduled(fixedDelay…)`, which fires its
        // first run the instant the context is up — seven threads scanning
        // `tickets` while this class's fixture is still writing it. That is a
        // deadlock reported against the test's own UPDATE, and it cost a re-run
        // on two integration batches. `SlaScanner` carries the full account.
        // Pushed past any suite's lifetime; every test here calls scanOnce().
        registry.add("edutrack.sla.initial-delay", () -> "PT24H");
        // A-051's stats worker is the other `fixedDelay` that fires at context
        // startup, and DailyStatsRepository reads and JOINs `tickets` — so it
        // races this class's fixture exactly as the sla scanners did. A-056 hit
        // the same thing from the other side ("22 of 25 cases failing in reset()")
        // and added this switch, applying it only to StatsRefreshIT. Using their
        // switch rather than editing worker/stats, which is Stream A's.
        registry.add("edutrack.stats.enabled", () -> "false");
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("edutrack.sla.l2-scan-interval", () -> "PT6H");
        registry.add("edutrack.sla.scan-interval", () -> "PT6H");
        registry.add("edutrack.sla.stage-scan-interval", () -> "PT6H");
        registry.add("edutrack.sla.pre-breach-scan-interval", () -> "PT6H");
        registry.add("edutrack.sla.stale-scan-interval", () -> "PT6H");
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
    L2EscalationScanner scanner;

    @Autowired
    EscalationPolicies policies;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private long assignee;
    private long reportingManager;
    private long headOfDelivery;
    private long projectId;
    private int taskTypeId;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM email_log");
        jdbc.update("DELETE FROM sla_policies");

        int n = SEQ.incrementAndGet();
        headOfDelivery = insertUser("hod" + n, null);
        reportingManager = insertUser("rm" + n, headOfDelivery);
        assignee = insertUser("dev" + n, reportingManager);
        projectId = insertProject("L2" + n);
        taskTypeId = jdbc.queryForObject("SELECT MIN(id) FROM task_types", Integer.class);
    }

    // ------------------------------------------------------------- the rule

    @Test
    @DisplayName("48 working hours past the date, L2 reaches the RM's manager")
    void theSecondLevelGoesOneStepUp() {
        enableL2();
        long ticket = insertBreachedTicket(LONG_PAST_DUE);

        scanner.scanOnce();

        // Not the reporting manager — they were told at breach. This is the
        // level above, which is what "L2" means in A-007's schema.
        assertThat(notifiedUserIds(ticket)).containsExactly(headOfDelivery);
        assertThat(escalatedTo(ticket)).isEqualTo(headOfDelivery);
    }

    @Test
    @DisplayName("a ticket only just overdue is not escalated twice")
    void tooRecentToEscalate() {
        enableL2();
        // Due Friday afternoon; it is Monday. Past its date, but nowhere near
        // 48 working hours past it.
        long ticket = insertBreachedTicket(Instant.parse("2026-08-14T08:30:00Z"));

        scanner.scanOnce();

        assertThat(escalationsFor(ticket)).isZero();
    }

    @Test
    @DisplayName("a ticket that never breached cannot reach the second level")
    void l2FollowsL1() {
        enableL2();
        long ticket = insertBreachedTicket(LONG_PAST_DUE);
        // is_delayed is what records that L1 fired. Without it there was no
        // first level for this to be the second of.
        jdbc.update("UPDATE tickets SET is_delayed = 0 WHERE id = ?", ticket);

        scanner.scanOnce();

        assertThat(escalationsFor(ticket)).isZero();
    }

    @Test
    void aClosedTicketIsNotEscalated() {
        enableL2();
        long ticket = insertBreachedTicket(LONG_PAST_DUE);
        jdbc.update("UPDATE tickets SET actual_close_date = ?, status = 'CLOSED' WHERE id = ?",
                java.sql.Timestamp.from(NOW.minusSeconds(3600)), ticket);

        scanner.scanOnce();

        assertThat(escalationsFor(ticket)).isZero();
    }

    @Test
    void escalationHappensOnce() {
        enableL2();
        long ticket = insertBreachedTicket(LONG_PAST_DUE);
        scanner.scanOnce();

        scanner.scanOnce();

        assertThat(escalationsFor(ticket)).isEqualTo(1);
        assertThat(notifiedUserIds(ticket)).hasSize(1);
    }

    // ---------------------------------------------------------- the matrix

    @Test
    @DisplayName("L2 is off unless the project asks for it")
    void withoutAPolicyThereIsNoSecondLevel() {
        // No sla_policies row at all: A-007's column default is
        // escalate_to_l2 = 0, and that default is what applies.
        long ticket = insertBreachedTicket(LONG_PAST_DUE);

        scanner.scanOnce();

        assertThat(escalationsFor(ticket)).isZero();
    }

    @Test
    @DisplayName("a project can switch the second level off again")
    void aPolicyCanDisableIt() {
        insertPolicy(projectId, taskTypeId, "HIGH", true, false);
        long ticket = insertBreachedTicket(LONG_PAST_DUE);

        scanner.scanOnce();

        assertThat(escalationsFor(ticket)).isZero();
    }

    @Test
    @DisplayName("the most specific policy wins: project+type beats project, beats org-wide")
    void resolutionOrderIsMostSpecificFirst() {
        insertPolicy(null, null, "HIGH", true, true);            // org-wide: on
        insertPolicy(projectId, null, "HIGH", true, true);       // project: on
        insertPolicy(projectId, taskTypeId, "HIGH", true, false); // this type: off

        assertThat(policies.forTicket(projectId, taskTypeId, "HIGH").l2())
                .as("the project+type row is the most specific and says no")
                .isFalse();
    }

    @Test
    @DisplayName("an org-wide policy applies to a project with none of its own")
    void theOrgWideRowIsTheFallback() {
        insertPolicy(null, null, "HIGH", true, true);

        assertThat(policies.forTicket(projectId, taskTypeId, "HIGH").l2()).isTrue();
    }

    @Test
    @DisplayName("a deactivated policy falls through to the next most specific")
    void inactivePoliciesAreIgnored() {
        insertPolicy(null, null, "HIGH", true, true);
        long specific = insertPolicy(projectId, taskTypeId, "HIGH", true, false);
        jdbc.update("UPDATE sla_policies SET is_active = 0 WHERE id = ?", specific);

        // Switching a policy off is not the same as it saying nothing — the
        // next active row is the answer.
        assertThat(policies.forTicket(projectId, taskTypeId, "HIGH").l2()).isTrue();
    }

    @Test
    @DisplayName("the matrix is per level, so Critical and Low can differ")
    void policyIsScopedToLevel() {
        insertPolicy(projectId, null, "CRITICAL", true, true);

        assertThat(policies.forTicket(projectId, taskTypeId, "CRITICAL").l2()).isTrue();
        assertThat(policies.forTicket(projectId, taskTypeId, "LOW").l2())
                .as("no LOW row, so the default applies")
                .isFalse();
    }

    // ---------------------------------------------------- the chain runs out

    @Test
    @DisplayName("a ticket whose manager has no manager is claimed, not retried forever")
    void theTopOfTheTreeIsRecordedAndDropped() {
        enableL2();
        long orphanManager = insertUser("top" + SEQ.incrementAndGet(), null);
        long lonely = insertUser("solo" + SEQ.incrementAndGet(), orphanManager);
        long ticket = insertBreachedTicketFor(lonely, LONG_PAST_DUE);

        scanner.scanOnce();

        // Nobody was told — there is nobody above. But it is recorded, or every
        // pass forever re-evaluates a ticket that can never escalate.
        assertThat(notifiedUserIds(ticket)).isEmpty();
        assertThat(escalationsFor(ticket)).isEqualTo(1);
        assertThat(escalatedTo(ticket)).isNull();
    }

    // ------------------------------------------------------------- helpers

    private void enableL2() {
        insertPolicy(projectId, null, "HIGH", true, true);
    }

    private long insertPolicy(Long project, Integer taskType, String level,
                              boolean l1, boolean l2) {
        jdbc.update("""
                INSERT INTO sla_policies (project_id, task_type_id, level, resolution_hrs,
                                          escalate_to_l1, escalate_to_l2, is_active)
                VALUES (?, ?, ?, 8.00, ?, ?, 1)
                """, project, taskType, level, l1, l2);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertBreachedTicket(Instant due) {
        return insertBreachedTicketFor(assignee, due);
    }

    private long insertBreachedTicketFor(long owner, Instant due) {
        String code = "L2-" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO tickets (ticket_code, project_id, title, task_type_id, level,
                                     original_level, status, current_stage, reported_by,
                                     assigned_to, planned_close_date, date_reported,
                                     is_delayed, delayed_since)
                VALUES (?, ?, ?, ?, 'HIGH', 'HIGH', 'OPEN', 'DEV', ?, ?, ?, ?, 1, ?)
                """, code, projectId, "L2 fixture " + code, taskTypeId, owner, owner,
                java.sql.Timestamp.from(due),
                java.sql.Timestamp.from(due.minusSeconds(864000)),
                java.sql.Timestamp.from(due));
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertProject(String code) {
        jdbc.update("INSERT INTO projects (project_code, name) VALUES (?, ?)",
                code, "Project " + code);
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

    private int escalationsFor(long ticketId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM l2_escalations WHERE ticket_id = ?", Integer.class, ticketId);
        return n == null ? 0 : n;
    }

    private Long escalatedTo(long ticketId) {
        return jdbc.queryForObject(
                "SELECT escalated_to FROM l2_escalations WHERE ticket_id = ?", Long.class, ticketId);
    }

    private List<Long> notifiedUserIds(long ticketId) {
        return jdbc.queryForList("""
                SELECT user_id FROM notifications
                 WHERE ticket_id = ? AND event_code = 'SLA_BREACHED'
                """, Long.class, ticketId);
    }
}
