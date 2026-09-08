package com.edunext.edutrack.worker.onboarding.tat;

import com.edunext.edutrack.worker.onboarding.load.ObLoadFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C-117 · the TAT scanner's load pass — 500 active journeys, 4,000 open steps
 * (see {@link ObLoadFixture}), and the question the backlog actually asks:
 * does one sweep still finish inside its cadence.
 *
 * <h2>What "inside its cadence" is measured against</h2>
 *
 * <p>{@code ObTatScanner} declares two numbers, and the tighter one is the
 * one that matters: {@code scan-interval} defaults to {@code PT15M}, but
 * {@code @SchedulerLock(lockAtMostFor = "PT14M")} is what stops a second
 * replica starting a pass while this one is still running. A pass that
 * overran the lock would not merely be late — two replicas would sweep the
 * same candidates at once, and the only thing standing between that and two
 * breach mails for one step is the guarded {@code UPDATE}. So the budget
 * here is stated against the lock, with an order of magnitude of headroom.
 *
 * <h2>Timings are asserted loosely and reported precisely</h2>
 *
 * <p>The numbers this class logs are the deliverable; the numbers it asserts
 * are a regression tripwire. A CI runner sharing a machine with three other
 * jobs can be several times slower than a developer's laptop, and an
 * assertion tuned to the laptop would fail for reasons that have nothing to
 * do with the scanner. The budgets below are therefore generous by design —
 * they catch "this went quadratic", not "this got 30% slower". Read the
 * logged figures for the real headroom.
 *
 * <h2>Correctness at scale, not just speed</h2>
 *
 * <p>Every timing test here also asserts an exact candidate count. Both of
 * this scanner's filters that a load fixture can plausibly break — the 500
 * paused {@code WAITING_ON_CLIENT} steps with a past {@code due_at}, and the
 * 48 overdue steps on archived, completed, gate-locked or parked journeys —
 * are present in numbers large enough that a regression shows up as a wrong
 * count rather than as a rounding error.
 */
@Testcontainers
@SpringBootTest(classes = com.edunext.edutrack.worker.WorkerApplication.class)
@Import(ObTatScannerLoadIT.FixedClock.class)
class ObTatScannerLoadIT {

    private static final Logger log = LoggerFactory.getLogger(ObTatScannerLoadIT.class);

    private static final Instant NOW = Instant.parse("2026-08-10T10:00:00Z");

    /** {@code edutrack.onboarding.tat.scan-interval}'s own default. */
    private static final Duration CADENCE = Duration.ofMinutes(15);

    /** {@code @SchedulerLock(lockAtMostFor)} — the real ceiling on one pass. */
    private static final Duration LOCK_AT_MOST_FOR = Duration.ofMinutes(14);

    /** One capped pass: 500 flags, each with a history row and four outbox rows. */
    private static final Duration ONE_PASS_BUDGET = Duration.ofMinutes(2);

    /** The whole 875-step backlog, however many passes that takes. */
    private static final Duration DRAIN_BUDGET = CADENCE;

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_ob_tat_load_it")
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
        // Every scanner and digest in this process is a fixedDelay that fires
        // at context startup — SlaScannerIT's own account of the deadlock this
        // avoids. Every test here drives scanOnce() directly, and a scheduled
        // pass running alongside a measured one would make the measurement
        // meaningless as well as flaky.
        registry.add("edutrack.onboarding.tat.initial-delay", () -> "PT24H");
        registry.add("edutrack.onboarding.escalation.initial-delay", () -> "PT24H");
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
    @Autowired ObTatScanner scanner;
    @Autowired ObTatRepository steps;

    /**
     * Seeded once for the class, then only unflagged between tests: building
     * the population is not what is being measured, and paying for it four
     * times would multiply the cost of the slowest IT in the module for no
     * extra assurance.
     *
     * <p>A flag rather than {@code @BeforeAll}, because the seed needs an
     * injected {@code JdbcTemplate} and {@code @TestInstance(PER_CLASS)} —
     * which is what would let {@code @BeforeAll} have one — makes JUnit build
     * the test instance <em>before</em> the {@code @Container} is started,
     * and Spring then asks a stopped container for its JDBC URL.
     */
    private static boolean seeded;

