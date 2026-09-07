package com.edunext.edutrack.worker.onboarding.stats;

import com.edunext.edutrack.worker.WorkerApplication;
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

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-120 · both summary tables against a real MySQL 8.4.
 *
 * <p>Almost everything this task ships is a {@code CASE} expression whose value
 * is that its arms are disjoint, and a mocked repository would assert nothing
 * about that. So the assertions below are of three kinds and each is about a
 * property no unit test could reach:
 *
 * <ul>
 *   <li><b>The partitions add up.</b> A-108 states two arithmetic contracts this
 *       class is obliged to keep, and the way they break is that a journey which
 *       is overdue <em>and</em> blocked lands in two buckets — every figure stays
 *       individually plausible and the total quietly exceeds the population.</li>
 *   <li><b>The boundaries.</b> The amber threshold, the IST day edge and the
 *       early/on-time/late split are all off-by-one country, and asserting a
 *       total without pinning an edge would pass against a rule that is a day
 *       out throughout.</li>
 *   <li><b>Stock is not rewritten for a past day.</b> The one property that, if
 *       it broke, would flatten every trend chart into a straight line at
 *       today's value while looking exactly as it always had.</li>
 * </ul>
 *
 * <h2>No shared teardown, and that is forced rather than chosen</h2>
 *
 * <p>{@code ob_step_history} is append-only and A-105's sibling triggers refuse a
 * DELETE, which also pins the steps and journeys it points at. So each test owns
 * its own products, clients and users, and every assertion is scoped to them —
 * the same call {@code DigestSchedulerIT} made against {@code ticket_history}.
 * The refresh itself is global by construction, so this costs nothing: each pass
 * recomputes every product, and the tests read back only their own rows.
 */
@Testcontainers
@SpringBootTest(classes = WorkerApplication.class)
@Import(ObStatsRefreshIT.FixedClock.class)
class ObStatsRefreshIT {

