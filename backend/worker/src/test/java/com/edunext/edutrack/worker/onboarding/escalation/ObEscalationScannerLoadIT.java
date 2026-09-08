package com.edunext.edutrack.worker.onboarding.escalation;

import com.edunext.edutrack.domain.onboarding.ObEscalationLevel;
import com.edunext.edutrack.worker.onboarding.load.ObLoadFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C-117 · the escalation matrix's load pass, over the same 500 journeys and
 * 4,000 open steps {@link ObLoadFixture} builds for {@code
 * ObTatScannerLoadIT}.
 *
 * <h2>Why this scanner needs its own load pass rather than sharing that one</h2>
 *
 * <p>It is the heavier of the two by construction, and by more than the cap
 * suggests. {@code ObTatScanner} caps a pass at 500 candidates; {@code
 * ObEscalationScanner} caps <em>each of three levels</em> at 500, so one pass
 * is up to 1,500 rungs — and it does so on a five-minute cadence rather than a
 * fifteen-minute one, with a {@code lockAtMostFor} of four minutes rather than
 * fourteen. Three times the work in a third of the time is where a scanner
 * stops keeping up first, so it is where the load pass has to look.
 *
 * <p>It is also the one that consults the working calendar per candidate:
 * L2 and L3 ask {@code WorkingHoursService} how much working time has elapsed
 * since the breach, once in the scanner's own {@code due()} gate and again
 * inside the raise. The last test here measures that cost on its own, because
 * if a pass ever does overrun its lock, the first question will be whether the
 * calendar or the writes are responsible, and answering it from a single
 * end-to-end figure is impossible.
 *
 * <h2>These tests run in order, and share the state they build</h2>
 *
 * <p>Unusual, and deliberate. Draining this ladder is 2,375 rung-raises —
 * each an insert, a chained history row and two outbox rows — and resetting
 * between tests would mean paying for the same 2,375 twice to learn nothing
 * new. So the class is one pipeline: the population is measured before
 * anything is raised, then one pass is timed, then the remaining passes drain
 * what is left, then the gate that held 125 steps at L1 is checked. Each test
 * states the state it expects to inherit.
 */