    @BeforeEach
    void seedOnceThenUnflag() {
        if (!seeded) {
            long start = System.nanoTime();
            ObLoadFixture.seed(jdbc, NOW);
            seeded = true;
            log.info("ob-tat load: seeded {} journeys / {} open steps in {}",
                    ObLoadFixture.ACTIVE_JOURNEYS, ObLoadFixture.OPEN_STEPS, elapsed(start));
        }
        // Every test starts from an unflagged backlog, so none depends on order.
        ObLoadFixture.clearBreachFlags(jdbc);
    }

    // ------------------------------------------------------- the population

    @Test
    @DisplayName("the fixture really is 500 active journeys and 4,000 open steps")
    void theFixtureIsTheLoadTheBacklogNames() {
        // The client's own status is part of "active" — a journey whose client
        // is parked passes every filter on the journey row and is still not
        // one the scanner should be sweeping.
        assertThat(count("SELECT COUNT(*) FROM ob_journeys j JOIN ob_clients c ON c.id = j.ob_client_id "
                + "WHERE j.archived_at IS NULL AND j.completed_at IS NULL "
                + "AND j.gate_status = 'OPEN' AND c.overall_status = 'ONBOARDING'"))
                .isEqualTo(ObLoadFixture.ACTIVE_JOURNEYS);

        assertThat(count("SELECT COUNT(*) FROM ob_journey_steps s JOIN ob_journeys j ON j.id = s.journey_id "
                + "JOIN ob_clients c ON c.id = j.ob_client_id "
                + "WHERE s.status NOT IN ('DONE', 'SKIPPED') AND j.archived_at IS NULL "
                + "AND j.completed_at IS NULL AND j.gate_status = 'OPEN' AND c.overall_status = 'ONBOARDING'"))
                .isEqualTo(ObLoadFixture.OPEN_STEPS);

        assertThat(count("SELECT COUNT(*) FROM ob_journey_steps "
                + "WHERE status = 'WAITING_ON_CLIENT' AND due_at < '2026-08-10 10:00:00'"))
                .as("paused clocks with a date already behind them — the trap for a careless filter")
                .isEqualTo(ObLoadFixture.PAUSED_PAST_DUE_STEPS);
    }

    @Test
    @DisplayName("875 of the 4,000 are candidates — the paused and the parked are not")
    void onlyTheRunningOverdueStepsAreCandidates() {
        // No limit worth the name: this asks what the sweep would see if the
        // per-pass cap were lifted, which is the number the cap is dividing.
        int candidates = steps.candidates(NOW, 100_000).size();

        assertThat(candidates)
                .as("500 paused + %d parked steps look overdue and must not be counted",
                        ObLoadFixture.EXCLUDED_OVERDUE_STEPS)
                .isEqualTo(ObLoadFixture.OVERDUE_STEPS);
    }

    // ------------------------------------------------------------- the plan

    @Test
    @DisplayName("the candidate query is served by an index, not by a scan of every step")
    void theCandidateQueryIsIndexServed() {
        List<Map<String, Object>> plan = explainCandidates();
        Map<String, Object> stepsRow = plan.stream()
                .filter(row -> "s".equals(String.valueOf(row.get("table"))))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no plan row for ob_journey_steps: " + plan));

        log.info("ob-tat load: candidate plan for ob_journey_steps = {}", stepsRow);

        assertThat(String.valueOf(stepsRow.get("type")))
                .as("a full scan of ob_journey_steps is cheap at 4,000 rows and ruinous at 400,000; "
                        + "the whole plan was %s", plan)
                .isNotEqualTo("ALL");
        assertThat(stepsRow.get("key"))
                .as("some index has to be doing the work; the whole plan was %s", plan)
                .isNotNull();
    }

    // ---------------------------------------------------------- one sweep