    /** Wednesday 2026-08-12, 14:30 in the seeded Asia/Kolkata calendar. */
    private static final Instant NOW = Instant.parse("2026-08-12T09:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 12);

    /** Midnight IST on the 12th, and on the 13th. The day every card is filtered by. */
    private static final Instant DAY_START = Instant.parse("2026-08-11T18:30:00Z");

    /** 09:30–18:30 IST on the 12th, in UTC. The calendar's working window. */
    private static final Instant WORK_START = Instant.parse("2026-08-12T04:00:00Z");

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_ob_stats_it")
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
        // Every background schedule out of the way. A `fixedDelay` job fires once
        // the instant the context is up regardless of its interval, and this
        // one's stock passes DELETE and rewrite the day these tests are asserting
        // on — the race StatsRefreshWorker documents, arriving on a second table.
        registry.add("edutrack.ob-stats.enabled", () -> "false");
        registry.add("edutrack.ob-stats.refresh-interval", () -> "PT6H");
        registry.add("edutrack.stats.enabled", () -> "false");
        registry.add("edutrack.stats.refresh-interval", () -> "PT6H");
        registry.add("edutrack.outbox.enabled", () -> "false");
        registry.add("edutrack.ob-outbox.enabled", () -> "false");
        registry.add("edutrack.sla.initial-delay", () -> "PT24H");
        registry.add("edutrack.chain.verify-cron", () -> "0 0 5 31 2 *");
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
    ObStatsRefreshWorker worker;

    // ── ob_dashboard_summary · the journey buckets and RAG ───────────────

    @Test
    @DisplayName("the four journey buckets partition journeys_total exactly once")
    void theFourBucketsPartitionTheTotal() {
        long product = product();
        long other = product();
        long template = template(product);

        completedJourney(client(), product, template);
        lockedJourney(client(), product, template);
        runningJourney(client(), product, template);

        // §5.5's service-level hold: past its own gate, waiting on a sibling
        // service of the same client.
        long holdingClient = client();
        long holder = runningJourney(holdingClient, other, template(other));
        heldJourney(holdingClient, product, template, holder);

        worker.refreshOnce();

        Map<String, Object> row = summary(product);
        assertThat(n(row, "journeys_total")).isEqualTo(4);
        assertThat(n(row, "journeys_completed")).isEqualTo(1);
        assertThat(n(row, "journeys_locked")).isEqualTo(1);
        assertThat(n(row, "journeys_held")).isEqualTo(1);
        assertThat(n(row, "journeys_open_running")).isEqualTo(1);
        assertThat(n(row, "journeys_locked") + n(row, "journeys_held")
                + n(row, "journeys_open_running") + n(row, "journeys_completed"))
                .isEqualTo(n(row, "journeys_total"));
    }

    @Test
    @DisplayName("a locked journey has no colour at all, even carrying an overdue step")
    void aLockedJourneyIsNotGreen() {
        long product = product();
        long journey = lockedJourney(client(), product, template(product));
        step(journey, 1, "IN_PROGRESS", null, NOW.minusSeconds(86_400), NOW.minusSeconds(3_600), null);

        worker.refreshOnce();

        Map<String, Object> row = summary(product);
        // The RAG columns partition the RUNNING population, and this journey is
        // not in it. Folding it into green would report a client who has not
        // started as on track — OB-03 renders it "Prerequisites pending".
        assertThat(n(row, "journeys_locked")).isEqualTo(1);
        assertThat(n(row, "rag_green")).isZero();
        assertThat(n(row, "rag_amber")).isZero();
        assertThat(n(row, "rag_red")).isZero();
        // The card, unlike the colour, is about work rather than health: the step
        // is late whether or not its journey has a colour.
        assertThat(n(row, "steps_overdue")).isEqualTo(1);
    }

    @Test
    @DisplayName("worst wins across a journey's steps — one red, not one red and one amber")
    void worstWinsWithinAJourney() {
        long product = product();
        long journey = runningJourney(client(), product, template(product));
        // 80% through its TAT: amber on its own.
        step(journey, 1, "IN_PROGRESS", NOW.minusSeconds(80 * 3_600), NOW.plusSeconds(20 * 3_600), null, null);
        // Past its date: red.
        step(journey, 2, "IN_PROGRESS", NOW.minusSeconds(50 * 3_600), NOW.minusSeconds(3_600), null, null);

        worker.refreshOnce();

        Map<String, Object> row = summary(product);
        assertThat(n(row, "rag_red")).isEqualTo(1);
        assertThat(n(row, "rag_amber")).isZero();
        assertThat(n(row, "rag_green") + n(row, "rag_amber") + n(row, "rag_red"))
                .isEqualTo(n(row, "journeys_open_running"));
    }

    @Test
    @DisplayName("a step waiting on the client is never overdue — §5.7 stopped its clock")
    void waitingOnClientDoesNotBreach() {
        long product = product();
        long journey = runningJourney(client(), product, template(product));
        step(journey, 1, "WAITING_ON_CLIENT", NOW.minusSeconds(200 * 3_600),
                NOW.minusSeconds(72 * 3_600), null, null);

        worker.refreshOnce();

        Map<String, Object> row = summary(product);
        // Three days past a suspended promise. Painting it red would charge the
        // organisation for a delay it attributed to the client, which is the
        // attribution ob_step_clock_events exists to defend in a §14 dispute.
        assertThat(n(row, "rag_red")).isZero();
        assertThat(n(row, "rag_green")).isEqualTo(1);
        assertThat(n(row, "steps_overdue")).isZero();
    }

    @Test
    @DisplayName("a blocked step past its date IS overdue — an internal block keeps burning TAT")
    void internalBlockStillBreaches() {
        long product = product();
        long journey = runningJourney(client(), product, template(product));
        blockedStep(journey, 1, NOW.minusSeconds(200 * 3_600), NOW.minusSeconds(72 * 3_600), null);

        worker.refreshOnce();

        Map<String, Object> row = summary(product);
        // The asymmetry with the test above is A-105's, not this class's: only a
        // PAUSED clock event stops the clock, and BLOCKED does not write one.
        assertThat(n(row, "rag_red")).isEqualTo(1);
        assertThat(n(row, "steps_overdue")).isEqualTo(1);
    }

    @Test
    @DisplayName("amber arrives exactly at the configured share of TAT, not before it")
    void amberIsABoundary() {
        long justUnder = product();
        long justOver = product();
        long a = runningJourney(client(), justUnder, template(justUnder));
        long b = runningJourney(client(), justOver, template(justOver));

        // A 100-hour window. 74 hours elapsed is green; 75 is amber.
        step(a, 1, "IN_PROGRESS", NOW.minusSeconds(74 * 3_600), NOW.plusSeconds(26 * 3_600), null, null);
        step(b, 1, "IN_PROGRESS", NOW.minusSeconds(75 * 3_600), NOW.plusSeconds(25 * 3_600), null, null);

        worker.refreshOnce();

        assertThat(n(summary(justUnder), "rag_green")).isEqualTo(1);
        assertThat(n(summary(justUnder), "rag_amber")).isZero();
        assertThat(n(summary(justOver), "rag_amber")).isEqualTo(1);
        assertThat(n(summary(justOver), "rag_green")).isZero();
    }

    @Test
    @DisplayName("a journey that has not started yet is green, not amber")
    void anUnstartedStepHasNoElapsedShare() {
        long product = product();
        long journey = runningJourney(client(), product, template(product));
        step(journey, 1, "PENDING", null, NOW.plusSeconds(3_600), null, null);

        worker.refreshOnce();

        assertThat(n(summary(product), "rag_green")).isEqualTo(1);
        assertThat(n(summary(product), "rag_amber")).isZero();
    }

    // ── ob_dashboard_summary · the OB-02 cards ───────────────────────────

    @Test
    @DisplayName("the overdue card counts clients; the step card counts items")
    void clientsAreCountedOnceHoweverLateTheyAre() {
        long product = product();
        long journey = runningJourney(client(), product, template(product));
        step(journey, 1, "IN_PROGRESS", NOW.minusSeconds(50 * 3_600), NOW.minusSeconds(3_600), null, null);
        step(journey, 2, "IN_PROGRESS", NOW.minusSeconds(50 * 3_600), NOW.minusSeconds(7_200), null, null);

        worker.refreshOnce();

        Map<String, Object> row = summary(product);
        assertThat(n(row, "steps_overdue")).isEqualTo(2);
        // A-108's own note: one client late on several services is one Overdue
        // Client, and the two figures differ exactly when the card matters most.
        assertThat(n(row, "clients_overdue")).isEqualTo(1);
    }

    @Test
    @DisplayName("\"due today\" is the organisation's day, not UTC's")
    void dueTodayIsTheCalendarsDay() {
        long product = product();
        long journey = runningJourney(client(), product, template(product));

        // 00:30 IST on the 12th — today. UTC still calls this the 11th.
        step(journey, 1, "IN_PROGRESS", DAY_START, Instant.parse("2026-08-11T19:00:00Z"), null, null);
        // 01:30 IST on the 13th — tomorrow, and still this week.
        step(journey, 2, "IN_PROGRESS", DAY_START, Instant.parse("2026-08-12T20:00:00Z"), null, null);
        // 22:30 IST on the 11th — yesterday, and still this week.
        step(journey, 3, "IN_PROGRESS", DAY_START, Instant.parse("2026-08-11T17:00:00Z"), null, null);
        // Monday the 17th — next week. The week boundary, not just the day one.
        step(journey, 4, "IN_PROGRESS", DAY_START, Instant.parse("2026-08-17T06:00:00Z"), null, null);

        worker.refreshOnce();

        Map<String, Object> row = summary(product);
        assertThat(n(row, "steps_due_today")).isEqualTo(1);
        assertThat(n(row, "steps_due_this_week")).isEqualTo(3);
    }

    @Test
    @DisplayName("the client cards count distinct clients per product")
    void clientCards() {
        long product = product();
        long template = template(product);

        long liveOne = client("LIVE");
        long liveTwo = client("LIVE");
        long onboarding = client("ONBOARDING");
        runningJourney(liveOne, product, template);
        runningJourney(liveTwo, product, template);
        long escalated = runningJourney(onboarding, product, template);
        long step = step(escalated, 1, "IN_PROGRESS", NOW.minusSeconds(3_600), NOW.plusSeconds(3_600), null, null);
        escalation(onboarding, escalated, step);

        worker.refreshOnce();

        Map<String, Object> row = summary(product);
        assertThat(n(row, "clients_live")).isEqualTo(2);
        assertThat(n(row, "clients_onboarding")).isEqualTo(1);
        assertThat(n(row, "clients_escalated")).isEqualTo(1);
    }

    @Test
    @DisplayName("an archived journey leaves the board and takes its steps with it")
    void archivingIsTheModulesSoftDelete() {
        long product = product();
        long journey = runningJourney(client(), product, template(product));
        step(journey, 1, "IN_PROGRESS", NOW.minusSeconds(50 * 3_600), NOW.minusSeconds(3_600), null, null);

        worker.refreshOnce();
        assertThat(n(summary(product), "journeys_total")).isEqualTo(1);

        jdbc.update("UPDATE ob_journeys SET archived_at = ? WHERE id = ?", ts(NOW), journey);
        worker.refreshOnce();

        // No row at all rather than a row of zeroes: the DELETE-and-rewrite is
        // what lets a product stop earning one. An upsert could not retract it.
        assertThat(summaryOrNull(product)).isNull();
    }

    // ── ob_dashboard_summary · flow, and the history it must not rewrite ─

    @Test
    @DisplayName("the flow columns count what happened on the day, by its own boundaries")
    void flowCountsTheDaysEvents() {
        long product = product();
        long template = template(product);
        long a = client();
        long b = client();

        // Started at 11:00 IST today.
        long started = insertJourney(a, product, template, "OPEN", DAY_START, null, null,
                Instant.parse("2026-08-12T05:30:00Z"), null, null);
        // Completed at 12:00 IST today.
        long completed = insertJourney(b, product, template, "OPEN", DAY_START, null, null,
                NOW.minusSeconds(600_000), Instant.parse("2026-08-12T06:30:00Z"), null);

        step(started, 1, "DONE", NOW.minusSeconds(90_000), NOW.plusSeconds(3_600),
                Instant.parse("2026-08-12T06:00:00Z"), null);
        // Yesterday, 20:00 IST on the 11th. Belongs to the 11th, not to today.
        step(completed, 1, "DONE", NOW.minusSeconds(200_000), NOW.plusSeconds(3_600),
                Instant.parse("2026-08-11T14:30:00Z"), null);

        worker.refreshOnce();

        Map<String, Object> row = summary(product);
        assertThat(n(row, "journeys_started")).isEqualTo(1);
        assertThat(n(row, "journeys_went_live")).isEqualTo(1);
        assertThat(n(row, "steps_completed")).isEqualTo(1);
    }

    @Test
    @DisplayName("🔴 a later pass rewrites a past day's flow and leaves its stock alone")
    void stockIsNeverRecomputedForAPastDay() {
        long product = product();
        long template = template(product);
        long journey = runningJourney(client(), product, template);
        // Completed at 15:00 IST yesterday.
        step(journey, 1, "DONE", NOW.minusSeconds(400_000), NOW.plusSeconds(3_600),
                Instant.parse("2026-08-11T09:30:00Z"), null);

        LocalDate yesterday = TODAY.minusDays(1);
        // What the pass that ran yesterday recorded. RAG is a current value with
        // no history behind it, so this is the only record that it ever existed.
        jdbc.update("""
                INSERT INTO ob_dashboard_summary
                    (stat_date, product_id, journeys_total, journeys_open_running, rag_amber, computed_at)
                VALUES (?, ?, 9, 9, 4, ?)
                """, yesterday, product, ts(NOW.minusSeconds(86_400)));

        worker.refreshFlowForDay(yesterday, NOW);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM ob_dashboard_summary WHERE stat_date = ? AND product_id = ?",
                yesterday, product);
        // Recomputing this from today's journeys would report 1 open and 0 amber
        // — plausible, wrong, and it would flatten the trend chart above it into
        // a straight line at today's value while looking exactly as it always had.
        assertThat(n(row, "journeys_open_running")).isEqualTo(9);
        assertThat(n(row, "rag_amber")).isEqualTo(4);
        // Flow, on the other hand, derives from an immutable timestamp and is
        // exactly what a recovered outage needs recomputed.
        assertThat(n(row, "steps_completed")).isEqualTo(1);
    }