@Testcontainers
@SpringBootTest(classes = com.edunext.edutrack.worker.WorkerApplication.class)
@Import(ObEscalationScannerLoadIT.FixedClock.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ObEscalationScannerLoadIT {

    private static final Logger log = LoggerFactory.getLogger(ObEscalationScannerLoadIT.class);

    private static final Instant NOW = Instant.parse("2026-08-10T10:00:00Z");

    /**
     * Old enough that 4 and 8 working hours have both passed under any
     * plausible calendar, not merely under the one seeded today.
     *
     * <p>A week rather than the three days that would do. Under the seeded
     * calendar (V20260808_1630: Mon–Fri, 09:30–18:30 Asia/Kolkata) a
     * three-day-old breach is nine working hours — one hour past L3's
     * threshold of eight, and that hour is the whole margin. Shorten the
     * working day by one hour in a future migration and L3 silently stops
     * firing here, which this class would report as a wrong rung count
     * rather than as what it is. A week is five working days whichever way
     * the calendar moves.
     */
    private static final Instant BREACHED_LONG_AGO = NOW.minusSeconds(7L * 86_400);

    /** Short of 4 working hours under any of them. */
    private static final Instant BREACHED_JUST_NOW = NOW.minusSeconds(600);

    /** {@code edutrack.onboarding.escalation.scan-interval}'s own default. */
    private static final Duration CADENCE = Duration.ofMinutes(5);

    /** {@code @SchedulerLock(lockAtMostFor)} — the real ceiling on one pass. */
    private static final Duration LOCK_AT_MOST_FOR = Duration.ofMinutes(4);

    /** One pass: three levels, 500 rungs each, every one of them a write. */
    private static final Duration ONE_PASS_BUDGET = Duration.ofMinutes(2);

    /** The whole ladder, however many passes that takes. */
    private static final Duration DRAIN_BUDGET = CADENCE;

    /**
     * 875 breached steps, of which 750 are old enough for every rung and 125
     * are not old enough for L2 or L3.
     */
    private static final int OLD_BREACHES = ObLoadFixture.OVERDUE_STEPS - ObLoadFixture.RECENTLY_BREACHED_STEPS;
    private static final int EXPECTED_L1 = ObLoadFixture.OVERDUE_STEPS;   // 875 — L1 fires at the breach itself
    private static final int EXPECTED_L2 = OLD_BREACHES;                  // 750
    private static final int EXPECTED_L3 = OLD_BREACHES;                  // 750
    private static final int EXPECTED_RUNGS = EXPECTED_L1 + EXPECTED_L2 + EXPECTED_L3;

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_ob_escalation_load_it")
            .withCommand(
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci",
                    "--default-time-zone=+00:00",
                    "--log-bin-trust-function-creators=1")
            .withUrlParam("allowPublicKeyRetrieval", "true")
            .withUrlParam("useSSL", "false")
            .withUrlParam("connectionTimeZone", "UTC")
            // Seeding is 5,000 rows through JdbcTemplate.batchUpdate, and
            // without this the driver sends them one statement at a time —
            // 70 seconds of setup for a test whose subject is the 25 seconds
            // that follow it. Test datasource only; nothing in production
            // reads this file.
            .withUrlParam("rewriteBatchedStatements", "true");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        // Every scheduled sweep in this process is disabled: a scanner firing
        // on its own alongside a measured pass would make the measurement
        // meaningless as well as flaky. SlaScannerIT's own note.
        registry.add("edutrack.onboarding.escalation.initial-delay", () -> "PT24H");
        registry.add("edutrack.onboarding.tat.initial-delay", () -> "PT24H");
        registry.add("edutrack.sla.initial-delay", () -> "PT24H");
        registry.add("edutrack.ob-stats.enabled", () -> "false");
        registry.add("edutrack.stats.enabled", () -> "false");
        registry.add("edutrack.outbox.enabled", () -> "false");
        registry.add("edutrack.ob-outbox.enabled", () -> "false");
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
    @Autowired ObEscalationScanner scanner;
    @Autowired ObEscalationLadderRepository escalations;
    @Autowired com.edunext.edutrack.domain.masters.WorkingHoursService workingHours;

    /**
     * The ladder starts where the TAT scanner leaves off, so the flags are set
     * directly rather than by running that scanner — its cost is measured in
     * its own IT and has no business inside these timings.
     *
     * <p>A flag rather than {@code @BeforeAll}, because the seed needs an
     * injected {@code JdbcTemplate} and {@code @TestInstance(PER_CLASS)} —
     * which is what would let {@code @BeforeAll} have one — makes JUnit build
     * the test instance <em>before</em> the {@code @Container} is started, and
     * Spring then asks a stopped container for its JDBC URL.
     */
    private static boolean seeded;

    @BeforeEach
    void seedTheLoadOnce() {
        if (seeded) {
            return;
        }
        long start = System.nanoTime();
        ObLoadFixture.seed(jdbc, NOW);
        int breached = ObLoadFixture.markOverdueAsBreached(jdbc, NOW, BREACHED_LONG_AGO);
        int recent = ObLoadFixture.markRecentlyBreached(jdbc, BREACHED_JUST_NOW);
        seeded = true;

        assertThat(breached).isEqualTo(ObLoadFixture.OVERDUE_STEPS);
        assertThat(recent).isEqualTo(ObLoadFixture.RECENTLY_BREACHED_STEPS);
        log.info("ob-escalation load: seeded {} journeys / {} open steps / {} breached ({} of them fresh) in {}",
                ObLoadFixture.ACTIVE_JOURNEYS, ObLoadFixture.OPEN_STEPS, breached, recent, elapsed(start));
    }

    // ------------------------------------------------------- the population

    @Test
    @Order(1)
    @DisplayName("every breached step is an L1 candidate — and nothing that is not running is")
    void theLadderSeesOnlyTheRunningBreachedSteps() {
        int candidates = escalations.candidates(ObEscalationLevel.L1, 100_000).size();

        assertThat(candidates)
                .as("%d overdue steps on archived, completed, gate-locked or parked journeys "
                                + "look identical on the step row and must not climb",
                        ObLoadFixture.EXCLUDED_OVERDUE_STEPS)
                .isEqualTo(EXPECTED_L1);
    }

    /**
     * What the plan actually looks like today, recorded because the next
     * person to read it should not have to re-derive it: MySQL drives from
     * {@code ob_journeys} — a full scan of it, with a temporary table and a
     * filesort for the {@code ORDER BY tat_breached_at} — then joins steps by
     * {@code journey_id} and checks the {@code NOT EXISTS} against a
     * materialised, index-served read of {@code ob_escalations}.
     *
     * <p>That journey scan is 500 rows here and is not asserted against: it
     * grows with active journeys rather than with steps or escalations, so it
     * is linear in the smallest of the three tables and cheap for as long as
     * an onboarding business has hundreds of live journeys rather than
     * hundreds of thousands. What is asserted is the pair that would go
     * quadratic — a scan of the steps table per journey, or a scan of the
     * ladder per step.
     */
    @Test
    @Order(2)
    @DisplayName("the candidate query is served by an index, not by a scan of every step")
    void theCandidateQueryIsIndexServed() {
        List<Map<String, Object>> plan = explainCandidates();
        Map<String, Object> stepsRow = plan.stream()
                .filter(row -> "s".equals(String.valueOf(row.get("table"))))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no plan row for ob_journey_steps: " + plan));

        log.info("ob-escalation load: candidate plan = {}", plan);

        assertThat(String.valueOf(stepsRow.get("type")))
                .as("a full scan of ob_journey_steps is cheap at 4,000 rows and ruinous at 400,000; "
                        + "the whole plan was %s", plan)
                .isNotEqualTo("ALL");

        Map<String, Object> escalationsRow = plan.stream()
                .filter(row -> "e".equals(String.valueOf(row.get("table"))))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no plan row for the NOT EXISTS subquery: " + plan));
        assertThat(String.valueOf(escalationsRow.get("type")))
                .as("the NOT EXISTS is checked once per surviving step; a scan of ob_escalations "
                        + "there is the one that goes quadratic. Plan: %s", plan)
                .isNotEqualTo("ALL");
    }

    // ------------------------------------------------------------ one sweep

    @Test
    @Order(3)
    @DisplayName("one pass raises 3 × 500 rungs, inside half the scanner's own lock")
    void oneFullPassFinishesFarInsideItsCadence() {
        long start = System.nanoTime();
        int raised = scanner.scanOnce();
        Duration took = elapsed(start);

        log.info("ob-escalation load: one pass raised {} rung(s) in {} — {}% of the {} lock",
                raised, took, percentOf(took, LOCK_AT_MOST_FOR), LOCK_AT_MOST_FOR);

        assertThat(raised)
                .as("MAX_PER_PASS bounds each level separately, and every level's backlog exceeds it")
                .isEqualTo(3 * ObLoadFixture.CAP_PER_PASS);
        assertThat(took)
                .as("one pass must finish well inside lockAtMostFor=%s, or a second replica "
                        + "starts sweeping the same ladder", LOCK_AT_MOST_FOR)
                .isLessThan(ONE_PASS_BUDGET);
    }

    // ------------------------------------------------------------- draining

    @Test
    @Order(4)
    @DisplayName("the rest of the ladder drains inside one cadence, and then stops")
    void theRemainderDrainsWithinOneCadence() {
        long start = System.nanoTime();
        int raised = 0;
        int passes = 0;
        int lastPass;
        do {
            lastPass = scanner.scanOnce();
            raised += lastPass;
            passes++;
        } while (lastPass > 0 && passes < 10);
        Duration took = elapsed(start);

        log.info("ob-escalation load: {} further rung(s) over {} pass(es) in {} ({}% of the {} cadence)",
                raised, passes, took, percentOf(took, CADENCE), CADENCE);

        assertThat(rungs(null))
                .as("every due rung raised exactly once, across this pass and the one before it")
                .isEqualTo(EXPECTED_RUNGS);
        assertThat(took)
                .as("what one pass leaves behind must clear inside a single %s cadence", CADENCE)
                .isLessThan(DRAIN_BUDGET);
        assertThat(lastPass)
                .as("the ladder terminates: a pass with nothing due raises nothing")
                .isZero();
    }

    @Test
    @Order(5)
    @DisplayName("the working-hours gate still holds at load: 125 fresh breaches stop at L1")
    void theFreshlyBreachedCohortClimbsNoFurtherThanL1() {
        assertThat(rungs(ObEscalationLevel.L1)).isEqualTo(EXPECTED_L1);
        assertThat(rungs(ObEscalationLevel.L2)).isEqualTo(EXPECTED_L2);
        assertThat(rungs(ObEscalationLevel.L3)).isEqualTo(EXPECTED_L3);

        Integer stalledAtL1 = jdbc.queryForObject("""
                SELECT COUNT(*) FROM ob_journey_steps s
                 WHERE s.tat_breached_at = ?
                   AND EXISTS (SELECT 1 FROM ob_escalations e WHERE e.step_id = s.id AND e.level = 'L1')
                   AND NOT EXISTS (SELECT 1 FROM ob_escalations e WHERE e.step_id = s.id AND e.level = 'L2')
                """, Integer.class, java.sql.Timestamp.from(BREACHED_JUST_NOW));

        assertThat(stalledAtL1)
                .as("L1 fires at the breach; L2 waits 4 working hours and these have had ten minutes")
                .isEqualTo(ObLoadFixture.RECENTLY_BREACHED_STEPS);
    }

    // -------------------------------------------------- the calendar's cost

    @Test
    @Order(6)
    @DisplayName("the per-candidate working-hours lookup is not what would blow the budget")
    void theWorkingCalendarLookupIsCheapEnoughPerCandidate() {
        int calls = 1_000;
        long start = System.nanoTime();
        for (int i = 0; i < calls; i++) {
            workingHours.workingHoursBetween(BREACHED_LONG_AGO, NOW);
        }
        Duration took = elapsed(start);

        log.info("ob-escalation load: {} working-hours lookups in {} ({} µs each)",
                calls, took, took.toNanos() / calls / 1_000);

        // Two levels × up to 500 candidates is 1,000 gate checks per pass,
        // before the raises make their own calls. This is a share of the pass
        // budget, not a budget of its own.
        assertThat(took)
                .as("the ladder makes about this many calendar reads in a single pass")
                .isLessThan(ONE_PASS_BUDGET.dividedBy(2));
    }

    // -------------------------------------------------------------- helpers

    /**
     * The scanner's own SQL, read off {@link ObEscalationLadderRepository}
     * rather than copied here — a copy would drift and end up proving that an
     * index serves a query nobody runs.
     */
    private List<Map<String, Object>> explainCandidates() {
        String sql;
        try {
            var field = ObEscalationLadderRepository.class.getDeclaredField("CANDIDATES");
            field.setAccessible(true);
            sql = (String) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("ObEscalationLadderRepository.CANDIDATES has moved or been renamed", e);
        }
        String bound = sql
                .replace(":level", "'L1'")
                .replace(":limit", String.valueOf(ObLoadFixture.CAP_PER_PASS));
        return jdbc.queryForList("EXPLAIN " + bound);
    }

    /** @param level {@code null} for every level at once */
    private int rungs(ObEscalationLevel level) {
        Integer count = level == null
                ? jdbc.queryForObject("SELECT COUNT(*) FROM ob_escalations", Integer.class)
                : jdbc.queryForObject("SELECT COUNT(*) FROM ob_escalations WHERE level = ?",
                        Integer.class, level.name());
        return count == null ? 0 : count;
    }

    private static Duration elapsed(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }

    private static long percentOf(Duration took, Duration budget) {
        return took.toMillis() * 100 / budget.toMillis();
    }
}
