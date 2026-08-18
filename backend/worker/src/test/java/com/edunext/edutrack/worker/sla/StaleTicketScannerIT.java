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
 * D-022 · the stale-task nudge.
 *
 * <p>Calendar is Asia/Kolkata 09:30–18:30 (B-023), so a working day is nine
 * hours and the threshold is twenty-seven. Instants are picked in IST and
 * written in UTC.
 *
 * <p><b>{@code @Import(FixedClock.class)} is required</b> — a nested
 * {@code @TestConfiguration} is auto-detected only when {@code @SpringBootTest}
 * declares no {@code classes}, and this one does.
 */
@Testcontainers
@SpringBootTest(classes = com.edunext.edutrack.worker.WorkerApplication.class)
@org.springframework.context.annotation.Import(StaleTicketScannerIT.FixedClock.class)
class StaleTicketScannerIT {

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
        registry.add("edutrack.sla.stale-scan-interval", () -> "PT6H");
        registry.add("edutrack.sla.scan-interval", () -> "PT6H");
        registry.add("edutrack.sla.stage-scan-interval", () -> "PT6H");
        registry.add("edutrack.sla.pre-breach-scan-interval", () -> "PT6H");
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
    StaleTicketScanner scanner;

    @Autowired
    StaleTicketNudge nudge;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private long assignee;
    private long manager;
    private long projectId;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM email_log");