    @Test
    @DisplayName("a second pass over the same day produces the same numbers")
    void refreshIsIdempotent() {
        long product = product();
        long template = template(product);
        long journey = runningJourney(client(), product, template);
        step(journey, 1, "IN_PROGRESS", NOW.minusSeconds(80 * 3_600), NOW.plusSeconds(20 * 3_600), null, null);
        step(journey, 2, "DONE", NOW.minusSeconds(90_000), NOW.plusSeconds(3_600),
                Instant.parse("2026-08-12T06:00:00Z"), null);

        worker.refreshOnce();
        Map<String, Object> first = summary(product);
        worker.refreshOnce();
        Map<String, Object> second = summary(product);

        // Everything but computed_at, which is the point of computed_at.
        first.remove("computed_at");
        second.remove("computed_at");
        assertThat(second).isEqualTo(first);
    }

    // ── ob_implementor_daily_stats ───────────────────────────────────────

    @Test
    @DisplayName("an implementor carrying nothing still gets a row — the bench is on the grid")
    void theBenchIsOnTheGrid() {
        long idle = user();
        grantStepOwner(idle);

        worker.refreshOnce();

        Map<String, Object> row = implementor(idle);
        // §9 asks for this explicitly and A-108 flags it as the requirement most
        // likely to be lost: the natural GROUP BY produces nothing for somebody
        // who has just finished everything, and the grid then shows a
        // fully-delivered implementor as absent rather than as clear.
        assertThat(n(row, "clients_open")).isZero();
        assertThat(n(row, "on_track")).isZero();
    }

