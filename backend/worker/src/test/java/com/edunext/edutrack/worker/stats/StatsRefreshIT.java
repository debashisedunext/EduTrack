package com.edunext.edutrack.worker.stats;

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

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-051 · the summary rows against real data.
 *
 * <p>The figures here are arithmetic over timestamps, and arithmetic over
 * timestamps is where off-by-one lives. Every assertion below is about a
 * <em>boundary</em>: a ticket closed on day 3 is open at the end of day 2 and
 * not at the end of day 3; effort belongs to its {@code work_date} and not to
 * the day it was entered. Asserting a total without pinning an edge would pass
 * against a query that is a day out throughout.
 *
 * <p><b>Idempotence is asserted directly</b>, because the whole
 * recompute-don't-accumulate decision rests on it: a second pass over the same
 * day must produce the same numbers, or an outage leaves the table permanently
 * wrong in a way nothing reports.
 */
@Testcontainers
@SpringBootTest(classes = com.edunext.edutrack.worker.WorkerApplication.class)
@Import(StatsRefreshIT.FixedClock.class)
class StatsRefreshIT {

    /** Wednesday 2026-08-12, mid-morning UTC. */
    private static final Instant NOW = Instant.parse("2026-08-12T09:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 12);

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_stats_it")
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
        registry.add("edutrack.outbox.enabled", () -> "false");
        // Every scheduled job is pushed out of the way; each test drives its
        // worker directly so the assertions are about the query, not timing.
        registry.add("edutrack.stats.refresh-interval", () -> "PT6H");
        registry.add("edutrack.chain.verify-cron", () -> "0 0 5 31 2 *");
        registry.add("edutrack.sla.scan-interval", () -> "PT6H");
        registry.add("edutrack.sla.stage-scan-interval", () -> "PT6H");
        registry.add("edutrack.sla.pre-breach-scan-interval", () -> "PT6H");
        registry.add("edutrack.sla.stale-scan-interval", () -> "PT6H");
        registry.add("edutrack.sla.l2-scan-interval", () -> "PT6H");
        registry.add("edutrack.sla.ping-pong-scan-interval", () -> "PT6H");
        registry.add("edutrack.sla.unassigned-scan-interval", () -> "PT6H");
    }

    @TestConfiguration
    public static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StatsRefreshWorker worker;

    private long projectId;
    private long userId;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM daily_ticket_stats");
        jdbc.update("DELETE FROM resource_daily_stats");

        // ticket_effort_logs is append-only and hash-chained: A-008's trigger
        // refuses DELETE outright, so a fixture cannot truncate it and must not
        // try. A ticket an effort row points at is therefore undeletable too —
        // the FK holds it — so those are left in place rather than fought with.
        //
        // Nothing rests on the truncation anyway. Every test gets its own
        // project and its own user, and every assertion is scoped to one or the
        // other, so a row surviving from an earlier test is invisible to this
        // one. What the delete below still earns is a predictable
        // earliestActivity(): the backfill tests assert over a date range, and
        // a stray old ticket would move where backfill starts.
        // Most things referencing tickets cascade; email_log does not, and the
        // worker's scanners fire once at context startup, so it can hold a row
        // pinning a ticket alive. Cleared first rather than worked around.
        jdbc.update("DELETE FROM email_log");
        jdbc.update("DELETE FROM tickets WHERE id NOT IN (SELECT ticket_id FROM ticket_effort_logs)");

        userId = user();

