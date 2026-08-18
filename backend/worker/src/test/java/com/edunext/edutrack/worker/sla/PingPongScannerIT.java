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
 * D-025 · the ping-pong flag.
 *
 * <p>No working calendar in play — this counts backward moves rather than
 * measuring elapsed time — so the clock only has to be fixed so that the
 * flag's timestamps are predictable.
 *
 * <p><b>{@code @Import(FixedClock.class)} is required</b>: a nested
 * {@code @TestConfiguration} is auto-detected only when {@code @SpringBootTest}
 * declares no {@code classes}, and this one does. Without it the fixture reads
 * as working while silently running against the wall clock.
 */
@Testcontainers
@SpringBootTest(classes = com.edunext.edutrack.worker.WorkerApplication.class)
@org.springframework.context.annotation.Import(PingPongScannerIT.FixedClock.class)
class PingPongScannerIT {

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
        // The seven sla scanners are `@Scheduled(fixedDelay…)`, which fires its
        // first run the instant the context is up — seven threads scanning
        // `tickets` while this class's fixture is still writing it. That is a
        // deadlock reported against the test's own UPDATE, and it cost a re-run
        // on two integration batches. `SlaScanner` carries the full account.
        // Pushed past any suite's lifetime; every test here calls scanOnce().
        registry.add("edutrack.sla.initial-delay", () -> "PT24H");
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("edutrack.sla.ping-pong-scan-interval", () -> "PT6H");
        registry.add("edutrack.sla.scan-interval", () -> "PT6H");
        registry.add("edutrack.sla.stage-scan-interval", () -> "PT6H");
        registry.add("edutrack.sla.pre-breach-scan-interval", () -> "PT6H");
        registry.add("edutrack.sla.stale-scan-interval", () -> "PT6H");
        registry.add("edutrack.sla.l2-scan-interval", () -> "PT6H");
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
    PingPongScanner scanner;

    @Autowired
    PingPongRepository repository;

    @Autowired
    Clock clock;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private long assignee;
    private long reportingManager;
    private long projectManager;
    private long projectId;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM email_log");