    @Test
    @DisplayName("the six workload columns partition clients_open")
    void theSixWorkloadColumnsPartition() {
        long owner = user();
        long product = product();
        long template = template(product);

        long delayed = runningJourney(client(), product, template);
        step(delayed, 1, "IN_PROGRESS", NOW.minusSeconds(50 * 3_600), NOW.minusSeconds(3_600), null, owner);

        long blocked = runningJourney(client(), product, template);
        blockedStep(blocked, 1, NOW.minusSeconds(3_600), NOW.plusSeconds(50 * 3_600), owner);

        long atRisk = runningJourney(client(), product, template);
        step(atRisk, 1, "IN_PROGRESS", NOW.minusSeconds(90 * 3_600), NOW.plusSeconds(10 * 3_600), null, owner);

        long notStarted = runningJourney(client(), product, template);
        step(notStarted, 1, "PENDING", null, NOW.plusSeconds(50 * 3_600), null, owner);

        long onTrack = runningJourney(client(), product, template);
        step(onTrack, 1, "IN_PROGRESS", NOW.minusSeconds(3_600), NOW.plusSeconds(99 * 3_600), null, owner);

        long ahead = runningJourney(client(), product, template);
        step(ahead, 1, "IN_PROGRESS", NOW.minusSeconds(3_600), NOW.plusSeconds(99 * 3_600), null, owner);
        // Finished on the 10th against a promise of the 12th: an earlier calendar
        // day in the organisation's zone, so early.
        step(ahead, 2, "DONE", NOW.minusSeconds(400_000), Instant.parse("2026-08-12T06:00:00Z"),
                Instant.parse("2026-08-10T06:00:00Z"), owner);

        worker.refreshOnce();

        Map<String, Object> row = implementor(owner);
        assertThat(n(row, "clients_open")).isEqualTo(6);
        assertThat(n(row, "delayed")).isEqualTo(1);
        assertThat(n(row, "blocked_waiting")).isEqualTo(1);
        assertThat(n(row, "at_risk")).isEqualTo(1);
        assertThat(n(row, "not_started")).isEqualTo(1);
        assertThat(n(row, "on_track")).isEqualTo(1);
        assertThat(n(row, "ahead_of_schedule")).isEqualTo(1);
        assertThat(n(row, "on_track") + n(row, "not_started") + n(row, "delayed")
                + n(row, "at_risk") + n(row, "blocked_waiting") + n(row, "ahead_of_schedule"))
                .isEqualTo(n(row, "clients_open"));
    }