        jdbc.update("INSERT INTO projects (project_code, name) VALUES (?, 'Stats IT Project')",
                "ST" + SEQ.incrementAndGet());
        projectId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long user() {
        int n = SEQ.incrementAndGet();
        Long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code = 'DEVELOPER'", Long.class);
        jdbc.update("INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id) "
                        + "VALUES (?, ?, ?, 'x', 'Stats IT', ?)",
                "E-S-" + n, "stats.it." + n, "stats.it." + n + "@example.com", roleId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long ticket(String reportedAt, String closedAt, String level) {
        jdbc.update("INSERT INTO tickets (ticket_code, project_id, title, level, original_level, "
                        + "date_reported, actual_close_date, planned_close_date, assigned_to) "
                        + "VALUES (?, ?, 'stats probe', ?, ?, ?, ?, ?, ?)",
                "ST-26-" + SEQ.incrementAndGet(), projectId, level, level,
                reportedAt, closedAt, "2099-01-01 00:00:00", userId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private Integer stat(LocalDate day, String column) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM daily_ticket_stats WHERE stat_date = ? AND project_id = ?",
                Integer.class, day, projectId);
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("flow lands on the day it happened")
    void createdAndClosedLandOnTheirOwnDays() {
        ticket("2026-08-10 11:00:00", "2026-08-11 15:00:00", "HIGH");
        worker.refreshOnce();

        assertThat(stat(LocalDate.of(2026, 8, 10), "created")).isEqualTo(1);
        assertThat(stat(LocalDate.of(2026, 8, 10), "closed")).isZero();
        assertThat(stat(LocalDate.of(2026, 8, 11), "created")).isZero();
        assertThat(stat(LocalDate.of(2026, 8, 11), "closed")).isEqualTo(1);
    }

    /**
     * The boundary the whole stock idea rests on. A ticket closed <em>during</em>
     * day 11 was open when day 10 ended and not when day 11 did — an
     * inclusive/exclusive slip here is invisible in a total and wrong on every
     * row.
     */
    @Test
    @DisplayName("open-at-end-of-day counts the day it closed as closed")
    void openAtEndOfDayRespectsTheBoundary() {
        ticket("2026-08-10 11:00:00", "2026-08-11 15:00:00", "HIGH");
        worker.refreshOnce();

        assertThat(stat(LocalDate.of(2026, 8, 9), "open_total"))
                .as("not yet reported").isZero();
        assertThat(stat(LocalDate.of(2026, 8, 10), "open_total"))
                .as("reported on the 10th and still open when it ended").isEqualTo(1);
        assertThat(stat(LocalDate.of(2026, 8, 11), "open_total"))
                .as("closed during the 11th, so not open when it ended").isZero();
    }

    @Test
    @DisplayName("level breakdown sums to the open total")
    void levelBreakdownAddsUp() {
        ticket("2026-08-10 09:00:00", null, "CRITICAL");
        ticket("2026-08-10 09:00:00", null, "LOW");
        worker.refreshOnce();

        LocalDate d = LocalDate.of(2026, 8, 10);
        assertThat(stat(d, "open_total")).isEqualTo(2);
        assertThat(stat(d, "open_critical")).isEqualTo(1);
        assertThat(stat(d, "open_low")).isEqualTo(1);
        assertThat(stat(d, "open_critical") + stat(d, "open_high")
                + stat(d, "open_medium") + stat(d, "open_low"))
                .as("every open ticket falls in exactly one level bucket")
                .isEqualTo(stat(d, "open_total"));
    }

    @Test
    @DisplayName("aging is measured from the reporting date, at end of day")
    void agingBucketsMoveWithTheDay() {
        ticket("2026-08-05 09:00:00", null, "MEDIUM");   // 5 days old on the 10th
        worker.refreshOnce();

        assertThat(stat(LocalDate.of(2026, 8, 6), "aging_0_2"))
                .as("one day old").isEqualTo(1);
        assertThat(stat(LocalDate.of(2026, 8, 10), "aging_3_7"))
                .as("five days old").isEqualTo(1);
        assertThat(stat(LocalDate.of(2026, 8, 10), "aging_0_2")).isZero();
    }

    /**
     * §4A.4 attributes hours to when the work happened. A timesheet entered
     * today for Monday belongs to Monday — and changes Monday's row when it is
     * next recomputed, which is the reason each pass covers a window rather
     * than only today.
     */
    @Test
    @DisplayName("effort is attributed to work_date, not to when it was logged")
    void effortLandsOnTheDayTheWorkHappened() {
        long id = ticket("2026-08-10 09:00:00", null, "MEDIUM");
        jdbc.update("INSERT INTO ticket_effort_logs (ticket_id, cycle_no, stage_code, iteration_no, "
                        + "user_id, work_date, hours) VALUES (?, 1, 'DEV', 1, ?, ?, ?)",
                id, userId, "2026-08-10", new BigDecimal("3.50"));
        worker.refreshOnce();

        BigDecimal onTheTenth = jdbc.queryForObject(
                "SELECT effort_hours FROM resource_daily_stats WHERE stat_date = ? AND user_id = ?",
                BigDecimal.class, LocalDate.of(2026, 8, 10), userId);
        assertThat(onTheTenth).isEqualByComparingTo("3.50");

        Integer rowsToday = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_daily_stats WHERE stat_date = ? AND user_id = ? "
                        + "AND effort_hours > 0", Integer.class, TODAY, userId);
        assertThat(rowsToday).as("no hours logged for today").isZero();
    }

    /**
     * The property the whole design rests on. If a second pass changed the
     * numbers, an outage would leave the table permanently wrong and nothing
     * would say so.
     */
    @Test
    @DisplayName("a second pass over the same day changes nothing")
    void recomputeIsIdempotent() {
        ticket("2026-08-10 11:00:00", null, "HIGH");
        worker.refreshOnce();
        LocalDate d = LocalDate.of(2026, 8, 10);
        int firstOpen = stat(d, "open_total");
        int firstCreated = stat(d, "created");

        worker.refreshOnce();

        assertThat(stat(d, "open_total")).isEqualTo(firstOpen);
        assertThat(stat(d, "created")).isEqualTo(firstCreated);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM daily_ticket_stats WHERE stat_date = ? AND project_id = ?",
                Integer.class, d, projectId))
                .as("upsert, not insert — a second pass must not duplicate the row")
                .isEqualTo(1);
    }

