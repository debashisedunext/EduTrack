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
 * D-021 · the 80% pre-breach warning.
 *
 * <p>Instants are chosen in the calendar's zone (Asia/Kolkata, 09:30–18:30 per
 * B-023) and written in UTC. Assertions are scoped to the ticket under test,
 * never to the scanner's return value, since tickets accumulate across the
 * class.
 *
 * <p><b>{@code @Import(FixedClock.class)} is required</b>, not decorative: a
 * nested {@code @TestConfiguration} is auto-detected only when
 * {@code @SpringBootTest} declares no {@code classes}, and this one does.
 * Without it the fixed clock is silently ignored and every proportion here is
 * measured against whenever the suite happened to run.
 */
@Testcontainers
@SpringBootTest(classes = com.edunext.edutrack.worker.WorkerApplication.class)
@org.springframework.context.annotation.Import(PreBreachScannerIT.FixedClock.class)
class PreBreachScannerIT {

    /** Monday 2026-08-10, 09:45 IST — a quarter of an hour into the week. */
    private static final Instant NOW = Instant.parse("2026-08-10T04:15:00Z");

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
        registry.add("edutrack.sla.pre-breach-scan-interval", () -> "PT1H");
        registry.add("edutrack.sla.scan-interval", () -> "PT1H");
        registry.add("edutrack.sla.stage-scan-interval", () -> "PT1H");
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
    PreBreachScanner scanner;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private long assignee;
    private long projectId;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM email_log");