    @Test
    @DisplayName("delayed outranks blocked — a delay does not hide behind its own excuse")
    void delayedBeatsBlockedForTheSameClient() {
        long owner = user();
        long product = product();
        long journey = runningJourney(client(), product, template(product));
        blockedStep(journey, 1, NOW.minusSeconds(200 * 3_600), NOW.minusSeconds(3_600), owner);

        worker.refreshOnce();

        Map<String, Object> row = implementor(owner);
        assertThat(n(row, "clients_open")).isEqualTo(1);
        assertThat(n(row, "delayed")).isEqualTo(1);
        assertThat(n(row, "blocked_waiting")).isZero();
    }

    @Test
    @DisplayName("two open steps for one client are one entry in one bucket")
    void theGridCountsClientsNotSteps() {
        long owner = user();
        long product = product();
        long journey = runningJourney(client(), product, template(product));
        step(journey, 1, "IN_PROGRESS", NOW.minusSeconds(3_600), NOW.plusSeconds(99 * 3_600), null, owner);
        step(journey, 2, "IN_PROGRESS", NOW.minusSeconds(3_600), NOW.plusSeconds(99 * 3_600), null, owner);

        worker.refreshOnce();

        assertThat(n(implementor(owner), "clients_open")).isEqualTo(1);
        assertThat(n(implementor(owner), "on_track")).isEqualTo(1);
    }

