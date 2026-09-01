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
import java.util.List;
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
        registry.add("edutrack.outbox.enabled", () -> "false");
        // Every scheduled job is pushed out of the way; each test drives its
        // worker directly so the assertions are about the query, not timing.
        registry.add("edutrack.stats.enabled", () -> "false");
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
        jdbc.update("DELETE FROM client_daily_stats");
        jdbc.update("DELETE FROM stage_daily_stats");
        jdbc.update("DELETE FROM module_daily_stats");

        // Dashboard Rework PR 4 · ticket_cycles carries no delete-blocking
        // trigger — it sits beside the three protected tables without being
        // one of them (V20260831_1400's own header) — so a plain DELETE
        // clears whatever `cycle()` wrote last test, ahead of the tickets
        // cleanup below: leaving a row behind would make its ticket
        // undeletable by the same foreign key the append-only tables use on
        // purpose, and this table does not earn that protection.
        jdbc.update("DELETE FROM ticket_cycles");

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
        // A-058 · ticket_stage_transitions joins the same exclusion, and for
        // the identical reason rather than a similar one: A-008 puts a
        // BEFORE DELETE trigger on it too — "the ribbon can never be
        // rewritten" — so a ticket with hops is as undeletable as one with
        // effort, and its FK would fail this statement rather than the
        // trigger firing.
        //
        // Nothing rests on removing them. Every test seeds its own project and
        // every assertion is scoped to it, so a surviving ticket is invisible
        // here; and the handoff walk is per ticket, so an old ticket's hops
        // cannot join into this one's sequence.
        jdbc.update("""
                DELETE FROM tickets
                 WHERE id NOT IN (SELECT ticket_id FROM ticket_effort_logs)
                   AND id NOT IN (SELECT ticket_id FROM ticket_stage_transitions)
                """);

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

    // ── A-056 · the task-type breakdown §S-05's donut reads ──────────────────

    /**
     * Counts what was <em>open</em> at end of day, matching {@code open_total}
     * and the level columns beside it. A breakdown of "created by type" would
     * answer a different question from every other figure on the row, and the
     * two would be compared anyway.
     */
    @Test
    @DisplayName("type_counts holds open tickets per task type")
    void typeCountsBreakOpenTicketsDownByType() {
        Long dev = taskType("DEV_IT");
        Long qa = taskType("QA_IT");
        typedTicket("2026-08-10 09:00:00", dev);
        typedTicket("2026-08-10 09:00:00", dev);
        typedTicket("2026-08-10 09:00:00", qa);
        worker.refreshOnce();

        assertThat(typeCount(LocalDate.of(2026, 8, 10), dev)).isEqualTo(2);
        assertThat(typeCount(LocalDate.of(2026, 8, 10), qa)).isEqualTo(1);
    }

    /**
     * A type nobody raised draws no slice. Eleven zero entries per project per
     * day would be most of the column, and a donut cannot render a zero segment
     * anyway.
     */
    @Test
    @DisplayName("a type with nothing open is absent, not zero")
    void typesWithNothingOpenAreOmitted() {
        Long dev = taskType("DEV_IT2");
        Long unused = taskType("UNUSED_IT");
        typedTicket("2026-08-10 09:00:00", dev);
        worker.refreshOnce();

        assertThat(typeCount(LocalDate.of(2026, 8, 10), unused))
                .as("absent from the JSON entirely")
                .isNull();
    }

    /**
     * NULL rather than {@code '{}'}. An empty object claims no type had anything
     * open; NULL says the question does not arise for a project with no tickets.
     */
    @Test
    @DisplayName("a project with nothing open gets NULL, not an empty object")
    void nothingOpenIsNullNotEmpty() {
        worker.refreshOnce();

        String json = jdbc.queryForObject(
                "SELECT type_counts FROM daily_ticket_stats WHERE stat_date = ? AND project_id = ?",
                String.class, TODAY, projectId);
        assertThat(json).isNull();
    }

    private Long taskType(String code) {
        jdbc.update("INSERT INTO task_types (code, name, is_active) VALUES (?, ?, 1)",
                code + SEQ.incrementAndGet(), "Type " + code);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void typedTicket(String reportedAt, Long taskTypeId) {
        jdbc.update("INSERT INTO tickets (ticket_code, project_id, title, level, original_level, "
                        + "date_reported, planned_close_date, assigned_to, task_type_id) "
                        + "VALUES (?, ?, 'typed probe', 'MEDIUM', 'MEDIUM', ?, '2099-01-01 00:00:00', ?, ?)",
                "TT-26-" + SEQ.incrementAndGet(), projectId, reportedAt, userId, taskTypeId);
    }

    /** @return the open count for one type, or null when the type is absent from the JSON */
    private Integer typeCount(LocalDate day, Long taskTypeId) {
        return jdbc.queryForObject(
                "SELECT JSON_EXTRACT(type_counts, ?) FROM daily_ticket_stats "
                        + "WHERE stat_date = ? AND project_id = ?",
                Integer.class, "$.\"" + taskTypeId + "\"", day, projectId);
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
        return resourceStat(day, uid, "assigned_open");
    }

    private int resourceStat(LocalDate day, long uid, String column) {
        Integer value = jdbc.queryForObject(
                "SELECT COALESCE((SELECT " + column + " FROM resource_daily_stats "
                        + "WHERE stat_date = ? AND user_id = ?), 0)",
                Integer.class, day, uid);
        return value == null ? 0 : value;
    }

    /**
     * A-062 · a ticket with a real due date. {@link #ticket} pins
     * {@code planned_close_date} to 2099 so that nothing it creates is ever
     * delayed; the due columns need one that lands inside the window.
     */
    private void ticketDue(String reportedAt, String plannedCloseAt) {
        jdbc.update("INSERT INTO tickets (ticket_code, project_id, title, level, original_level, "
                        + "date_reported, actual_close_date, planned_close_date, assigned_to) "
                        + "VALUES (?, ?, 'due probe', 'MEDIUM', 'MEDIUM', ?, NULL, ?, ?)",
                "ST-26-" + SEQ.incrementAndGet(), projectId, reportedAt, plannedCloseAt, userId);
    }

    // ── A-062 · the resource-keyed due and aging columns ─────────────────────

    private static final LocalDate DUE_DAY = LocalDate.of(2026, 8, 10);

    /**
     * The window is seven days <em>including</em> the day itself, which is what
     * the two card labels claim and what makes {@code due_today} a subset of it.
     * The edges are what this pins: day 6 is the last day inside, day 7 is out.
     */
    @Test
    @DisplayName("due counts today, the next six days, and nothing beyond them")
    void dueWindowEdges() {
        ticketDue("2026-08-01 09:00:00", "2026-08-10 17:00:00");   // due on the day
        ticketDue("2026-08-01 09:00:00", "2026-08-16 17:00:00");   // day + 6, last one inside
        ticketDue("2026-08-01 09:00:00", "2026-08-17 09:00:00");   // day + 7, outside

        worker.refreshOnce();

        assertThat(resourceStat(DUE_DAY, userId, "assigned_due_today"))
                .as("one ticket due that day, whatever time of day it is due")
                .isEqualTo(1);
        assertThat(resourceStat(DUE_DAY, userId, "assigned_due_next_7"))
                .as("today plus six more days, so two of the three")
                .isEqualTo(2);
    }

    /**
     * Due is what is <em>coming</em>; delayed is what is already late. A ticket
     * that appeared in both would be counted twice by a developer reading four
     * figures that are meant to partition their work — and the tile people plan
     * their day around would be the one overstating it.
     */
    @Test
    @DisplayName("an overdue ticket is delayed and is not due")
    void overdueIsNeverAlsoDue() {
        ticketDue("2026-08-01 09:00:00", "2026-08-05 17:00:00");

        worker.refreshOnce();

        assertThat(resourceStat(DUE_DAY, userId, "assigned_delayed")).isEqualTo(1);
        assertThat(resourceStat(DUE_DAY, userId, "assigned_due_today")).isZero();
        assertThat(resourceStat(DUE_DAY, userId, "assigned_due_next_7")).isZero();
    }

    /** No commitment was made, so there is nothing to be due. */
    @Test
    @DisplayName("a ticket with no due date is in neither due column")
    void noDueDateIsNotDue() {
        jdbc.update("INSERT INTO tickets (ticket_code, project_id, title, level, original_level, "
                        + "date_reported, planned_close_date, assigned_to) "
                        + "VALUES (?, ?, 'no due date', 'MEDIUM', 'MEDIUM', ?, NULL, ?)",
                "ST-26-" + SEQ.incrementAndGet(), projectId, "2026-08-01 09:00:00", userId);

        worker.refreshOnce();

        assertThat(assignedOpen(DUE_DAY, userId)).as("it is still open work").isEqualTo(1);
        assertThat(resourceStat(DUE_DAY, userId, "assigned_due_next_7")).isZero();
    }

    /**
     * The four buckets partition the person's open set, with the project
     * table's own edges — 0–2 / 3–7 / 8–30 / 31+. Two charts that share their
     * labels and their drill-down links but not their boundaries produce two
     * figures that never reconcile and no way to see why.
     */
    @Test
    @DisplayName("resource aging uses the project table's bucket edges and sums to the open count")
    void resourceAgingBuckets() {
        ticket("2026-08-09 09:00:00", null, "MEDIUM");   // 1 day old on the 10th
        ticket("2026-08-05 09:00:00", null, "MEDIUM");   // 5 days
        ticket("2026-07-25 09:00:00", null, "MEDIUM");   // 16 days
        ticket("2026-06-01 09:00:00", null, "MEDIUM");   // 70 days

        worker.refreshOnce();

        assertThat(resourceStat(DUE_DAY, userId, "assigned_aging_0_2")).isEqualTo(1);
        assertThat(resourceStat(DUE_DAY, userId, "assigned_aging_3_7")).isEqualTo(1);
        assertThat(resourceStat(DUE_DAY, userId, "assigned_aging_8_30")).isEqualTo(1);
        assertThat(resourceStat(DUE_DAY, userId, "assigned_aging_31_plus")).isEqualTo(1);

        assertThat(resourceStat(DUE_DAY, userId, "assigned_aging_0_2")
                + resourceStat(DUE_DAY, userId, "assigned_aging_3_7")
                + resourceStat(DUE_DAY, userId, "assigned_aging_8_30")
                + resourceStat(DUE_DAY, userId, "assigned_aging_31_plus"))
                .as("every open ticket falls in exactly one bucket, so the four sum to assigned_open")
                .isEqualTo(assignedOpen(DUE_DAY, userId));
    }

    /**
     * The resource buckets are computed by the same {@code DATEDIFF} against the
     * same day as the project ones, so for a single assignee holding everything
     * the two tables have to agree bucket for bucket. They are two statements of
     * one definition and this is what stops them drifting.
     */
    @Test
    @DisplayName("the resource buckets agree with the project buckets")
    void resourceAndProjectAgingAgree() {
        ticket("2026-08-09 09:00:00", null, "MEDIUM");
        ticket("2026-08-05 09:00:00", null, "MEDIUM");
        ticket("2026-07-25 09:00:00", null, "MEDIUM");

        worker.refreshOnce();

        for (String bucket : new String[] {"aging_0_2", "aging_3_7", "aging_8_30", "aging_31_plus"}) {
            assertThat(resourceStat(DUE_DAY, userId, "assigned_" + bucket))
                    .as(bucket)
                    .isEqualTo(stat(DUE_DAY, bucket));
        }
    }

    // ── A-059 · client_daily_stats ───────────────────────────────────────────

    /**
     * The flow/stock split, on one day, with two different answers.
     *
     * <p>One ticket raised before the day and one raised during it: the day's
     * volume is one and its open count is two. A recompute that read either
     * column into the other would still produce a plausible number, which is
     * why both are asserted on the same row rather than in two tests.
     */
    @Test
    @DisplayName("volume counts what was raised, open counts what was still there")
    void clientVolumeSeparatesFlowFromStock() {
        long acme = client("Acme");
        clientTicket(acme, "2026-08-08 09:00:00", null);
        clientTicket(acme, "2026-08-10 09:00:00", null);
        worker.refreshOnce();

        LocalDate d = LocalDate.of(2026, 8, 10);
        assertThat(clientStat(d, acme, "created")).isEqualTo(1);
        assertThat(clientStat(d, acme, "open_total")).isEqualTo(2);
    }

    /**
     * 🔴 The NULL trap the COALESCE in the query exists for.
     *
     * <p>{@code actual_close_date} is NULL on an open ticket, {@code NULL < x}
     * is NULL rather than false, and {@code SUM} skips NULLs — so a client
     * holding nothing but open tickets sums {@code closed} to NULL and the
     * NOT NULL column rejects the whole row. The symptom is not a zero in the
     * wrong place: it is the client vanishing from the chart entirely, for
     * having closed nothing.
     */
    @Test
    @DisplayName("a client that has closed nothing still gets a row")
    void clientWithNoClosuresStillGetsARow() {
        long acme = client("Acme");
        clientTicket(acme, "2026-08-10 09:00:00", null);
        worker.refreshOnce();

        LocalDate d = LocalDate.of(2026, 8, 10);
        assertThat(clientStat(d, acme, "closed")).isZero();
        assertThat(clientStat(d, acme, "created")).isEqualTo(1);
    }

    /**
     * An internally-raised ticket belongs to no client and has no bar to sit
     * in. It must not be counted under another client, and it must not invent
     * one.
     */
    @Test
    @DisplayName("tickets with no client are summarised nowhere")
    void ticketsWithoutAClientAreNotSummarised() {
        long acme = client("Acme");
        clientTicket(acme, "2026-08-10 09:00:00", null);
        ticket("2026-08-10 09:00:00", null, "MEDIUM");   // no client_id
        worker.refreshOnce();

        assertThat(jdbc.queryForObject(
                "SELECT COALESCE(SUM(created), 0) FROM client_daily_stats "
                        + "WHERE stat_date = ? AND project_id = ?",
                Integer.class, LocalDate.of(2026, 8, 10), projectId))
                .as("the unattributed ticket is in daily_ticket_stats and nowhere here")
                .isEqualTo(1);
    }

    /**
     * 🔴 Why the refresh clears the day rather than upserting over it.
     *
     * <p>{@code tickets.client_id} is editable — a support desk correcting a
     * mis-filed ticket is routine — and an upsert cannot retract the row it
     * wrote for the old client. The old bar would keep its count while the new
     * one gained the same ticket, so one ticket would be drawn twice under two
     * names and the chart's total would exceed the tickets that exist.
     */
    @Test
    @DisplayName("re-attributing a ticket retracts the old client's row")
    void reattributionRetractsTheOldClientRow() {
        long acme = client("Acme");
        long globex = client("Globex");
        long id = clientTicket(acme, "2026-08-10 09:00:00", null);
        worker.refreshOnce();

        LocalDate d = LocalDate.of(2026, 8, 10);
        assertThat(clientStat(d, acme, "created")).isEqualTo(1);

        jdbc.update("UPDATE tickets SET client_id = ? WHERE id = ?", globex, id);
        worker.refreshOnce();

        assertThat(clientStat(d, globex, "created")).isEqualTo(1);
        // Gone, not zeroed. A row of zeroes would still put the old client on
        // the chart, and "raised nothing" is a different claim from "was never
        // this client's ticket".
        assertThat(clientRows(d, acme)).isZero();
    }

    /**
     * A (project, client) pair with nothing to report earns no row, or the
     * table would grow by clients times projects times days regardless of
     * activity.
     */
    @Test
    @DisplayName("a day with nothing to report earns no row")
    void quietDaysEarnNoRow() {
        long acme = client("Acme");
        clientTicket(acme, "2026-08-07 09:00:00", "2026-08-08 15:00:00");
        worker.refreshOnce();

        assertThat(clientRows(LocalDate.of(2026, 8, 11), acme))
                .as("nothing raised, nothing closed, nothing open").isZero();
        // Absence is about having nothing to say, not about being old: the day
        // it closed is still recorded.
        assertThat(clientStat(LocalDate.of(2026, 8, 8), acme, "closed")).isEqualTo(1);
    }

    /** Recompute, never accumulate — the guarantee the other two tables make. */
    @Test
    @DisplayName("recomputing a day twice does not double the volume")
    void clientRecomputeIsIdempotent() {
        long acme = client("Acme");
        clientTicket(acme, "2026-08-10 09:00:00", null);
        worker.refreshOnce();
        worker.refreshOnce();

        LocalDate d = LocalDate.of(2026, 8, 10);
        assertThat(clientStat(d, acme, "created")).isEqualTo(1);
        assertThat(clientRows(d, acme)).isEqualTo(1);
    }

    private long client(String name) {
        int n = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO clients (client_code, name) VALUES (?, ?)",
                "SC-" + n, name + " " + n);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long clientTicket(long clientId, String reportedAt, String closedAt) {
        long id = ticket(reportedAt, closedAt, "MEDIUM");
        jdbc.update("UPDATE tickets SET client_id = ? WHERE id = ?", clientId, id);
        return id;
    }

    private Integer clientStat(LocalDate day, long clientId, String column) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM client_daily_stats "
                        + "WHERE stat_date = ? AND project_id = ? AND client_id = ?",
                Integer.class, day, projectId, clientId);
    }

    private Integer clientRows(LocalDate day, long clientId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM client_daily_stats "
                        + "WHERE stat_date = ? AND project_id = ? AND client_id = ?",
                Integer.class, day, projectId, clientId);
    }

    // ── Dashboard Rework Dev 2, PR 14 · module_daily_stats ───────────────────

    /**
     * 🔴 The decision this widget turns on, and the obvious implementation
     * fails it silently.
     *
     * <p>A stacked bar makes an arithmetic claim: the segments add up to the
     * whole. Three independent SUMs over overlapping predicates would count an
     * overdue in-progress ticket twice — once as overdue, once as WIP — and
     * every module's bar would overstate its load in proportion to how late
     * that module is running. Each segment stays individually plausible, which
     * is why this is asserted rather than eyeballed.
     */
    @Test
    @DisplayName("an overdue WIP ticket is counted once, under overdue")
    void moduleSegmentsPartitionRatherThanOverlap() {
        long module = module("BILLING");
        // In progress AND past its planned close date — the ticket that gets
        // double-counted by the intuitive query.
        long id = moduleTicket(module, "2026-08-08 09:00:00");
        jdbc.update("UPDATE tickets SET status = 'IN_PROGRESS', planned_close_date = ? WHERE id = ?",
                "2026-08-09 00:00:00", id);
        worker.refreshOnce();

        LocalDate d = LocalDate.of(2026, 8, 10);
        assertThat(moduleStat(d, module, "open_overdue")).isEqualTo(1);
        assertThat(moduleStat(d, module, "open_wip")).isZero();
        assertThat(moduleStat(d, module, "open_not_started")).isZero();
    }

    /**
     * The same property stated as arithmetic: whatever the mix, the three
     * segments sum to the number of outstanding tickets in the module. This is
     * what the bar's *length* means, and it is the assertion that survives
     * somebody rewriting the CASE.
     */
    @Test
    @DisplayName("the three segments sum to the module's open total")
    void moduleSegmentsSumToTheOpenTotal() {
        long module = module("FEES");
        long overdue = moduleTicket(module, "2026-08-08 09:00:00");
        jdbc.update("UPDATE tickets SET status = 'IN_PROGRESS', planned_close_date = ? WHERE id = ?",
                "2026-08-09 00:00:00", overdue);
        long wip = moduleTicket(module, "2026-08-08 09:00:00");
        jdbc.update("UPDATE tickets SET status = 'IN_PROGRESS' WHERE id = ?", wip);
        moduleTicket(module, "2026-08-09 09:00:00");    // NEW → not started
        moduleTicket(module, "2026-08-09 10:00:00");    // NEW → not started
        worker.refreshOnce();

        LocalDate d = LocalDate.of(2026, 8, 10);
        assertThat(moduleStat(d, module, "open_overdue")
                + moduleStat(d, module, "open_wip")
                + moduleStat(d, module, "open_not_started"))
                .isEqualTo(4);
        assertThat(moduleStat(d, module, "open_wip")).isEqualTo(1);
        assertThat(moduleStat(d, module, "open_not_started")).isEqualTo(2);
    }

    /**
     * RESOLVED is category DONE with its record still open — finished work
     * whose ticket has not been closed. S-05 counts it on the Today tab's
     * Pending Review card. Counting it here would put finished work in a chart
     * titled "open tickets" and, because it is in none of the three segments,
     * would break the sum above.
     */
    @Test
    @DisplayName("resolved-but-not-closed is not open work")
    void resolvedIsNotCountedAsOpen() {
        long module = module("LIBRARY");
        long id = moduleTicket(module, "2026-08-09 09:00:00");
        jdbc.update("UPDATE tickets SET status = 'RESOLVED' WHERE id = ?", id);
        worker.refreshOnce();

        assertThat(moduleRows(LocalDate.of(2026, 8, 10), module)).isZero();
    }

    /**
     * §7.5's module fields postdate the tickets raised before them, so
     * module_id is nullable. Those tickets belong to no module's bar — they
     * must not be counted under another module, and must not invent one.
     */
    @Test
    @DisplayName("tickets with no module are summarised nowhere")
    void ticketsWithoutAModuleAreNotSummarised() {
        long module = module("EXAM");
        moduleTicket(module, "2026-08-09 09:00:00");
        ticket("2026-08-09 09:00:00", null, "MEDIUM");   // no module_id
        worker.refreshOnce();

        LocalDate d = LocalDate.of(2026, 8, 10);
        assertThat(moduleStat(d, module, "open_not_started")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COALESCE(SUM(open_not_started), 0) FROM module_daily_stats "
                        + "WHERE stat_date = ? AND project_id = ?",
                Integer.class, d, projectId)).isEqualTo(1);
    }

    /**
     * module_id is editable on the ticket, so a pair can stop earning its row.
     * An upsert cannot retract what it wrote, and the ticket would then stand
     * in two modules' bars at once — which is why the refresh deletes the day
     * before rewriting it.
     */
    @Test
    @DisplayName("re-pointing a ticket moves it rather than duplicating it")
    void repointingAModuleDoesNotDoubleCount() {
        long from = module("ATTEND");
        long to = module("PARENT");
        long id = moduleTicket(from, "2026-08-09 09:00:00");
        worker.refreshOnce();
        assertThat(moduleStat(LocalDate.of(2026, 8, 10), from, "open_not_started")).isEqualTo(1);

        jdbc.update("UPDATE tickets SET module_id = ? WHERE id = ?", to, id);
        worker.refreshOnce();

        LocalDate d = LocalDate.of(2026, 8, 10);
        assertThat(moduleRows(d, from)).isZero();
        assertThat(moduleStat(d, to, "open_not_started")).isEqualTo(1);
    }

    /** A second pass over unchanged rows must not change the answer. */
    @Test
    @DisplayName("recompute is idempotent")
    void moduleRecomputeIsIdempotent() {
        long module = module("INVENT");
        moduleTicket(module, "2026-08-09 09:00:00");
        worker.refreshOnce();
        Integer first = moduleStat(LocalDate.of(2026, 8, 10), module, "open_not_started");

        worker.refreshOnce();

        assertThat(moduleStat(LocalDate.of(2026, 8, 10), module, "open_not_started")).isEqualTo(first);
        assertThat(moduleRows(LocalDate.of(2026, 8, 10), module)).isEqualTo(1);
    }

    private long module(String code) {
        jdbc.update("INSERT INTO product_modules (code, name, seq) VALUES (?, ?, 0)",
                code + SEQ.incrementAndGet(), code);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long moduleTicket(long moduleId, String reportedAt) {
        long id = ticket(reportedAt, null, "MEDIUM");
        jdbc.update("UPDATE tickets SET module_id = ? WHERE id = ?", moduleId, id);
        return id;
    }

    private Integer moduleStat(LocalDate day, long moduleId, String column) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM module_daily_stats "
                        + "WHERE stat_date = ? AND project_id = ? AND module_id = ?",
                Integer.class, day, projectId, moduleId);
    }

    private Integer moduleRows(LocalDate day, long moduleId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM module_daily_stats "
                        + "WHERE stat_date = ? AND project_id = ? AND module_id = ?",
                Integer.class, day, projectId, moduleId);
    }

    // ── A-058 · widgets 16–19, the four derived from the ribbon ──────────────

    /**
     * 🔴 The decision this task turns on, and the obvious implementation fails
     * it.
     *
     * <p>{@code tickets.current_stage} is current state with no history. A pass
     * reading it would write <em>today's</em> distribution into every day it
     * recomputes — and because each pass recomputes a trailing week, last
     * Tuesday's funnel would silently become a copy of this morning's, five
     * minutes at a time.
     *
     * <p>The ticket below is in QA now and was in DEV on the 10th. A funnel for
     * the 10th must say DEV.
     */
    @Test
    @DisplayName("wip_by_stage is where the ticket WAS that day, not where it is now")
    void wipByStageIsHistorical() {
        long id = ticket("2026-08-10 08:00:00", null, "MEDIUM");
        jdbc.update("UPDATE tickets SET current_stage = 'QA' WHERE id = ?", id);
        // Left DEV on the 11th, in QA since — and unsealed, so it is there now.
        transition(id, 1, 1, "DEV", "2026-08-10 09:00:00", "2026-08-11 09:00:00", 480);
        transition(id, 1, 2, "QA", "2026-08-11 09:00:00", null, null);

        worker.refreshOnce();

        assertThat(wip(LocalDate.of(2026, 8, 10)))
                .as("reading tickets.current_stage would answer QA for a day it was in DEV")
                .isEqualTo("{\"DEV\": 1}");
        assertThat(wip(LocalDate.of(2026, 8, 11))).isEqualTo("{\"QA\": 1}");
    }

    @Test
    @DisplayName("a ticket closed by end of day sits in no stage")
    void closedTicketsLeaveTheFunnel() {
        long id = ticket("2026-08-10 08:00:00", "2026-08-11 10:00:00", "MEDIUM");
        transition(id, 1, 1, "QA", "2026-08-10 09:00:00", null, null);

        worker.refreshOnce();

        assertThat(wip(LocalDate.of(2026, 8, 10))).isEqualTo("{\"QA\": 1}");
        assertThat(wip(LocalDate.of(2026, 8, 11)))
                .as("closed work is not queued anywhere; NULL says the question does not arise")
                .isNull();
    }

    /**
     * The staleness {@code refreshTypeCounts} still carries and this must not
     * copy: an inner join leaves yesterday's document in place for a project
     * that no longer matches. A funnel is read as "where the work is now", so a
     * stale one points at a bottleneck that has already cleared.
     */
    @Test
    @DisplayName("a project that has emptied is reset to NULL, not left holding yesterday's funnel")
    void wipByStageIsResetRatherThanLeftStale() {
        jdbc.update("""
                INSERT INTO daily_ticket_stats (stat_date, project_id, wip_by_stage, computed_at)
                VALUES (?, ?, '{"DEV": 99}', ?)
                """, TODAY, projectId, java.sql.Timestamp.from(NOW));

        worker.refreshOnce();

        assertThat(wip(TODAY)).isNull();
    }

    /**
     * The two counters are independent and the migration calls confusing them
     * "the single most misread concept in the spec". A reopen starts a fresh
     * journey with iteration back at 1, so a bounce in cycle 1 must not follow
     * a ticket into cycle 2.
     */
    @Test
    @DisplayName("rework counts the iteration inside the LATEST cycle, so a reopen clears it")
    void reworkIsScopedToTheCurrentCycle() {
        long bouncing = ticket("2026-08-09 08:00:00", null, "MEDIUM");
        transition(bouncing, 1, 1, "DEV", "2026-08-09 09:00:00", "2026-08-09 12:00:00", 180);
        reworkTransition(bouncing, 1, 2, 3, "DEV", "2026-08-09 12:00:00", null);

        long reopened = ticket("2026-08-09 08:00:00", null, "MEDIUM");
        // Bounced twice in cycle 1, then reopened into a clean cycle 2.
        reworkTransition(reopened, 1, 1, 3, "DEV", "2026-08-09 09:00:00", "2026-08-09 18:00:00");
        transition(reopened, 2, 1, "DEV", "2026-08-10 09:00:00", null, null);

        worker.refreshOnce();

        assertThat(stat(TODAY, "rework_open"))
                .as("only the still-bouncing ticket; the reopened one is on a clean cycle 2")
                .isEqualTo(1);
        assertThat(stat(TODAY, "pingpong_open"))
                .as("iteration 3 in the current cycle is ping-pong; a cycle-1 bounce is not")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("rework is stock, so a ticket bouncing for three days counts once per day")
    void reworkIsStockNotFlow() {
        long id = ticket("2026-08-09 08:00:00", null, "MEDIUM");
        reworkTransition(id, 1, 1, 2, "DEV", "2026-08-09 09:00:00", null);

        worker.refreshOnce();

        for (LocalDate day : List.of(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 11), TODAY)) {
            assertThat(stat(day, "rework_open")).as("%s", day).isEqualTo(1);
        }
        assertThat(stat(TODAY, "pingpong_open"))
                .as("two passes is rework and not yet ping-pong — §4A.7 escalates at three")
                .isZero();
    }

    @Test
    @DisplayName("a project whose tickets stop bouncing goes back to zero")
    void reworkIsClearedRatherThanLeftStanding() {
        jdbc.update("""
                INSERT INTO daily_ticket_stats (stat_date, project_id, rework_open, pingpong_open, computed_at)
                VALUES (?, ?, 7, 4, ?)
                """, TODAY, projectId, java.sql.Timestamp.from(NOW));

        worker.refreshOnce();

        assertThat(stat(TODAY, "rework_open"))
                .as("a quality warning that can be earned and never cleared is worse than none")
                .isZero();
        assertThat(stat(TODAY, "pingpong_open")).isZero();
    }

    @Test
    @DisplayName("stage rows count entries on the day they began and exits on the day they ended")
    void stageFlowSplitsEntriesFromExits() {
        long id = ticket("2026-08-09 08:00:00", null, "MEDIUM");
        // Entered Monday, left Tuesday — one entry and one exit, two days apart.
        transition(id, 1, 1, "DEV", "2026-08-10 09:00:00", "2026-08-11 09:00:00", 480);

        worker.refreshOnce();

        assertThat(stageStat(LocalDate.of(2026, 8, 10), "DEV", "entered")).isEqualTo(1);
        assertThat(stageStat(LocalDate.of(2026, 8, 10), "DEV", "exited")).isZero();
        assertThat(stageStat(LocalDate.of(2026, 8, 11), "DEV", "exited")).isEqualTo(1);
        assertThat(stageStat(LocalDate.of(2026, 8, 11), "DEV", "elapsed_mins"))
                .as("working minutes from the transition, attributed to the day it sealed")
                .isEqualTo(480);
    }

    @Test
    @DisplayName("an unsealed visit contributes no elapsed minutes")
    void openVisitsAreNotAveraged() {
        long id = ticket("2026-08-09 08:00:00", null, "MEDIUM");
        transition(id, 1, 1, "QA", "2026-08-10 09:00:00", null, null);

        worker.refreshOnce();

        assertThat(stageStat(LocalDate.of(2026, 8, 10), "QA", "entered")).isEqualTo(1);
        assertThat(stageStat(LocalDate.of(2026, 8, 10), "QA", "exited"))
                .as("a ticket still in a stage has no duration yet; counting a partial "
                        + "stay drags the average down worst where work is piling up")
                .isZero();
        assertThat(stageStat(LocalDate.of(2026, 8, 10), "QA", "elapsed_mins")).isZero();
    }

    /**
     * 🔴 CLAUDE.md: "All SLA and duration maths use the working calendar."
     *
     * <p>{@code TIMESTAMPDIFF} would report this handoff as 2,880 minutes of
     * queue waste. Widget 19 exists to point at queue waste, so that is the one
     * wrong answer it must not give — a chart spiking every Monday teaches the
     * reader to ignore the only signal it carries.
     */
    @Test
    @DisplayName("handoff latency is working minutes, so a weekend is not two days of queue waste")
    void handoffLatencySkipsTheWeekend() {
        long id = ticket("2026-08-06 08:00:00", null, "MEDIUM");
        // Friday 7 Aug 2026 17:00 → Monday 10 Aug 09:00. Wall clock: 3,840
        // minutes. Working time: the calendar's Friday remainder plus Monday's
        // morning, and nothing for Saturday or Sunday.
        transition(id, 1, 1, "DEV", "2026-08-06 09:00:00", "2026-08-07 17:00:00", 480);
        transition(id, 1, 2, "QA", "2026-08-10 09:00:00", null, null);

        worker.refreshOnce();

        Integer minutes = stageStat(LocalDate.of(2026, 8, 10), "QA", "handoff_mins");
        assertThat(stageStat(LocalDate.of(2026, 8, 10), "QA", "handoff_count")).isEqualTo(1);
        assertThat(minutes)
                .as("wall-clock would be 3840; anything near it means the calendar was skipped")
                .isNotNull()
                .isLessThan(1440);
    }

    @Test
    @DisplayName("the handoff is charged to the receiving stage, not to the one that finished")
    void handoffIsChargedToTheQueueItWaitedIn() {
        long id = ticket("2026-08-09 08:00:00", null, "MEDIUM");
        transition(id, 1, 1, "DEV", "2026-08-10 09:00:00", "2026-08-10 10:00:00", 60);
        transition(id, 1, 2, "QA", "2026-08-10 12:00:00", null, null);

        worker.refreshOnce();

        assertThat(stageStat(LocalDate.of(2026, 8, 10), "QA", "handoff_count"))
                .as("QA is the queue the ticket sat in")
                .isEqualTo(1);
        assertThat(stageStat(LocalDate.of(2026, 8, 10), "DEV", "handoff_count"))
                .as("charging the sender blames the team that did its job")
                .isZero();
    }

    @Test
    @DisplayName("a first hop has no handoff, since nothing preceded it")
    void theFirstHopIsNotAHandoff() {
        long id = ticket("2026-08-09 08:00:00", null, "MEDIUM");
        transition(id, 1, 1, "INTAKE", "2026-08-10 09:00:00", null, null);

        worker.refreshOnce();

        assertThat(stageStat(LocalDate.of(2026, 8, 10), "INTAKE", "handoff_count")).isZero();
    }

    @Test
    @DisplayName("effort lands on its work_date and against its own stage")
    void activeMinutesFollowTheWorkDate() {
        long id = ticket("2026-08-09 08:00:00", null, "MEDIUM");
        transition(id, 1, 1, "DEV", "2026-08-10 09:00:00", "2026-08-11 09:00:00", 480);
        effort(id, "DEV", LocalDate.of(2026, 8, 10), "2.50");

        worker.refreshOnce();

        assertThat(stageStat(LocalDate.of(2026, 8, 10), "DEV", "active_mins"))
                .as("2.5 hours attributed to the day the work happened, per §4A.4")
                .isEqualTo(150);
    }

    @Test
    @DisplayName("recomputing a day twice does not double the stage rows")
    void stageStatsAreRecomputedNotAccumulated() {
        long id = ticket("2026-08-09 08:00:00", null, "MEDIUM");
        transition(id, 1, 1, "DEV", "2026-08-10 09:00:00", "2026-08-10 17:00:00", 480);

        worker.refreshOnce();
        worker.refreshOnce();

        assertThat(stageStat(LocalDate.of(2026, 8, 10), "DEV", "exited")).isEqualTo(1);
        assertThat(stageStat(LocalDate.of(2026, 8, 10), "DEV", "elapsed_mins")).isEqualTo(480);
    }

    // ── Dashboard Rework PR 4 · Today's Progress schema ──────────────────────

    /**
     * {@code ns_overdue} and {@code ns_due_today} must be disjoint so a
     * not-started ticket due today reads as due, not overdue — see
     * {@code DailyStatsRepository.refreshTodayStats}'s note on why this pair
     * uses {@code dayStart} rather than {@code wip_delayed}'s {@code dayEnd}.
     */
    @Test
    @DisplayName("not-started buckets: overdue-to-start and due-today are disjoint, both roll into the total")
    void notStartedBuckets() {
        ticketWithStatus("2026-08-01 09:00:00", "NEW", "2026-08-09 17:00:00");   // due yesterday
        ticketWithStatus("2026-08-01 09:00:00", "REOPENED", "2026-08-10 17:00:00"); // due today
        ticketWithStatus("2026-08-01 09:00:00", "NEW", "2026-08-20 17:00:00");   // due later
        worker.refreshOnce();

        LocalDate d = LocalDate.of(2026, 8, 10);
        assertThat(stat(d, "ns_total")).isEqualTo(3);
        assertThat(stat(d, "ns_overdue")).as("due before today started").isEqualTo(1);
        assertThat(stat(d, "ns_due_today")).as("due inside today's window").isEqualTo(1);
    }

    /**
     * {@code wip_delayed} keeps the class's established {@code < dayEnd}
     * boundary, so anything due today is already counted delayed and
     * {@code wip_near_delay} only ever starts strictly after today.
     */
    @Test
    @DisplayName("a WIP ticket due today is delayed, not near-delay")
    void wipDueTodayIsDelayedNotNearDelay() {
        ticketWithStatus("2026-08-01 09:00:00", "IN_PROGRESS", "2026-08-10 17:00:00");
        worker.refreshOnce();

        LocalDate d = LocalDate.of(2026, 8, 10);
        assertThat(stat(d, "wip_delayed")).isEqualTo(1);
        assertThat(stat(d, "wip_near_delay")).isZero();
    }

    /**
     * The scenario the plan names by name: a ticket due on a weekend day must
     * show as near-delay on the Friday before it, because Monday — not
     * Saturday — is {@code nextWorkingDay}, and {@code wip_near_delay} is
     * "due on or before" it.
     */
    @Test
    @DisplayName("near-delay reaches across the weekend to the next working day")
    void nearDelaySpansTheWeekend() {
        // Friday 2026-08-07. Saturday the 8th is inside the window; Tuesday
        // the 11th (after Monday the 10th) is not.
        ticketWithStatus("2026-08-01 09:00:00", "IN_PROGRESS", "2026-08-08 10:00:00");
        ticketWithStatus("2026-08-01 09:00:00", "IN_PROGRESS", "2026-08-11 10:00:00");
        worker.refreshOnce();

        LocalDate friday = LocalDate.of(2026, 8, 7);
        assertThat(stat(friday, "wip_near_delay"))
                .as("due Saturday, and Monday is the next working day").isEqualTo(1);
        assertThat(stat(friday, "wip_delayed")).isZero();
    }

    /**
     * {@code tickets.updated_at} carries no history, so a day other than the
     * one the worker is actually running on must answer {@code NULL} rather
     * than a wrong count — see the migration header.
     */
    @Test
    @DisplayName("wip_updated_today is NULL for any day that is not the actual current day")
    void wipUpdatedTodayOnlyAnswersForToday() {
        long id = ticketWithStatus("2026-07-20 09:00:00", "IN_PROGRESS", "2099-01-01 00:00:00");
        // Forces updated_at into the backfill window this ticket was created in.
        jdbc.update("UPDATE tickets SET updated_at = ? WHERE id = ?", "2026-07-20 10:00:00", id);
        worker.refreshOnce();

        assertThat(jdbc.queryForObject(
                "SELECT wip_updated_today FROM daily_ticket_stats WHERE stat_date = ? AND project_id = ?",
                Integer.class, LocalDate.of(2026, 7, 20), projectId))
                .as("a backfilled day has no honest answer for updated_at").isNull();
        assertThat(stat(TODAY, "wip_updated_today")).as("today does").isNotNull();
    }

    @Test
    @DisplayName("blocked splits ON_HOLD from AWAITING_INFO, and neither is WIP")
    void blockedIsNotWip() {
        ticketWithStatus("2026-08-01 09:00:00", "ON_HOLD", null);
        ticketWithStatus("2026-08-01 09:00:00", "AWAITING_INFO", null);
        worker.refreshOnce();

        LocalDate d = TODAY;
        assertThat(stat(d, "blocked_on_hold")).isEqualTo(1);
        assertThat(stat(d, "blocked_awaiting_info")).isEqualTo(1);
        assertThat(stat(d, "wip_total"))
                .as("category IN_PROGRESS's paused sub-statuses are Blocked, not WIP").isZero();
    }

    /**
     * The two ways onto the Pending Review card, combined with OR rather
     * than summed — a ticket satisfying both must not be counted twice.
     */
    @Test
    @DisplayName("pending review combines RESOLVED status and review-stage placement without double-counting")
    void pendingReviewCombinesBothPathsOnce() {
        long template = reviewTemplate();
        ticketWithStatus("2026-08-01 09:00:00", "RESOLVED", null);   // status path only
        long inStage = ticketWithStatus("2026-08-01 09:00:00", "IN_PROGRESS", null);
        setStage(inStage, template, "SIGNOFF");                      // stage path only
        long both = ticketWithStatus("2026-08-01 09:00:00", "RESOLVED", null);
        setStage(both, template, "VERIFY");                          // both paths, one ticket

        worker.refreshOnce();

        assertThat(stat(TODAY, "pending_review"))
                .as("three tickets, three qualifying paths, but the last collapses to one")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("a stage the master does not flag as review does not count")
    void nonReviewStageDoesNotCountAsPendingReview() {
        long template = reviewTemplate();
        long id = ticketWithStatus("2026-08-01 09:00:00", "IN_PROGRESS", null);
        setStage(id, template, "DEV");   // DEV is never flagged is_review_stage

        worker.refreshOnce();

        assertThat(stat(TODAY, "pending_review")).isZero();
    }

    @Test
    @DisplayName("started/finished today read ticket_cycles, bucketed against the cycle's own due date")
    void startedAndFinishedBuckets() {
        long onTime = ticket("2026-08-01 09:00:00", null, "MEDIUM");
        long early = ticket("2026-08-01 09:00:00", null, "MEDIUM");
        long late = ticket("2026-08-01 09:00:00", null, "MEDIUM");
        cycle(onTime, 1, "2026-08-10 09:00:00", "2026-08-10 15:00:00", "2026-08-10 17:00:00");
        cycle(early, 1, "2026-08-10 09:00:00", "2026-08-10 15:00:00", "2026-08-12 17:00:00");
        cycle(late, 1, "2026-08-10 09:00:00", "2026-08-10 15:00:00", "2026-08-09 17:00:00");

        worker.refreshOnce();

        LocalDate d = LocalDate.of(2026, 8, 10);
        assertThat(stat(d, "started_today")).isEqualTo(3);
        assertThat(stat(d, "finished_on_time")).isEqualTo(1);
        assertThat(stat(d, "finished_early")).as("finished two calendar days ahead of its due date").isEqualTo(1);
        assertThat(stat(d, "finished_late")).isEqualTo(1);
    }

    @Test
    @DisplayName("open_by_role groups by the assignee's role, with UNASSIGNED for assigned_to IS NULL")
    void openByRoleGroupsByAssigneeRole() {
        long qaUser = userWithRole("QA");
        ticketAssignedTo(qaUser);
        ticketAssignedTo(qaUser);
        jdbc.update("INSERT INTO tickets (ticket_code, project_id, title, level, original_level, "
                        + "date_reported, planned_close_date) "
                        + "VALUES (?, ?, 'unassigned probe', 'MEDIUM', 'MEDIUM', ?, '2099-01-01 00:00:00')",
                "ST-26-" + SEQ.incrementAndGet(), projectId, "2026-08-01 09:00:00");

        worker.refreshOnce();

        String json = jdbc.queryForObject(
                "SELECT open_by_role FROM daily_ticket_stats WHERE stat_date = ? AND project_id = ?",
                String.class, TODAY, projectId);
        assertThat(json).contains("\"QA\": 2").contains("\"UNASSIGNED\": 1");
    }

    /**
     * The MIS table's own columns, computed the same way the project table's
     * are — reconciling row for row is the whole point of naming them alike.
     */
    @Test
    @DisplayName("the resource table's new counters agree with the project table's for a single assignee")
    void resourceCountersAgreeWithProjectCounters() {
        ticketWithStatus("2026-08-01 09:00:00", "NEW", "2026-08-09 17:00:00");
        ticketWithStatus("2026-08-01 09:00:00", "IN_PROGRESS", "2026-08-10 17:00:00");
        ticketWithStatus("2026-08-01 09:00:00", "ON_HOLD", null);
        worker.refreshOnce();

        for (String column : new String[] {
                "ns_total", "ns_overdue", "wip_total", "wip_delayed", "blocked_on_hold"}) {
            assertThat(resourceStat(TODAY, userId, column))
                    .as(column)
                    .isEqualTo(stat(TODAY, column));
        }
    }

    // ── Dashboard Rework Dev 2, PR 11 · weekly columns ────────────────────────

    /**
     * {@code tickets.pct_complete} carries no history — the migration's own
     * reason, restated as a boundary: a ticket open across a backfilled day
     * and today must answer {@code NULL} for the former and a real sum for
     * the latter, from the identical ticket, in the identical pass.
     */
    @Test
    @DisplayName("open_pct_sum answers only for the actual current day")
    void openPctSumOnlyAnswersForToday() {
        long id = ticketWithStatus("2026-07-20 09:00:00", "IN_PROGRESS", "2099-01-01 00:00:00");
        jdbc.update("UPDATE tickets SET pct_complete = 40 WHERE id = ?", id);
        worker.refreshOnce();

        assertThat(jdbc.queryForObject(
                "SELECT open_pct_sum FROM daily_ticket_stats WHERE stat_date = ? AND project_id = ?",
                Integer.class, LocalDate.of(2026, 7, 20), projectId))
                .as("a backfilled day has no honest answer for a live-only column").isNull();
        assertThat(stat(TODAY, "open_pct_sum")).as("today does").isEqualTo(40);
    }

    /**
     * Unlike {@code pct_complete}, delay is pure arithmetic against {@code
     * planned_close_date} — stable regardless of when the pass runs — so a
     * backfilled day gets a real number, not {@code NULL}.
     */
    @Test
    @DisplayName("delay_days_sum backfills honestly from planned_close_date")
    void delayDaysSumBackfills() {
        // Reported and due on 1 Aug; still open five days later on the 6th.
        ticketWithStatus("2026-08-01 09:00:00", "IN_PROGRESS", "2026-08-01 09:00:00");
        worker.refreshOnce();

        assertThat(stat(LocalDate.of(2026, 8, 6), "delay_days_sum")).isEqualTo(5);
        assertThat(stat(LocalDate.of(2026, 8, 1), "delay_days_sum"))
                .as("due the same day it was reported — not yet late").isZero();
    }

    /** A ticket with no due date is not late by any measure — {@code DATEDIFF} against {@code NULL} must not poison the sum. */
    @Test
    @DisplayName("delay_days_sum ignores a ticket with no planned_close_date")
    void delayDaysSumIgnoresNoDueDate() {
        ticketWithStatus("2026-08-01 09:00:00", "IN_PROGRESS", null);
        worker.refreshOnce();

        assertThat(stat(TODAY, "delay_days_sum")).isZero();
    }

    @Test
    @DisplayName("open_due_next_7 counts the seventh day and excludes the eighth")
    void openDueNext7RespectsTheWindow() {
        ticketWithStatus("2026-08-01 09:00:00", "NEW", "2026-08-07 10:00:00");
        ticketWithStatus("2026-08-01 09:00:00", "NEW", "2026-08-08 10:00:00");
        worker.refreshOnce();

        assertThat(stat(LocalDate.of(2026, 8, 1), "open_due_next_7"))
                .as("due on day 7 of a window starting day 1 — inside it").isEqualTo(1);
    }

    @Test
    @DisplayName("the resource table's weekly columns agree with the project table's for a single assignee")
    void resourceWeeklyColumnsAgreeWithProjectColumns() {
        long id = ticketWithStatus("2026-08-01 09:00:00", "IN_PROGRESS", "2026-08-01 09:00:00");
        jdbc.update("UPDATE tickets SET pct_complete = 25 WHERE id = ?", id);
        worker.refreshOnce();

        assertThat(resourceStat(TODAY, userId, "pct_sum")).isEqualTo(stat(TODAY, "open_pct_sum"));
        assertThat(resourceStat(TODAY, userId, "delay_days_sum")).isEqualTo(stat(TODAY, "delay_days_sum"));
    }

    private long ticketWithStatus(String reportedAt, String status, String plannedCloseAt) {
        jdbc.update("INSERT INTO tickets (ticket_code, project_id, title, level, original_level, status, "
                        + "date_reported, planned_close_date, assigned_to) "
                        + "VALUES (?, ?, 'stats probe', 'MEDIUM', 'MEDIUM', ?, ?, ?, ?)",
                "ST-26-" + SEQ.incrementAndGet(), projectId, status, reportedAt, plannedCloseAt, userId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void ticketAssignedTo(long uid) {
        jdbc.update("INSERT INTO tickets (ticket_code, project_id, title, level, original_level, "
                        + "date_reported, planned_close_date, assigned_to) "
                        + "VALUES (?, ?, 'role probe', 'MEDIUM', 'MEDIUM', ?, '2099-01-01 00:00:00', ?)",
                "ST-26-" + SEQ.incrementAndGet(), projectId, "2026-08-01 09:00:00", uid);
    }

    private long userWithRole(String roleCode) {
        int n = SEQ.incrementAndGet();
        Long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code = ?", Long.class, roleCode);
        jdbc.update("INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id) "
                        + "VALUES (?, ?, ?, 'x', 'Stats IT', ?)",
                "E-R-" + n, "stats.role." + n, "stats.role." + n + "@example.com", roleId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    /** A template with VERIFY and SIGNOFF flagged as review stages, DEV not. */
    private long reviewTemplate() {
        int n = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO workflow_templates (name) VALUES (?)", "Stats review template " + n);
        long templateId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO workflow_stages (template_id, seq, stage_code, is_review_stage, "
                        + "display_name, owner_role) VALUES (?, 1, 'DEV', 0, 'Development', 'DEVELOPER')",
                templateId);
        jdbc.update("INSERT INTO workflow_stages (template_id, seq, stage_code, is_review_stage, "
                        + "display_name, owner_role) VALUES (?, 2, 'VERIFY', 1, 'Verification', 'DEVELOPER')",
                templateId);
        jdbc.update("INSERT INTO workflow_stages (template_id, seq, stage_code, is_review_stage, "
                        + "display_name, owner_role) VALUES (?, 3, 'SIGNOFF', 1, 'Sign-off', 'PM')",
                templateId);
        return templateId;
    }

    private void setStage(long ticketId, long templateId, String stageCode) {
        jdbc.update("UPDATE tickets SET workflow_template_id = ?, current_stage = ? WHERE id = ?",
                templateId, stageCode, ticketId);
    }

    /** One cycle with a start, a finish, and the due date it is measured against. */
    private void cycle(long ticketId, int cycleNo, String startDate, String finishedAt, String plannedCloseAt) {
        jdbc.update("""
                INSERT INTO ticket_cycles
                       (ticket_id, cycle_no, start_date, started_at, finished_at, planned_close_date)
                VALUES (?, ?, ?, ?, ?, ?)
                """, ticketId, cycleNo, startDate, startDate, finishedAt, plannedCloseAt);
    }

    // ── A-058 fixtures ───────────────────────────────────────────────────────

    /** One sealed or open visit. {@code exitedAt} null means the ticket is still there. */
    private void transition(long ticketId, int cycleNo, int seqNo, String toStage,
                            String enteredAt, String exitedAt, Integer durationMins) {
        jdbc.update("""
                INSERT INTO ticket_stage_transitions
                       (ticket_id, cycle_no, iteration_no, seq_no, to_stage, action_code,
                        entered_at, exited_at, duration_mins, is_current)
                VALUES (?, ?, 1, ?, ?, 'FORWARD', ?, ?, ?, ?)
                """, ticketId, cycleNo, seqNo, toStage, enteredAt, exitedAt, durationMins,
                exitedAt == null ? 1 : 0);
    }

    /**
     * A backward move, which is what {@code iteration_no} counts. Separate from
     * {@link #transition} so a test cannot raise the iteration by accident —
     * the whole of widget 17 rests on that column meaning "sent back".
     */
    private void reworkTransition(long ticketId, int cycleNo, int seqNo, int iterationNo,
                                  String toStage, String enteredAt, String exitedAt) {
        jdbc.update("""
                INSERT INTO ticket_stage_transitions
                       (ticket_id, cycle_no, iteration_no, seq_no, to_stage, action_code,
                        reason, entered_at, exited_at, is_current)
                VALUES (?, ?, ?, ?, ?, 'REWORK', 'stats probe', ?, ?, ?)
                """, ticketId, cycleNo, iterationNo, seqNo, toStage, enteredAt, exitedAt,
                exitedAt == null ? 1 : 0);
    }

    private void effort(long ticketId, String stageCode, LocalDate workDate, String hours) {
        jdbc.update("""
                INSERT INTO ticket_effort_logs
                       (ticket_id, user_id, cycle_no, stage_code, iteration_no, work_date, hours)
                VALUES (?, ?, 1, ?, 1, ?, ?)
                """, ticketId, userId, stageCode, workDate, new java.math.BigDecimal(hours));
    }

    private String wip(LocalDate day) {
        return jdbc.queryForObject(
                "SELECT wip_by_stage FROM daily_ticket_stats WHERE stat_date = ? AND project_id = ?",
                String.class, day, projectId);
    }

    /**
     * A stage with no row that day answers {@code 0} rather than throwing.
     * "Nothing was recorded" is what most of these assertions are checking, and
     * an {@code EmptyResultDataAccessException} would report it as an error
     * rather than as the zero it is.
     */
    private Integer stageStat(LocalDate day, String stageCode, String column) {
        return jdbc.queryForObject(
                "SELECT COALESCE((SELECT " + column + " FROM stage_daily_stats "
                        + "WHERE stat_date = ? AND project_id = ? AND stage_code = ?), 0)",
                Integer.class, day, projectId, stageCode);
    }
}