    @Test
    @DisplayName("one capped pass flags 500 steps far inside the scanner's own lock")
    void oneFullPassFinishesFarInsideItsCadence() {
        long start = System.nanoTime();
        int flagged = scanner.scanOnce();
        Duration took = elapsed(start);

        log.info("ob-tat load: one pass flagged {} step(s) in {} — {}% of the {} lock",
                flagged, took, percentOf(took, LOCK_AT_MOST_FOR), LOCK_AT_MOST_FOR);

        assertThat(flagged)
                .as("MAX_PER_PASS bounds the pass, and the backlog is bigger than it")
                .isEqualTo(ObLoadFixture.CAP_PER_PASS);
        assertThat(took)
                .as("one pass must finish well inside lockAtMostFor=%s, or two replicas overlap",
                        LOCK_AT_MOST_FOR)
                .isLessThan(ONE_PASS_BUDGET);
    }

    // ------------------------------------------------------------- draining

    @Test
    @DisplayName("the whole backlog drains in ceil(875/500) passes, inside one cadence, losing nothing")
    void theBacklogDrainsWithinOneCadence() {
        int expectedPasses = (ObLoadFixture.OVERDUE_STEPS + ObLoadFixture.CAP_PER_PASS - 1)
                / ObLoadFixture.CAP_PER_PASS;

        List<Duration> passes = new ArrayList<>();
        long start = System.nanoTime();
        int flagged = 0;
        for (int pass = 0; pass < expectedPasses; pass++) {
            long passStart = System.nanoTime();
            flagged += scanner.scanOnce();
            passes.add(elapsed(passStart));
        }
        Duration took = elapsed(start);

        log.info("ob-tat load: drained {} step(s) in {} pass(es), {} total ({}% of the {} cadence); per pass {}",
                flagged, expectedPasses, took, percentOf(took, CADENCE), CADENCE, passes);

        assertThat(flagged)
                .as("every overdue step is flagged exactly once across the passes")
                .isEqualTo(ObLoadFixture.OVERDUE_STEPS);
        assertThat(scanner.scanOnce())
                .as("and the pass after the last one has nothing left to find")
                .isZero();
        assertThat(took)
                .as("a full backlog must clear inside a single %s cadence, or it never catches up",
                        CADENCE)
                .isLessThan(DRAIN_BUDGET);

        assertThat(count("SELECT COUNT(*) FROM ob_journey_steps WHERE status = 'WAITING_ON_CLIENT' "
                + "AND tat_breached_at IS NOT NULL"))
                .as("§5.7: a paused clock is never flagged, however many passes run")
                .isZero();
        assertThat(count("SELECT COUNT(*) FROM ob_journey_steps s JOIN ob_journeys j ON j.id = s.journey_id "
                + "JOIN ob_clients c ON c.id = j.ob_client_id "
                + "WHERE s.tat_breached_at IS NOT NULL AND (j.archived_at IS NOT NULL "
                + "OR j.completed_at IS NOT NULL OR j.gate_status <> 'OPEN' "
                + "OR c.overall_status <> 'ONBOARDING')"))
                .as("nor is a step on a journey or client that is not running")
                .isZero();
    }

    // -------------------------------------------------------------- helpers

    /**
     * The scanner's own SQL, read off {@link ObTatRepository} rather than
     * copied into this file. A copy would go stale silently and would then be
     * proving that an index serves a query nobody runs — which is the one way
     * a plan assertion can be worse than no plan assertion at all.
     */
    private List<Map<String, Object>> explainCandidates() {
        String sql;
        try {
            var field = ObTatRepository.class.getDeclaredField("CANDIDATES");
            field.setAccessible(true);
            sql = (String) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("ObTatRepository.CANDIDATES has moved or been renamed", e);
        }
        String bound = sql
                .replace(":now", "'2026-08-10 10:00:00.000000'")
                .replace(":limit", String.valueOf(ObLoadFixture.CAP_PER_PASS));
        return jdbc.queryForList("EXPLAIN " + bound);
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    private static Duration elapsed(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }

    private static long percentOf(Duration took, Duration budget) {
        return took.toMillis() * 100 / budget.toMillis();
    }
}