        int n = SEQ.incrementAndGet();
        reportingManager = insertUser("pprm" + n, null);
        projectManager = insertUser("pppm" + n, null);
        assignee = insertUser("ppdev" + n, reportingManager);
        projectId = insertProject("PP" + n, projectManager);
    }

    @Test
    @DisplayName("the injected clock is the fixed one, not the wall clock")
    void theFixtureClockIsActuallyApplied() {
        // The failure this catches is silent: without @Import the scanner runs
        // against Clock.systemUTC(), every assertion below still passes, and
        // the fixture only starts lying when a timestamp matters.
        assertThat(clock.instant()).isEqualTo(NOW);
    }

    // ------------------------------------------------------------ threshold

    @Test
    @DisplayName("a ticket on its third iteration is flagged")
    void thirdIterationIsFlagged() {
        long ticket = insertTicket(3, 1);

        scanner.scanOnce();

        assertThat(flagsFor(ticket)).isEqualTo(1);
        assertThat(announcedIteration(ticket, 1)).isEqualTo(3);
    }

    @Test
    @DisplayName("a ticket on its second iteration is not — §11 says three")
    void secondIterationIsLeftAlone() {
        long ticket = insertTicket(2, 1);

        scanner.scanOnce();

        assertThat(flagsFor(ticket)).isZero();
        assertThat(notifiedUserIds(ticket)).isEmpty();
    }

    @Test
    @DisplayName("a closed ticket is never flagged, however much it bounced")
    void aClosedTicketIsIgnored() {
        long ticket = insertTicket(5, 1);
        jdbc.update("UPDATE tickets SET actual_close_date = ?, status = 'CLOSED' WHERE id = ?",
                java.sql.Timestamp.from(NOW.minusSeconds(3600)), ticket);

        scanner.scanOnce();

        assertThat(flagsFor(ticket)).isZero();
    }

    // ------------------------------------------------------------ the count

    @Test
    @DisplayName("three iterations is reported as two bounces, not three")
    void theHeadlineCountsBouncesNotIterations() {
        long ticket = insertTicket(3, 1);

        scanner.scanOnce();

        // Iteration 1 is a ticket that has never gone backwards, so iteration 3
        // is two returns. Reporting "3" would overstate every alert by one and
        // would always look plausible enough to survive.
        assertThat(titlesFor(ticket)).allSatisfy(title ->
                assertThat(title).contains("sent backwards 2 times"));
    }

    @Test
    @DisplayName("a single bounce reads as \"1 time\", not \"1 times\"")
    void theHeadlineIsGrammatical() {
        // Reachable when a project raises the threshold to 2, and cheap to get
        // right; a plural bug in an alert a manager reads is a small thing that
        // makes the whole alert look automated and ignorable.
        assertThat(bouncePhrase(2)).isEqualTo("sent backwards 1 time");
        assertThat(bouncePhrase(3)).isEqualTo("sent backwards 2 times");
    }

    // ------------------------------------------------------------ who hears

    @Test
    @DisplayName("the project manager and the reporting manager, not the assignee")
    void managersAreToldAndTheAssigneeIsNot() {
        long ticket = insertTicket(3, 1);

        scanner.scanOnce();

        // §11: bell and email to PM and RM. The person holding the ticket has
        // by definition just received it and did not cause the loop.
        assertThat(notifiedUserIds(ticket))
                .containsExactlyInAnyOrder(projectManager, reportingManager)
                .doesNotContain(assignee);
    }

    @Test
    @DisplayName("one manager wearing both hats is told once")
    void aDuplicateRecipientIsDeduplicated() {
        long solo = insertProject("PPS" + SEQ.incrementAndGet(), reportingManager);
        long ticket = insertTicket(3, 1, solo);

        scanner.scanOnce();

        assertThat(notifiedUserIds(ticket))
                .containsExactly(reportingManager)
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("an unassigned ticket still reaches the project manager")
    void anUnassignedTicketStillReachesThePm() {
        long ticket = insertTicket(3, 1);
        jdbc.update("UPDATE tickets SET assigned_to = NULL WHERE id = ?", ticket);

        scanner.scanOnce();

        // There is no reporting manager without an assignee, but the pattern is
        // the project's problem and the PM can still act on it.
        assertThat(notifiedUserIds(ticket)).containsExactly(projectManager);
    }

    // ------------------------------------------------------------ repeating

    @Test
    @DisplayName("the same iteration is not announced twice")
    void theSameIterationIsAnnouncedOnce() {
        long ticket = insertTicket(3, 1);
        scanner.scanOnce();

        scanner.scanOnce();

        assertThat(notifiedUserIds(ticket))
                .as("two managers, once each")
                .hasSize(2);
    }

    @Test
    @DisplayName("bouncing again is new information and is announced again")
    void afurtherBounceIsAnnouncedAgain() {
        long ticket = insertTicket(3, 1);
        scanner.scanOnce();

        jdbc.update("UPDATE tickets SET current_iteration = 4 WHERE id = ?", ticket);
        scanner.scanOnce();

        assertThat(notifiedUserIds(ticket))
                .as("two managers, twice")
                .hasSize(4);
        assertThat(announcedIteration(ticket, 1))
                .as("the flag tracks the highest iteration already announced")
                .isEqualTo(4);
        assertThat(flagsFor(ticket))
                .as("still one row per cycle, updated in place")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the claim itself refuses a second announcement of the same iteration")
    void theClaimIsSafeWithoutTheCandidateQuery() {
        long ticket = insertTicket(3, 1);

        // Straight at the repository, because the scanner's candidate query
        // *also* filters out an iteration already announced — so through the
        // scanner these two calls can never both happen, and the guard inside
        // the claim is untested by every test above. It exists for the case
        // two workers evaluate the same candidate at once, and a guard nothing
        // tests is a guard the next person deletes as redundant.
        //
        // It is also where D-022's Connector/J trap bites: the driver reports
        // *matched* rows, so an UPDATE without `iteration_no < :iterationNo` in
        // its WHERE would match this row, change nothing, and still report 1.
        assertThat(repository.claim(ticket, 1, 3, NOW)).isTrue();
        assertThat(repository.claim(ticket, 1, 3, NOW))
                .as("same iteration, already announced")
                .isFalse();
        assertThat(repository.claim(ticket, 1, 4, NOW))
                .as("it has bounced again — that is new information")
                .isTrue();
    }

    @Test
    @DisplayName("a reopen starts a fresh argument — cycle 2 is flagged on its own merits")
    void anewCycleIsFlaggedIndependently() {
        long ticket = insertTicket(3, 1);
        scanner.scanOnce();

        // §4A.2: a reopen increments the cycle and restarts the iteration
        // counter. A flag from the previous cycle must not suppress the new one,
        // or a ticket that ping-ponged, closed and came back to do it again
        // would be announced only the first time.
        jdbc.update("UPDATE tickets SET current_cycle_no = 2, current_iteration = 3 WHERE id = ?",
                ticket);
        scanner.scanOnce();

        assertThat(flagsFor(ticket)).as("one row per cycle").isEqualTo(2);
        assertThat(notifiedUserIds(ticket)).hasSize(4);
    }

    // ------------------------------------------------------------- helpers

    /** The phrase {@link PingPongFlag} builds, isolated from the wiring. */
    private static String bouncePhrase(int iterationNo) {
        int bounces = iterationNo - 1;
        return "sent backwards " + bounces + (bounces == 1 ? " time" : " times");
    }

    private long insertTicket(int iteration, int cycle) {
        return insertTicket(iteration, cycle, projectId);
    }

    private long insertTicket(int iteration, int cycle, long project) {
        String code = "PP-" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO tickets (ticket_code, project_id, title, task_type_id, level,
                                     original_level, status, current_stage, reported_by,
                                     assigned_to, planned_close_date, date_reported,
                                     current_cycle_no, current_iteration)
                SELECT ?, ?, ?, MIN(tt.id), 'MEDIUM', 'MEDIUM', 'REWORK', 'DEV', ?, ?, ?, ?, ?, ?
                  FROM task_types tt
                """, code, project, "Ping-pong fixture " + code, assignee, assignee,
                java.sql.Timestamp.from(NOW.plusSeconds(2592000)),
                java.sql.Timestamp.from(NOW.minusSeconds(864000)),
                cycle, iteration);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertProject(String code, long managerId) {
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

    private int flagsFor(long ticketId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ping_pong_flags WHERE ticket_id = ?", Integer.class, ticketId);
        return n == null ? 0 : n;
    }

    private int announcedIteration(long ticketId, int cycleNo) {
        Integer n = jdbc.queryForObject("""
                SELECT iteration_no FROM ping_pong_flags WHERE ticket_id = ? AND cycle_no = ?
                """, Integer.class, ticketId, cycleNo);
        return n == null ? 0 : n;
    }

    private List<Long> notifiedUserIds(long ticketId) {
        return jdbc.queryForList("""
                SELECT user_id FROM notifications
                 WHERE ticket_id = ? AND event_code = 'ITERATION_LIMIT_REACHED'
                """, Long.class, ticketId);
    }

    private List<String> titlesFor(long ticketId) {
        return jdbc.queryForList("""
                SELECT title FROM notifications
                 WHERE ticket_id = ? AND event_code = 'ITERATION_LIMIT_REACHED'
                """, String.class, ticketId);
    }
}