    @Test
    @DisplayName("completions split into early, on time and late by calendar day")
    void completionsAreClassified() {
        long owner = user();
        long product = product();
        long journey = runningJourney(client(), product, template(product));

        Instant dueAtNoonIst = Instant.parse("2026-08-12T06:30:00Z");
        Instant finishedAt11Ist = Instant.parse("2026-08-12T05:30:00Z");
        // Finished today against tomorrow's promise: an earlier calendar day, so
        // early. All four finish TODAY — these are per-day flow counters, and a
        // completion belongs to the day it happened however early it was.
        step(journey, 1, "DONE", NOW.minusSeconds(400_000),
                Instant.parse("2026-08-13T06:30:00Z"), finishedAt11Ist, owner);
        // Finished at 11:00 IST, an hour inside a noon promise. Same day, so on
        // time rather than early — TAT is a count of days and so is the marker.
        step(journey, 2, "DONE", NOW.minusSeconds(400_000), dueAtNoonIst,
                finishedAt11Ist, owner);
        // Finished at 13:00 IST against a noon promise. Late by the instant, even
        // though it is the same calendar day: a deadline is a deadline.
        step(journey, 3, "DONE", NOW.minusSeconds(400_000), dueAtNoonIst,
                Instant.parse("2026-08-12T07:30:00Z"), owner);
        // No promise to measure against. Counted in none of the three rather than
        // credited as on time.
        step(journey, 4, "DONE", NOW.minusSeconds(400_000), null,
                Instant.parse("2026-08-12T07:30:00Z"), owner);

        worker.refreshOnce();

        Map<String, Object> row = implementor(owner);
        assertThat(n(row, "completed_early")).isEqualTo(1);
        assertThat(n(row, "completed_on_time")).isEqualTo(1);
        assertThat(n(row, "completed_late")).isEqualTo(1);
    }

    @Test
    @DisplayName("blocked hours are working hours, clipped to the day and to the working window")
    void blockedHoursUseTheWorkingCalendar() {
        long owner = user();
        long product = product();
        long clientId = client();
        long journey = runningJourney(clientId, product, template(product));
        long step = blockedStep(journey, 1, NOW.minusSeconds(200 * 3_600), NOW.plusSeconds(50 * 3_600), owner);

        // Blocked at IST midnight and still blocked now, 14:30 IST. Fourteen and
        // a half hours of wall clock; five of working time, because the calendar
        // opens at 09:30 IST and the fold stops at `now`.
        history(journey, step, clientId, "BLOCKED", DAY_START);

        worker.refreshOnce();

        assertThat(n(implementor(owner), "blocked_hours")).isEqualTo(5);
    }