        int n = SEQ.incrementAndGet();
        assignee = insertUser("pb" + n);
        projectId = insertProject("PB" + n);
    }

    // --------------------------------------------------------- the threshold

    @Test
    @DisplayName("a ticket most of the way through its window warns its assignee")
    void pastEightyPercentWarns() {
        // Reported Thursday 09:30 IST, due Monday 11:00 IST: 9 + 9 working
        // hours either side of a weekend that counts for nothing, plus 1.5 on
        // Monday — 19.5 committed, of which 18.25 are spent.
        long ticket = insertTicket("PB-1",
                Instant.parse("2026-08-06T04:00:00Z"),   // Thu 09:30 IST
                Instant.parse("2026-08-10T05:30:00Z"));  // Mon 11:00 IST

        scanner.scanOnce();

        assertThat(warningsFor(ticket)).isEqualTo(1);
        assertThat(notifiedUserIds(ticket)).containsExactly(assignee);
    }

    @Test
    @DisplayName("a ticket early in its window is left alone")
    void wellInsideTheWindowIsQuiet() {
        long ticket = insertTicket("PB-2",
                Instant.parse("2026-08-10T04:00:00Z"),   // Mon 09:30 IST
                Instant.parse("2026-08-21T12:00:00Z"));  // a fortnight out

        scanner.scanOnce();

        assertThat(warningsFor(ticket)).isZero();
    }

    @Test
    @DisplayName("the warning goes to the assignee alone, not the escalation chain")
    void onlyTheAssigneeIsWarned() {
        long ticket = insertTicket("PB-3",
                Instant.parse("2026-08-06T04:00:00Z"),
                Instant.parse("2026-08-10T05:30:00Z"));

        scanner.scanOnce();

        // D-020 tells three people once it is actually late. This is a nudge to
        // the one person who can still finish it.
        assertThat(notifiedUserIds(ticket)).hasSize(1).containsExactly(assignee);
    }

    // ------------------------------------------------------------ boundaries

    @Test
    @DisplayName("a ticket already past its date is D-020's, not this scanner's")
    void anAlreadyBreachedTicketIsNotWarned() {
        long ticket = insertTicket("PB-4",
                Instant.parse("2026-08-03T04:00:00Z"),
                Instant.parse("2026-08-07T09:00:00Z"));  // last Friday

        scanner.scanOnce();

        // Warning somebody about a deadline that has gone is worse than silence.
        assertThat(warningsFor(ticket)).isZero();
    }

    @Test
    @DisplayName("an unassigned ticket has nobody to warn")
    void anUnassignedTicketIsSkipped() {
        long ticket = insertTicket("PB-5",
                Instant.parse("2026-08-06T04:00:00Z"),
                Instant.parse("2026-08-10T05:30:00Z"));
        jdbc.update("UPDATE tickets SET assigned_to = NULL WHERE id = ?", ticket);

        scanner.scanOnce();

        assertThat(warningsFor(ticket)).isZero();
    }

    @Test
    @DisplayName("a closed ticket cannot approach anything")
    void aClosedTicketIsInvisible() {
        long ticket = insertTicket("PB-6",
                Instant.parse("2026-08-06T04:00:00Z"),
                Instant.parse("2026-08-10T05:30:00Z"));
        jdbc.update("UPDATE tickets SET actual_close_date = ?, status = 'CLOSED' WHERE id = ?",
                java.sql.Timestamp.from(NOW.minusSeconds(3600)), ticket);

        scanner.scanOnce();

        assertThat(warningsFor(ticket)).isZero();
    }

    // ----------------------------------------------------------- once, and again

    @Test
    @DisplayName("an assignee is warned once, not every fifteen minutes")
    void warningHappensOncePerCycle() {
        long ticket = insertTicket("PB-7",
                Instant.parse("2026-08-06T04:00:00Z"),
                Instant.parse("2026-08-10T05:30:00Z"));
        scanner.scanOnce();
        int after = countNotifications();

        scanner.scanOnce();

        assertThat(warningsFor(ticket)).isEqualTo(1);
        assertThat(countNotifications()).isEqualTo(after);
    }

    @Test
    @DisplayName("a reopened ticket gets a fresh warning for its new cycle")
    void aReopenReArmsTheWarning() {
        long ticket = insertTicket("PB-8",
                Instant.parse("2026-08-06T04:00:00Z"),
                Instant.parse("2026-08-10T05:30:00Z"));
        scanner.scanOnce();

        // Reopened: a new cycle with its own window. Somebody told about cycle 1
        // must still hear about cycle 2, or the warning stops arriving for
        // precisely the tickets that have already gone wrong once.
        jdbc.update("""
                UPDATE tickets SET current_cycle_no = 2, is_reopened = 1, reopen_count = 1
                 WHERE id = ?
                """, ticket);

        scanner.scanOnce();

        assertThat(warningsFor(ticket))
                .as("one row per cycle, not one per ticket")
                .isEqualTo(2);
    }

    // ---------------------------------------------------------------- D-027

    @Test
    @DisplayName("the proportion is of working hours, not of the calendar")
    void aWeekendDoesNotAdvanceTheWindow() {
        // Reported Friday 18:00 IST, due Monday 18:00 IST, and it is now
        // Monday 09:45. The window is three calendar days of which two are a
        // weekend, so:
        //
        //   wall clock  63.75h of 72h  = 88.5%  -> a naive implementation warns
        //   working      0.75h of  9h  =  8.3%  -> barely started
        //
        // Chosen so the two answers fall on opposite sides of the threshold.
        // An earlier draft had them both under it, which made the test pass
        // against wall-clock maths and prove nothing.
        long ticket = insertTicket("PB-9",
                Instant.parse("2026-08-07T12:30:00Z"),   // Fri 18:00 IST
                Instant.parse("2026-08-10T12:30:00Z"));  // Mon 18:00 IST

        scanner.scanOnce();

        assertThat(warningsFor(ticket))
                .as("the weekend is not part of a working-hours window")
                .isZero();
    }

    // ------------------------------------------------------------- helpers

    private long insertTicket(String code, Instant reported, Instant due) {
        jdbc.update("""
                INSERT INTO tickets (ticket_code, project_id, title, task_type_id, level,
                                     original_level, status, current_stage, reported_by,
                                     assigned_to, planned_close_date, date_reported)
                SELECT ?, ?, ?, MIN(tt.id), 'HIGH', 'HIGH', 'OPEN', 'DEV', ?, ?, ?, ?
                  FROM task_types tt
                """, code + "-" + SEQ.incrementAndGet(), projectId, "Pre-breach fixture",
                assignee, assignee,
                java.sql.Timestamp.from(due), java.sql.Timestamp.from(reported));
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertProject(String code) {
        jdbc.update("INSERT INTO projects (project_code, name) VALUES (?, ?)",
                code, "Project " + code);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertUser(String username) {
        Long roleId = jdbc.queryForObject("SELECT id FROM roles ORDER BY id LIMIT 1", Long.class);
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id)
                VALUES (?, ?, ?, 'not-a-real-hash', ?, ?)
                """, username, username, username + "@edunext.test", username, roleId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private int warningsFor(long ticketId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sla_prebreach_alerts WHERE ticket_id = ?",
                Integer.class, ticketId);
        return n == null ? 0 : n;
    }

    private List<Long> notifiedUserIds(long ticketId) {
        return jdbc.queryForList("""
                SELECT user_id FROM notifications
                 WHERE ticket_id = ? AND event_code = 'SLA_80_PERCENT_ELAPSED'
                """, Long.class, ticketId);
    }

    private int countNotifications() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM notifications", Integer.class);
        return n == null ? 0 : n;
    }
}
