package com.edunext.edutrack.worker.digest;

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
 * D-038 · the daily digest and the weekly manager summary.
 *
 * <p>The clock is fixed to <b>Tuesday 2026-08-11, 08:30 IST</b> — a working day
 * in the middle of a week, so "today" and "overdue" are unambiguous and no
 * assertion here depends on when the suite happens to run.
 */
@Testcontainers
@SpringBootTest(classes = com.edunext.edutrack.worker.WorkerApplication.class)
@org.springframework.context.annotation.Import(DigestSchedulerIT.FixedClock.class)
class DigestSchedulerIT {

    /** Tuesday 2026-08-11, 08:30 IST. */
    private static final Instant NOW = Instant.parse("2026-08-11T03:00:00Z");

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
        // The sla scanners and A-051's stats worker both fire at context
        // startup and write `tickets`; this class's fixture writes the same
        // rows. See SlaScanner for the deadlock that produced.
        registry.add("edutrack.sla.initial-delay", () -> "PT24H");
        registry.add("edutrack.stats.enabled", () -> "false");
        registry.add("edutrack.outbox.enabled", () -> "false");
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
    @Autowired DigestScheduler scheduler;

    private static final AtomicInteger SEQ = new AtomicInteger();
    private long assignee;
    private long projectId;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM email_log");
        jdbc.update("DELETE FROM holidays");
        int n = SEQ.incrementAndGet();
        assignee = insertUser("dg" + n);
        projectId = insertProject("DG" + n);
    }

    @Test
    @DisplayName("counts today's due and yesterday's overdue, and names both in the subject")
    void theDigestSaysWhatIsDueAndWhatIsLate() {
        insertTicket("D-1", Instant.parse("2026-08-11T09:00:00Z"));   // due today
        insertTicket("D-2", Instant.parse("2026-08-11T12:00:00Z"));   // due today
        insertTicket("D-3", Instant.parse("2026-08-07T09:00:00Z"));   // overdue

        scheduler.sendDaily();

        assertThat(subjects()).singleElement().asString()
                .isEqualTo("Your open tickets — 3 open, 2 due today, 1 overdue");
    }

    @Test
    @DisplayName("somebody with nothing open is not mailed at all")
    void nobodyIsToldTheyHaveNothing() {
        // A digest saying "0 open" is how people learn to filter digests, and
        // then the one that mattered is filtered too.
        scheduler.sendDaily();
        assertThat(subjects()).isEmpty();
    }

    @Test
    @DisplayName("a closed ticket is nobody's open work")
    void closedTicketsAreNotCounted() {
        long open = insertTicket("D-4", Instant.parse("2026-08-11T09:00:00Z"));
        long closed = insertTicket("D-5", Instant.parse("2026-08-11T09:00:00Z"));
        jdbc.update("UPDATE tickets SET status = 'CLOSED' WHERE id = ?", closed);

        scheduler.sendDaily();
        assertThat(subjects()).singleElement().asString().contains("1 open");
        assertThat(open).isPositive();
    }

    @Test
    @DisplayName("no digest on an org holiday — the calendar decides, not the clock")
    void aHolidayIsSilent() {
        insertTicket("D-6", Instant.parse("2026-08-11T09:00:00Z"));
        jdbc.update("INSERT INTO holidays (holiday_date, name, is_active) VALUES (?, ?, 1)",
                java.sql.Date.valueOf("2026-08-11"), "Fixture holiday");

        scheduler.sendDaily();
        assertThat(subjects()).isEmpty();
    }

    @Test
    @DisplayName("the weekly summary reaches a PM through their project membership")
    void theWeeklyFollowsMembershipNotRole() {
        // Scoped by membership rather than role alone: a PM with no project has
        // no team to summarise and would otherwise be mailed about everybody.
        insertTicket("D-7", Instant.parse("2026-08-07T09:00:00Z"));   // overdue
        jdbc.update("INSERT INTO project_members (project_id, user_id, role_in_project) VALUES (?, ?, 'PM')",
                projectId, assignee);

        scheduler.sendWeekly();
        assertThat(subjects()).singleElement().asString()
                .startsWith("Team summary — week of 11 Aug")
                .contains("1 overdue");
    }

    @Test
    @DisplayName("a PM on no project gets no summary")
    void noMembershipNoSummary() {
        insertTicket("D-8", Instant.parse("2026-08-07T09:00:00Z"));

        scheduler.sendWeekly();
        assertThat(subjects()).isEmpty();
    }

    // ------------------------------------------------------------- fixtures

    /**
     * Scoped to the user this test created, never the whole table.
     *
     * <p>Tickets accumulate across the class — nothing deletes them, because
     * `ticket_history` is append-only and hash-chained, so a ticket cannot
     * simply be removed between tests. Every earlier test's assignee therefore
     * still has open work and still earns a digest, which is correct behaviour
     * and which a global count reads as a failure. The same reason
     * PreBreachScannerIT scopes its assertions to the ticket under test.
     */
    private List<String> subjects() {
        return jdbc.queryForList(
                "SELECT subject FROM email_log WHERE to_user_id = ? ORDER BY id",
                String.class, assignee);
    }

    private long insertTicket(String code, Instant due) {
        jdbc.update("""
                INSERT INTO tickets (ticket_code, project_id, title, task_type_id, level,
                                     original_level, status, current_stage, reported_by,
                                     assigned_to, planned_close_date, date_reported)
                SELECT ?, ?, ?, MIN(tt.id), 'HIGH', 'HIGH', 'OPEN', 'DEV', ?, ?, ?, ?
                  FROM task_types tt
                """, code + "-" + SEQ.incrementAndGet(), projectId, "Digest fixture",
                assignee, assignee,
                java.sql.Timestamp.from(due), java.sql.Timestamp.from(NOW.minusSeconds(86400)));
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertProject(String code) {
        jdbc.update("INSERT INTO projects (project_code, name) VALUES (?, ?)", code, "Project " + code);
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
}