    @Test
    @DisplayName("a block that closes is charged only until it closed")
    void aClosedBlockStopsBeingCharged() {
        long owner = user();
        long product = product();
        long clientId = client();
        long journey = runningJourney(clientId, product, template(product));
        long step = step(journey, 1, "IN_PROGRESS", NOW.minusSeconds(200 * 3_600),
                NOW.plusSeconds(50 * 3_600), null, owner);

        // Blocked 09:30 IST, unblocked 13:30 IST: four working hours, and none of
        // the four and a half that follow before `now`.
        history(journey, step, clientId, "BLOCKED", WORK_START);
        history(journey, step, clientId, "IN_PROGRESS", WORK_START.plusSeconds(4 * 3_600));

        worker.refreshOnce();

        assertThat(n(implementor(owner), "blocked_hours")).isEqualTo(4);
    }

    // ── fixtures ─────────────────────────────────────────────────────────

    private Map<String, Object> summary(long productId) {
        return new HashMap<>(jdbc.queryForMap(
                "SELECT * FROM ob_dashboard_summary WHERE stat_date = ? AND product_id = ?",
                TODAY, productId));
    }

    private Map<String, Object> summaryOrNull(long productId) {
        return jdbc.query(
                "SELECT * FROM ob_dashboard_summary WHERE stat_date = ? AND product_id = ?",
                rs -> rs.next() ? Map.of("product_id", rs.getLong("product_id")) : null,
                TODAY, productId);
    }

    private Map<String, Object> implementor(long userId) {
        return jdbc.queryForMap(
                "SELECT * FROM ob_implementor_daily_stats WHERE stat_date = ? AND user_id = ?",
                TODAY, userId);
    }