        int n = SEQ.incrementAndGet();
        manager = insertUser("srm" + n, null);
        assignee = insertUser("sdev" + n, manager);
        projectId = insertProject("ST" + n);
    }

    // ------------------------------------------------------------ staleness

    @Test
    @DisplayName("a ticket untouched for three working days is nudged")
    void aQuietTicketIsNudged() {
        // Reported Tuesday 09:30 IST: Tue, Wed, Thu, Fri are nine hours each,
        // so by Monday it has been quiet for far more than twenty-seven.
        long ticket = insertTicket(Instant.parse("2026-08-04T04:00:00Z"));

        scanner.scanOnce();

        assertThat(nudgesFor(ticket)).isEqualTo(1);
        assertThat(notifiedUserIds(ticket)).containsExactlyInAnyOrder(assignee, manager);
    }

    @Test
    @DisplayName("a ticket touched yesterday is left alone")
    void arecentlyTouchedTicketIsQuiet() {
        long ticket = insertTicket(Instant.parse("2026-08-07T04:00:00Z"));   // Fri 09:30

        scanner.scanOnce();

        assertThat(nudgesFor(ticket)).isZero();
    }

    @Test
    @DisplayName("a comment counts as activity and resets the clock")
    void aCommentIsActivity() {
        long ticket = insertTicket(Instant.parse("2026-08-03T04:00:00Z"));   // long ago
        insertComment(ticket, Instant.parse("2026-08-10T04:00:00Z"));        // this morning

        scanner.scanOnce();

        assertThat(nudgesFor(ticket)).isZero();
    }

    @Test
    @DisplayName("a stage hop counts as activity too")
    void aStageTransitionIsActivity() {
        long ticket = insertTicket(Instant.parse("2026-08-03T04:00:00Z"));
        insertTransition(ticket, Instant.parse("2026-08-10T04:00:00Z"));

        scanner.scanOnce();

        assertThat(nudgesFor(ticket)).isZero();
    }

    @Test
    @DisplayName("the SLA scanner's own write does not make a ticket look fresh")
    void anEscalationIsNotActivity() {
        long ticket = insertTicket(Instant.parse("2026-08-03T04:00:00Z"));

        // Exactly what D-020 does when it escalates. It bumps tickets.updated_at,
        // and a staleness check reading that column would conclude somebody had
        // just worked on it — so escalating a ticket would be the thing that
        // stopped anybody being nudged about it.
        jdbc.update("UPDATE tickets SET is_delayed = 1, delayed_since = ? WHERE id = ?",
                java.sql.Timestamp.from(NOW), ticket);

        scanner.scanOnce();

        assertThat(nudgesFor(ticket))
                .as("a machine write is not somebody working on the ticket")
                .isEqualTo(1);
    }

    // ------------------------------------------------------------ who hears

    @Test
    @DisplayName("assignee, cc the reporting manager")
    void theAssigneeAndTheirManagerAreTold() {
        long ticket = insertTicket(Instant.parse("2026-08-04T04:00:00Z"));

        scanner.scanOnce();

        assertThat(notifiedUserIds(ticket))
                .containsExactlyInAnyOrder(assignee, manager)
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("an unassigned ticket is D-026's problem, not this one's")
    void anUnassignedTicketIsSkipped() {
        long ticket = insertTicket(Instant.parse("2026-08-04T04:00:00Z"));
        jdbc.update("UPDATE tickets SET assigned_to = NULL WHERE id = ?", ticket);

        scanner.scanOnce();

        assertThat(nudgesFor(ticket)).isZero();
    }

    @Test
    void aClosedTicketIsNeverStale() {
        long ticket = insertTicket(Instant.parse("2026-08-04T04:00:00Z"));
        jdbc.update("UPDATE tickets SET actual_close_date = ?, status = 'CLOSED' WHERE id = ?",
                java.sql.Timestamp.from(NOW.minusSeconds(3600)), ticket);

        scanner.scanOnce();

        assertThat(nudgesFor(ticket)).isZero();
    }

    // --------------------------------------------------------- repeating

    @Test
    @DisplayName("a nudge is not repeated on the very next pass")
    void nudgingDoesNotRepeatImmediately() {
        long ticket = insertTicket(Instant.parse("2026-08-04T04:00:00Z"));
        scanner.scanOnce();

        scanner.scanOnce();

        // Scoped to this ticket, not to a global count: other tests leave open
        // tickets behind and the scanner sweeps all of them.
        assertThat(notifiedUserIds(ticket))
                .as("assignee and manager, once each")
                .hasSize(2);
    }

    @Test
    @DisplayName("a ticket still quiet three working days later is nudged again")
    void aNudgeRepeatsOnceTheWindowHasPassedAgain() {
        long ticket = insertTicket(Instant.parse("2026-08-04T04:00:00Z"));
        scanner.scanOnce();

        // Backdate the nudge past the window. Unlike D-021's one-shot warning,
        // a ticket that stays forgotten should be raised again — a reminder
        // sent once and never repeated is one that gets ignored once.
        jdbc.update("UPDATE stale_ticket_nudges SET nudged_at = ? WHERE ticket_id = ?",
                java.sql.Timestamp.from(Instant.parse("2026-08-04T05:00:00Z")), ticket);

        scanner.scanOnce();

        assertThat(notifiedUserIds(ticket))
                .as("two recipients, twice")
                .hasSize(4);
    }

    @Test
    @DisplayName("losing the insert race does not nudge the same ticket twice")
    void aLostInsertRaceDoesNotNudgeAgain() {
        long ticket = insertTicket(Instant.parse("2026-08-04T04:00:00Z"));
        scanner.scanOnce();
        assertThat(notifiedUserIds(ticket)).as("pass one nudges").hasSize(2);

        // What a second worker sees when it read the candidate list a moment
        // before the first one committed: its row says "never nudged" while the
        // table already says otherwise. Its INSERT IGNORE then loses, and the
        // fallback UPDATE decides whether anybody is told twice.
        //
        // Found because D-025 added a scanner to the same context, which shifted
        // when the scheduled startup sweep lands relative to this class's first
        // test — enough to make a latent race fire. It was always reachable in
        // production, where two workers is the normal deployment.
        StaleTicketRepository.OpenTicket asSeenByALaterWorker =
                new StaleTicketRepository.OpenTicket(
                        ticket, "ST-race", "race", projectId, assignee, manager,
                        null, java.sql.Timestamp.from(Instant.parse("2026-08-04T04:00:00Z")));

        boolean nudgedAgain = nudge.nudgeIfStale(asSeenByALaterWorker, NOW);

        assertThat(nudgedAgain).isFalse();
        assertThat(notifiedUserIds(ticket))
                .as("still two — the worker that inserted the row did the nudging")
                .hasSize(2);
    }

    // ------------------------------------------------------------- helpers

    private long insertTicket(Instant reported) {
        String code = "ST-" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO tickets (ticket_code, project_id, title, task_type_id, level,
                                     original_level, status, current_stage, reported_by,
                                     assigned_to, planned_close_date, date_reported)
                SELECT ?, ?, ?, MIN(tt.id), 'MEDIUM', 'MEDIUM', 'OPEN', 'DEV', ?, ?, ?, ?
                  FROM task_types tt
                """, code, projectId, "Stale fixture " + code, assignee, assignee,
                java.sql.Timestamp.from(NOW.plusSeconds(2592000)),
                java.sql.Timestamp.from(reported));
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void insertComment(long ticketId, Instant at) {
        jdbc.update("""
                INSERT INTO ticket_comments (ticket_id, cycle_no, author_id, body_html,
                                             body_text, created_at)
                VALUES (?, 1, ?, '<p>update</p>', 'update', ?)
                """, ticketId, assignee, java.sql.Timestamp.from(at));
    }

    private void insertTransition(long ticketId, Instant at) {
        jdbc.update("""
                INSERT INTO ticket_stage_transitions
                       (ticket_id, cycle_no, seq_no, from_stage, to_stage, to_user_id,
                        action_code, entered_at, is_current)
                VALUES (?, 1, 1, 'TRIAGE', 'DEV', ?, 'FORWARD', ?, 1)
                """, ticketId, assignee, java.sql.Timestamp.from(at));
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

    private int nudgesFor(long ticketId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stale_ticket_nudges WHERE ticket_id = ?",
                Integer.class, ticketId);
        return n == null ? 0 : n;
    }

    private List<Long> notifiedUserIds(long ticketId) {
        return jdbc.queryForList("""
                SELECT user_id FROM notifications
                 WHERE ticket_id = ? AND event_code = 'STALE_TICKET_NUDGE'
                """, Long.class, ticketId);
    }

    private int countNotifications() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM notifications", Integer.class);
        return n == null ? 0 : n;
    }
}