    /**
     * A ticket older than the trailing window is history the trend charts need,
     * and it is only there if backfill runs.
     */
    @Test
    @DisplayName("history older than the window is backfilled")
    void backfillReachesOlderDays() {
        ticket("2026-07-20 09:00:00", null, "MEDIUM");   // three weeks back
        worker.refreshOnce();

        assertThat(stat(LocalDate.of(2026, 7, 20), "created"))
                .as("outside the seven-day window, so only backfill can have written it")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a project with no tickets still gets a row of zeroes")
    void quietProjectsAreStillReported() {
        worker.refreshOnce();

        assertThat(stat(TODAY, "created"))
                .as("an empty chart and a missing chart are different statements")
                .isZero();
    }

    /**
     * Backfill resumes by calendar day, and this is why. Tickets in April and
     * August with a quiet summer between: every day in May, June and July still
     * has open tickets, an aging profile and a delayed count, and the trend
     * widgets read all of them. A resume point derived from the dates tickets
     * were <em>reported</em> fills April, jumps to August and then reports
     * itself complete, leaving three months missing permanently.
     *
     * <p>Asserted as contiguity rather than by probing one day, because the
     * failure is a <em>range</em> of absent rows and a single probe that landed
     * on a filled day would call it green.
     */
    @Test
    @DisplayName("backfill leaves no gap across a quiet stretch")
    void backfillIsContiguousAcrossQuietMonths() {
        ticket("2026-04-01 09:00:00", null, "MEDIUM");
        ticket("2026-08-01 09:00:00", null, "MEDIUM");

        LocalDate from = LocalDate.of(2026, 4, 1);
        LocalDate windowStart = TODAY.minusDays(6);

        // Backfill is capped per pass, so catching up is meant to take several.
        // Passes beyond the last useful one cost only the trailing window.
        for (int pass = 0; pass < 8; pass++) {
            worker.refreshOnce();
        }
 
        Long filled = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT stat_date) FROM daily_ticket_stats "
                        + "WHERE project_id = ? AND stat_date BETWEEN ? AND ?",
                Long.class, projectId, from, windowStart);

        assertThat(filled)
                .as("every calendar day from the first ticket to the trailing window")
                .isEqualTo(ChronoUnit.DAYS.between(from, windowStart) + 1);
    }

    /**
     * {@code assigned_to} carries no history, so reassigning a ticket changes
     * who qualified on days that are already summarised. An upsert cannot
     * retract the row it wrote for the previous assignee, and the ticket would
     * then be counted against both people at once — not merely stale, doubled.
     */
    @Test
    @DisplayName("reassignment does not leave the old assignee holding the ticket")
    void reassignmentRetractsTheOldResourceRow() {
        long ticketId = ticket("2026-08-10 09:00:00", null, "HIGH");
        worker.refreshOnce();

        LocalDate d = LocalDate.of(2026, 8, 10);
        assertThat(assignedOpen(d, userId)).as("held by the original assignee").isEqualTo(1);

        long other = user();
        jdbc.update("UPDATE tickets SET assigned_to = ? WHERE id = ?", other, ticketId);
        worker.refreshOnce();

        assertThat(assignedOpen(d, userId))
                .as("the ticket moved, so the old assignee no longer holds it")
                .isZero();
        assertThat(assignedOpen(d, other))
                .as("and the new one does")
                .isEqualTo(1);
    }

    /** No row and holding nothing are the same statement, so both read as zero. */
    private int assignedOpen(LocalDate day, long uid) {
        Integer open = jdbc.queryForObject(
                "SELECT COALESCE((SELECT assigned_open FROM resource_daily_stats "
                        + "WHERE stat_date = ? AND user_id = ?), 0)",
                Integer.class, day, uid);
        return open == null ? 0 : open;
    }
}