    private static int n(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).intValue();
    }

    private long product() {
        int i = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO ob_products (code, name) VALUES (?, ?)", "P" + i, "Product " + i);
        return lastInsertId();
    }

    private long template(long productId) {
        jdbc.update("INSERT INTO ob_journey_templates (product_id, name) VALUES (?, ?)",
                productId, "Template " + SEQ.incrementAndGet());
        return lastInsertId();
    }

    private long client() {
        return client("ONBOARDING");
    }

    private long client(String status) {
        jdbc.update("INSERT INTO ob_clients (name, onboarding_date, overall_status) VALUES (?, ?, ?)",
                "Client " + SEQ.incrementAndGet(), LocalDate.of(2026, 7, 1), status);
        return lastInsertId();
    }

    private long user() {
        Long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code = 'DEVELOPER'", Long.class);
        String name = "impl" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id)
                VALUES (?, ?, ?, 'not-a-real-hash', ?, ?)
                """, name, name, name + "@example.com", name, roleId);
        return lastInsertId();
    }

    private void grantStepOwner(long userId) {
        jdbc.update("""
                INSERT INTO user_module_access (user_id, module, module_role)
                VALUES (?, 'ONBOARDING', 'OB_STEP_OWNER')
                """, userId);
    }

    private long runningJourney(long clientId, long productId, long templateId) {
        return insertJourney(clientId, productId, templateId, "OPEN", DAY_START, null, null,
                NOW.minusSeconds(600_000), null, null);
    }

    private long lockedJourney(long clientId, long productId, long templateId) {
        return insertJourney(clientId, productId, templateId, "LOCKED", null, null, null, null, null, null);
    }

    private long heldJourney(long clientId, long productId, long templateId, long heldBy) {
        return insertJourney(clientId, productId, templateId, "OPEN", DAY_START, heldBy, null,
                NOW.minusSeconds(600_000), null, null);
    }

    private long completedJourney(long clientId, long productId, long templateId) {
        return insertJourney(clientId, productId, templateId, "OPEN", DAY_START, null, null,
                NOW.minusSeconds(900_000), NOW.minusSeconds(100_000), null);
    }

    private long insertJourney(long clientId, long productId, long templateId, String gate,
                               Instant gateOpenedAt, Long heldBy, Instant releasedAt,
                               Instant startedAt, Instant completedAt, Instant archivedAt) {
        jdbc.update("""
                INSERT IGNORE INTO ob_client_applications (ob_client_id, product_id) VALUES (?, ?)
                """, clientId, productId);
        jdbc.update("""
                INSERT INTO ob_journeys (ob_client_id, product_id, template_id, gate_status,
                                         gate_opened_at, held_by_journey_id, released_at,
                                         started_at, completed_at, archived_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, clientId, productId, templateId, gate, ts(gateOpenedAt), heldBy, ts(releasedAt),
                ts(startedAt), ts(completedAt), ts(archivedAt));
        return lastInsertId();
    }

    private long step(long journeyId, int sequence, String status, Instant startedAt,
                      Instant dueAt, Instant finishedAt, Long ownerId) {
        jdbc.update("""
                INSERT INTO ob_journey_steps (journey_id, sequence, name, status,
                                              owner_user_id, started_at, due_at, finished_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, journeyId, sequence, "Step " + sequence, status, ownerId,
                ts(startedAt), ts(dueAt), ts(finishedAt));
        return lastInsertId();
    }

    /** {@code ck_ob_journey_steps_blocked_reason} refuses a block with no reason. */
    private long blockedStep(long journeyId, int sequence, Instant startedAt, Instant dueAt, Long ownerId) {
        jdbc.update("""
                INSERT INTO ob_journey_steps (journey_id, sequence, name, status, blocked_reason_code,
                                              owner_user_id, started_at, due_at)
                VALUES (?, ?, ?, 'BLOCKED', 'INFRA', ?, ?, ?)
                """, journeyId, sequence, "Step " + sequence, ownerId, ts(startedAt), ts(dueAt));
        return lastInsertId();
    }

    private void escalation(long clientId, long journeyId, long stepId) {
        Long contactId = contact(clientId);
        jdbc.update("""
                INSERT INTO ob_client_escalations
                    (ob_client_id, journey_id, step_id, raised_by_contact_id, comment)
                VALUES (?, ?, ?, ?, 'This is taking too long.')
                """, clientId, journeyId, stepId, contactId);
    }

    private Long contact(long clientId) {
        jdbc.update("""
                INSERT INTO ob_client_contacts (ob_client_id, name, email, is_primary)
                VALUES (?, ?, ?, 1)
                """, clientId, "Contact " + SEQ.incrementAndGet(), "c" + SEQ.get() + "@example.test");
        return lastInsertId();
    }

    /**
     * One {@code ob_step_history} status transition. The hash chain is left NULL,
     * exactly as {@code OnboardingFixture} does and for its reason: the
     * onboarding chain payload has not been defined, and inventing one here would
     * write rows that fail A-123's verifier.
     */
    private void history(long journeyId, long stepId, long clientId, String status, Instant at) {
        jdbc.update("""
                INSERT INTO ob_step_history (journey_id, step_id, ob_client_id, event_type,
                                             field_name, new_value, actor_type, created_at)
                VALUES (?, ?, ?, ?, 'status', ?, 'SYSTEM', ?)
                """, journeyId, stepId, clientId, status, status, ts(at));
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private long lastInsertId() {
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? 0L : id;
    }

    /** Guards the assumption every boundary above rests on. */
    @Test
    @DisplayName("the seeded calendar is the Asia/Kolkata one these boundaries assume")
    void theFixtureAssumesTheSeededCalendar() {
        String zone = jdbc.queryForObject("SELECT timezone FROM working_calendar WHERE id = 1", String.class);
        assertThat(ZoneId.of(zone)).isEqualTo(ZoneId.of("Asia/Kolkata"));
        assertThat(LocalDate.ofInstant(NOW, ZoneId.of(zone))).isEqualTo(TODAY);
    }
}
