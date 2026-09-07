package com.edunext.edutrack.api.feature.onboarding.reports;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.api.security.module.ModuleAccessGuard;
import com.edunext.edutrack.api.security.scope.OnboardingScopeResolver;
import com.edunext.edutrack.domain.onboarding.ObJourney;
import com.edunext.edutrack.domain.onboarding.ObJourneyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-122 · OB-10 against real MySQL.
 *
 * <p>What is worth a container here is everything a mock cannot answer: whether
 * the SQL scope predicate selects the same journeys as A-112's specification,
 * and whether six aggregate statements return the rows their runners claim.
 * The arithmetic above them is cheaper in {@link ObReportRunnersTest} and the
 * dispatch in {@link ObReportServiceTest}, which need no database.
 *
 * <p>Fixtures use usernames and names no seed migration will claim, for the
 * reason {@code AuthLoginIT} records.
 */
@SpringBootTest
@Testcontainers
class ObReportsIT {

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
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
        registry.add("spring.flyway.user", MYSQL::getUsername);
        registry.add("spring.flyway.password", MYSQL::getPassword);
    }

    @Autowired
    ObReportRepository reports;

    @Autowired
    ObJourneyRepository journeys;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    JdbcTemplate jdbc;

    /** Every window in this test comfortably contains the fixtures. */
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2026, 12, 31);
    private static final Instant NOW = Instant.parse("2026-09-06T12:00:00Z");

    private long ravi;
    private long meera;
    private long sunita;
    private long erp;
    private long biometric;
    private long horizon;
    private long crestwood;
    private long horizonErpJourney;
    private long crestwoodErpJourney;

    @BeforeEach
    void seed() {
        // Children first, then the two roots. ob_client_applications cascades
        // from ob_clients; nothing else does.
        jdbc.update("DELETE FROM ob_signoffs");
        // TRUNCATE rather than DELETE, and it is not a style choice.
        // ob_step_clock_events is append-only: A-107's trg_ob_clock_no_delete
        // refuses every row deletion, so a DELETE here fails — and the events
        // hold a foreign key to ob_journey_steps, so the steps cannot be
        // cleared while they are there either. TRUNCATE is a DDL-level reset
        // that does not fire row triggers, which is exactly the seam a test
        // harness should use and exactly the one application code must not:
        // the guarantee is that no service can delete a row, and this is not a
        // service. Ordered before the steps for the foreign key's sake.
        jdbc.execute("TRUNCATE TABLE ob_step_clock_events");
        jdbc.update("DELETE FROM ob_journey_steps");
        jdbc.update("DELETE FROM ob_journeys");
        jdbc.update("DELETE FROM ob_journey_templates WHERE name LIKE 'it_obrep_%'");
        jdbc.update("DELETE FROM ob_client_contacts");
        jdbc.update("DELETE FROM ob_clients WHERE name LIKE 'it_obrep_%'");
        jdbc.update("DELETE FROM ob_products WHERE code LIKE 'IT_OBREP_%'");
        jdbc.update("DELETE FROM users WHERE username LIKE 'it_obrep_%'");

        ravi = insertUser("it_obrep_ravi");
        meera = insertUser("it_obrep_meera");
        sunita = insertUser("it_obrep_sunita");

        erp = insertProduct("IT_OBREP_ERP", "ERP");
        biometric = insertProduct("IT_OBREP_BIO", "Biometric");
        long erpTemplate = insertTemplate(erp, 1);
        long bioTemplate = insertTemplate(biometric, 1);

        // Horizon was created by Ravi and is Ravi's client; Crestwood was
        // created by Meera. That split is what every scope assertion below
        // turns on.
        horizon = insertClient("it_obrep_horizon", ravi, ravi, "ONBOARDING",
                LocalDate.of(2026, 3, 1));
        crestwood = insertClient("it_obrep_crestwood", meera, meera, "LIVE",
                LocalDate.of(2026, 4, 1));

        application(horizon, erp);
        application(crestwood, erp);
        application(crestwood, biometric);

        horizonErpJourney = insertJourney(horizon, erp, erpTemplate, "OPEN",
                Instant.parse("2026-03-02T09:00:00Z"), null);
        crestwoodErpJourney = insertJourney(crestwood, erp, erpTemplate, "OPEN",
                Instant.parse("2026-04-02T09:00:00Z"), Instant.parse("2026-08-20T09:00:00Z"));
        insertJourney(crestwood, biometric, bioTemplate, "LOCKED", null, null);
    }

    // ── the duplicated scope rule ───────────────────────────────────────────

    /**
     * <b>The test the whole {@link ObReportScope} class depends on.</b>
     *
     * <p>A-112's rule is a JPA specification and this package re-expresses it as
     * SQL, because none of these aggregates is a Criteria query over a single
     * root. Two expressions of one security rule is the arrangement that rots
     * silently: a change to {@code OnboardingScopeResolver} that is not made
     * here leaves reports reading rows the rest of the module refuses, and
     * nothing fails.
     *
     * <p>So both are run against the same rows, for every role, and asserted to
     * select the same journeys. If this test is deleted, {@link ObReportScope}
     * must be too.
     */
    @ParameterizedTest
    @ValueSource(strings = {"OB_ADMIN", "OB_MANAGER", "OB_VIEWER", "OB_SALES",
            "OB_STEP_OWNER", "TICKETING_MEMBER", ""})
    @DisplayName("the SQL predicate and A-112's specification select the same journeys")
    void theSqlAndTheSpecificationAgree(String role) {
        // Ravi owns one step on Horizon's ERP journey and backs up one on
        // Crestwood's, so the step-owner rule has both of its halves exercised.
        step(horizonErpJourney, 1, "Kick-off", "DONE", ravi, null);
        step(crestwoodErpJourney, 1, "Kick-off", "DONE", meera, ravi);

        CallerIdentity caller = caller(ravi, role);

        List<Long> viaSpecification = journeys
                .findAll(OnboardingScopeResolver.journeyScope(caller)).stream()
                .map(ObJourney::getId)
                .sorted()
                .toList();

        List<Long> viaSql = journeyIdsMatching(ObReportScope.of(caller));

        assertThat(viaSql)
                .as("role %s", role.isEmpty() ? "(none)" : role)
                .isEqualTo(viaSpecification);
    }

    /**
     * The step-owner rule is deliberately the wider reading of §3 — a backup
     * owner sees the journey, because a backup that cannot open it cannot cover
     * the step. Asserted on its own so the agreement test above cannot pass by
     * both sides being wrong the same way.
     */
    @Test
    void aBackupOwnerSeesTheJourneyTheyBackUp() {
        step(crestwoodErpJourney, 1, "Kick-off", "IN_PROGRESS", meera, ravi);

        assertThat(journeyIdsMatching(ObReportScope.of(caller(ravi, "OB_STEP_OWNER"))))
                .containsExactly(crestwoodErpJourney);
    }

    /** Both owner columns are nullable and {@code = ?} never matches NULL. */
    @Test
    void anUnownedStepGivesNobodyVisibility() {
        step(horizonErpJourney, 1, "Kick-off", "PENDING", null, null);

        assertThat(journeyIdsMatching(ObReportScope.of(caller(sunita, "OB_STEP_OWNER")))).isEmpty();
    }

    // ── funnel ──────────────────────────────────────────────────────────────

    /**
     * A journey sits at its first non-terminal step, so a DONE first step moves
     * it to the second rather than counting it twice.
     */
    @Test
    void theFunnelCountsEachJourneyAtExactlyOneStep() {
        step(horizonErpJourney, 1, "Kick-off", "DONE", ravi, null);
        step(horizonErpJourney, 2, "Data migration", "IN_PROGRESS", ravi, null);
        step(crestwoodErpJourney, 1, "Kick-off", "IN_PROGRESS", meera, null);
        step(crestwoodErpJourney, 2, "Data migration", "PENDING", meera, null);

        assertThat(reports.funnel(unrestricted(), FROM, TO, erp))
                .extracting(ObReportRepository.FunnelRow::service,
                        ObReportRepository.FunnelRow::journeys)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Kick-off", 1L),
                        org.assertj.core.groups.Tuple.tuple("Data migration", 1L));
    }

    /**
     * A journey with nothing left to do has left the funnel, whether or not
     * {@code completed_at} has been stamped. One definition rather than two
     * that can disagree.
     */
    @Test
    void aJourneyWithEveryStepTerminalDropsOutOfTheFunnel() {
        step(horizonErpJourney, 1, "Kick-off", "DONE", ravi, null);
        skippedStep(horizonErpJourney, 2, "Data migration", ravi);

        assertThat(reports.funnel(unrestricted(), FROM, TO, erp)).isEmpty();
    }

    /**
     * A locked journey is counted, at its first step, and says so — hiding it
     * would make the funnel disagree with the board's own locked card, and
     * counting it silently would inflate step one.
     */
    @Test
    void aLockedJourneyIsCountedAndFlagged() {
        long locked = journeyIdOf(crestwood, biometric);
        step(locked, 1, "Device audit", "PENDING", sunita, null);

        assertThat(reports.funnel(unrestricted(), FROM, TO, biometric))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.journeys()).isEqualTo(1L);
                    assertThat(row.locked()).isEqualTo(1L);
                });
    }

    @Test
    void theFunnelIsScopedLikeEverythingElse() {
        step(horizonErpJourney, 1, "Kick-off", "IN_PROGRESS", ravi, null);
        step(crestwoodErpJourney, 1, "Kick-off", "IN_PROGRESS", meera, null);

        // Meera created Crestwood and nothing else.
        assertThat(reports.funnel(scope(meera, "OB_SALES"), FROM, TO, erp))
                .singleElement()
                .satisfies(row -> assertThat(row.journeys()).isEqualTo(1L));
    }

    // ── TAT compliance ──────────────────────────────────────────────────────

    /**
     * The distinction the whole report rests on: a finished step with no
     * {@code due_at} is counted as completed and not as measured, so a
     * percentage is never taken over steps nothing could judge.
     */
    @Test
    @DisplayName("a step with no due date is completed but not measured")
    void completedAndMeasuredAreCountedSeparately() {
        stepFinished(horizonErpJourney, 1, "Kick-off", ravi,
                Instant.parse("2026-05-01T09:00:00Z"), Instant.parse("2026-05-02T09:00:00Z"));
        stepFinished(horizonErpJourney, 2, "Data migration", ravi,
                Instant.parse("2026-05-10T09:00:00Z"), null);

        List<ObReportRepository.TatRow> rows =
                reports.tatCompliance(unrestricted(), FROM, TO, erp, null);

        assertThat(rows).hasSize(2);
        assertThat(rows).filteredOn(row -> row.service().equals("Data migration"))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.completed()).isEqualTo(1L);
                    assertThat(row.measured()).isZero();
                    assertThat(row.onTime()).isZero();
                });
    }

    @Test
    void aStepFinishedAfterItsDeadlineIsMeasuredAndNotOnTime() {
        stepFinished(horizonErpJourney, 1, "Kick-off", ravi,
                Instant.parse("2026-05-05T09:00:00Z"), Instant.parse("2026-05-01T09:00:00Z"));

        assertThat(reports.tatCompliance(unrestricted(), FROM, TO, erp, null))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.measured()).isEqualTo(1L);
                    assertThat(row.onTime()).isZero();
                });
    }

    /** Finishing exactly on the deadline counts as on time. */
    @Test
    void theDeadlineInstantItselfIsOnTime() {
        Instant due = Instant.parse("2026-05-01T09:00:00Z");
        stepFinished(horizonErpJourney, 1, "Kick-off", ravi, due, due);

        assertThat(reports.tatCompliance(unrestricted(), FROM, TO, erp, null))
                .singleElement()
                .satisfies(row -> assertThat(row.onTime()).isEqualTo(1L));
    }

    @Test
    void theOwnerFilterNarrowsToOneImplementor() {
        stepFinished(horizonErpJourney, 1, "Kick-off", ravi,
                Instant.parse("2026-05-01T09:00:00Z"), Instant.parse("2026-05-02T09:00:00Z"));
        stepFinished(crestwoodErpJourney, 1, "Kick-off", meera,
                Instant.parse("2026-05-01T09:00:00Z"), Instant.parse("2026-05-02T09:00:00Z"));

        assertThat(reports.tatCompliance(unrestricted(), FROM, TO, null, ravi))
                .singleElement()
                .satisfies(row -> assertThat(row.owner()).isEqualTo("it_obrep_ravi"));
    }

    // ── stuck and aging ─────────────────────────────────────────────────────

    @Test
    void blockedWaitingAndOverdueStepsAllAppearAndNothingElseDoes() {
        step(horizonErpJourney, 1, "Kick-off", "BLOCKED", ravi, null, "AWAITING_DATA");
        step(horizonErpJourney, 2, "Data migration", "WAITING_ON_CLIENT", ravi, null, null);
        overdueStep(horizonErpJourney, 3, "Config", ravi, NOW.minus(2, ChronoUnit.DAYS));
        // In progress, comfortably inside its TAT: not stuck.
        overdueStep(horizonErpJourney, 4, "UAT", ravi, NOW.plus(5, ChronoUnit.DAYS));

        assertThat(reports.stuckAndAging(unrestricted(), FROM, TO, erp, NOW))
                .extracting(ObReportRepository.StuckRow::service)
                .containsExactlyInAnyOrder("Kick-off", "Data migration", "Config");
    }

    /**
     * The attribution comes from the latest clock event rather than from
     * {@code status}: a step can be IN_PROGRESS with a paused clock, and the
     * status says what the work is doing while the clock says whose time it is.
     */
    @Test
    void theClockAttributionIsReadFromTheLatestEvent() {
        long step = step(horizonErpJourney, 1, "Kick-off", "BLOCKED", ravi, null, "AWAITING_DATA");
        clockEvent(step, "STARTED", "INTERNAL", NOW.minus(5, ChronoUnit.DAYS));
        clockEvent(step, "PAUSED", "CLIENT", NOW.minus(2, ChronoUnit.DAYS));

        assertThat(reports.stuckAndAging(unrestricted(), FROM, TO, erp, NOW))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.clock()).isEqualTo("CLIENT");
                    assertThat(row.clockSince()).isEqualTo(NOW.minus(2, ChronoUnit.DAYS));
                });
    }

    @Test
    void aStepWithNoClockEventReportsNoAttribution() {
        step(horizonErpJourney, 1, "Kick-off", "BLOCKED", ravi, null, "AWAITING_DATA");

        assertThat(reports.stuckAndAging(unrestricted(), FROM, TO, erp, NOW))
                .singleElement()
                .satisfies(row -> assertThat(row.clock()).isNull());
    }

    // ── time to live ────────────────────────────────────────────────────────

    @Test
    void onlyCompletedJourneysAreMeasuredAndTheyCarryTheirOwnMonth() {
        assertThat(reports.completedJourneys(unrestricted(), FROM, TO, null))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.month()).isEqualTo("2026-08");
                    assertThat(row.product()).isEqualTo("ERP");
                    assertThat(row.raisedAt()).isEqualTo(Instant.parse("2026-04-02T09:00:00Z"));
                    assertThat(row.completedAt()).isEqualTo(Instant.parse("2026-08-20T09:00:00Z"));
                });
    }

    // ── sales pipeline ──────────────────────────────────────────────────────

    @Test
    void thePipelineCountsClientsByStatusPerSalesPerson() {
        assertThat(reports.salesPipeline(unrestricted(), FROM, TO))
                .hasSize(2)
                .anySatisfy(row -> {
                    assertThat(row.salesPerson()).isEqualTo("it_obrep_meera");
                    assertThat(row.boarded()).isEqualTo(1L);
                    assertThat(row.live()).isEqualTo(1L);
                    assertThat(row.onboarding()).isZero();
                });
    }

    /**
     * The one report scoped over clients rather than journeys. Meera created
     * Crestwood, so a Sales caller sees exactly that row — and the ERP journey
     * Ravi owns steps on does not widen it.
     */
    @Test
    void thePipelineIsScopedByWhoCreatedTheClient() {
        assertThat(reports.salesPipeline(scope(meera, "OB_SALES"), FROM, TO))
                .singleElement()
                .satisfies(row -> assertThat(row.salesPerson()).isEqualTo("it_obrep_meera"));
    }

    /**
     * A client with no journey is still a Sales caller's pipeline — the
     * deliberate widening {@link ObReportScope#clientPredicate} documents, and
     * the reason the client predicate is not the journey one projected.
     */
    @Test
    @DisplayName("a client captured with no journey yet is still in its creator's pipeline")
    void aClientWithNoJourneyStillCounts() {
        insertClient("it_obrep_fresh", sunita, sunita, "ONBOARDING", LocalDate.of(2026, 5, 1));

        assertThat(reports.salesPipeline(scope(sunita, "OB_SALES"), FROM, TO))
                .singleElement()
                .satisfies(row -> assertThat(row.boarded()).isEqualTo(1L));
    }

    // ── sign-offs pending ───────────────────────────────────────────────────

    @Test
    void onlyPendingSignoffsAreListedOldestFirst() {
        long contact = insertContact(horizon, "Anita Rao", "anita@example.com");
        insertSignoff(horizon, horizonErpJourney, contact, "PENDING",
                Instant.parse("2026-06-10T09:00:00Z"));
        insertSignoff(horizon, horizonErpJourney, contact, "PENDING",
                Instant.parse("2026-06-01T09:00:00Z"));
        insertSignoff(horizon, horizonErpJourney, contact, "SIGNED",
                Instant.parse("2026-06-05T09:00:00Z"));

        assertThat(reports.pendingSignoffs(unrestricted(), FROM, TO, null))
                .extracting(ObReportRepository.PendingSignoff::requestedAt)
                .containsExactly(
                        Instant.parse("2026-06-01T09:00:00Z"),
                        Instant.parse("2026-06-10T09:00:00Z"));
    }

    /**
     * A go-live sign-off names no step, and the report has to say something
     * rather than leave the Service column blank.
     */
    @Test
    void aGoLiveSignoffWithNoStepIsLabelled() {
        long contact = insertContact(horizon, "Anita Rao", "anita@example.com");
        insertSignoff(horizon, horizonErpJourney, contact, "PENDING",
                Instant.parse("2026-06-01T09:00:00Z"));

        assertThat(reports.pendingSignoffs(unrestricted(), FROM, TO, null))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.service()).isEqualTo("Go-live");
                    assertThat(row.contact()).isEqualTo("Anita Rao");
                });
    }

    @Test
    void theClientFilterNarrowsToOneClient() {
        long contact = insertContact(horizon, "Anita Rao", "anita@example.com");
        insertSignoff(horizon, horizonErpJourney, contact, "PENDING",
                Instant.parse("2026-06-01T09:00:00Z"));

        assertThat(reports.pendingSignoffs(unrestricted(), FROM, TO, crestwood)).isEmpty();
        assertThat(reports.pendingSignoffs(unrestricted(), FROM, TO, horizon)).hasSize(1);
    }

    // ── every statement carries the guard ───────────────────────────────────

    /**
     * A deny-all caller gets nothing from every report, which is the property
     * worth asserting over all six at once: a statement that forgot the
     * predicate would return rows here and pass every other test in this file.
     */
    @Test
    @DisplayName("a caller with no onboarding standing reads nothing from any report")
    void everyReportIsEmptyForACallerWithNoRole() {
        step(horizonErpJourney, 1, "Kick-off", "BLOCKED", ravi, null, "AWAITING_DATA");
        long contact = insertContact(horizon, "Anita Rao", "anita@example.com");
        insertSignoff(horizon, horizonErpJourney, contact, "PENDING",
                Instant.parse("2026-06-01T09:00:00Z"));

        ObReportScope nobody = scope(ravi, "TICKETING_MEMBER");

        assertThat(reports.funnel(nobody, FROM, TO, null)).isEmpty();
        assertThat(reports.tatCompliance(nobody, FROM, TO, null, null)).isEmpty();
        assertThat(reports.stuckAndAging(nobody, FROM, TO, null, NOW)).isEmpty();
        assertThat(reports.completedJourneys(nobody, FROM, TO, null)).isEmpty();
        assertThat(reports.salesPipeline(nobody, FROM, TO)).isEmpty();
        assertThat(reports.pendingSignoffs(nobody, FROM, TO, null)).isEmpty();
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private List<Long> journeyIdsMatching(ObReportScope scope) {
        return jdbcClient
                .sql("SELECT j.id FROM ob_journeys j WHERE " + scope.journeyPredicate("j")
                        + " ORDER BY j.id")
                .param(ObReportScope.USER_PARAM, scope.userId())
                .query(Long.class)
                .list();
    }

    private static ObReportScope unrestricted() {
        return new ObReportScope(ObReportScope.OB_ADMIN, 0L);
    }

    private static ObReportScope scope(long userId, String role) {
        return ObReportScope.of(caller(userId, role));
    }

    private static CallerIdentity caller(long userId, String moduleRole) {
        return new CallerIdentity(userId, "SUPPORT", List.of(),
                List.of(ModuleAccessGuard.ONBOARDING),
                moduleRole.isEmpty()
                        ? Map.of()
                        : Map.of(ModuleAccessGuard.ONBOARDING, moduleRole));
    }

    private long insertUser(String username) {
        Long roleId = jdbc.queryForObject("SELECT id FROM roles ORDER BY id LIMIT 1", Long.class);
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id)
                VALUES (?, ?, ?, 'not-a-real-hash', ?, ?)
                """, username, username, username + "@example.com", username, roleId);
        return lastId();
    }

    private long insertProduct(String code, String name) {
        jdbc.update("INSERT INTO ob_products (code, name) VALUES (?, ?)", code, name);
        return lastId();
    }

    private long insertTemplate(long productId, int version) {
        jdbc.update("""
                INSERT INTO ob_journey_templates (product_id, name, version, is_active)
                VALUES (?, ?, ?, 1)
                """, productId, "it_obrep_template_" + productId, version);
        return lastId();
    }

    private long insertClient(String name, Long createdBy, Long salesPersonId,
                              String status, LocalDate onboardingDate) {
        jdbc.update("""
                INSERT INTO ob_clients (name, onboarding_date, sales_person_id, overall_status,
                                        created_by, live_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, name, onboardingDate, salesPersonId, status, createdBy,
                "LIVE".equals(status) ? Timestamp.from(Instant.parse("2026-08-20T09:00:00Z")) : null);
        return lastId();
    }

    private void application(long clientId, long productId) {
        jdbc.update("INSERT INTO ob_client_applications (ob_client_id, product_id) VALUES (?, ?)",
                clientId, productId);
    }

    private long insertJourney(long clientId, long productId, long templateId, String gate,
                               Instant startedAt, Instant completedAt) {
        jdbc.update("""
                INSERT INTO ob_journeys (ob_client_id, product_id, template_id, gate_status,
                                         gate_opened_at, started_at, completed_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, clientId, productId, templateId, gate,
                "OPEN".equals(gate) ? Timestamp.from(Instant.parse("2026-03-01T09:00:00Z")) : null,
                startedAt == null ? null : Timestamp.from(startedAt),
                completedAt == null ? null : Timestamp.from(completedAt),
                Timestamp.from(startedAt == null
                        ? Instant.parse("2026-03-01T09:00:00Z") : startedAt));
        return lastId();
    }

    private long journeyIdOf(long clientId, long productId) {
        return jdbc.queryForObject(
                "SELECT id FROM ob_journeys WHERE ob_client_id = ? AND product_id = ?",
                Long.class, clientId, productId);
    }

    private long step(long journeyId, int sequence, String name, String status,
                      Long owner, Long backup) {
        return step(journeyId, sequence, name, status, owner, backup, null);
    }

    private long step(long journeyId, int sequence, String name, String status,
                      Long owner, Long backup, String blockedReason) {
        jdbc.update("""
                INSERT INTO ob_journey_steps (journey_id, sequence, name, tat_days, status,
                                              owner_user_id, backup_owner_user_id,
                                              blocked_reason_code)
                VALUES (?, ?, ?, 3, ?, ?, ?, ?)
                """, journeyId, sequence, name, status, owner, backup, blockedReason);
        return lastId();
    }

    /**
     * {@code ck_ob_journey_steps_skip_reason} makes the reason mandatory on a
     * SKIPPED step, so it gets its own helper rather than a nullable argument
     * on the general one — the CHECK is plan §5.7's rule that a skip is always
     * accounted for, and a fixture that could bypass it would be testing a
     * state the application cannot produce.
     */
    private void skippedStep(long journeyId, int sequence, String name, Long owner) {
        jdbc.update("""
                INSERT INTO ob_journey_steps (journey_id, sequence, name, tat_days, status,
                                              owner_user_id, skip_reason, skipped_by)
                VALUES (?, ?, ?, 3, 'SKIPPED', ?, 'Not applicable for this client', ?)
                """, journeyId, sequence, name, owner, owner);
    }

    private void stepFinished(long journeyId, int sequence, String name, Long owner,
                              Instant finishedAt, Instant dueAt) {
        jdbc.update("""
                INSERT INTO ob_journey_steps (journey_id, sequence, name, tat_days, status,
                                              owner_user_id, started_at, finished_at, due_at)
                VALUES (?, ?, ?, 3, 'DONE', ?, ?, ?, ?)
                """, journeyId, sequence, name, owner,
                Timestamp.from(finishedAt.minus(3, ChronoUnit.DAYS)),
                Timestamp.from(finishedAt),
                dueAt == null ? null : Timestamp.from(dueAt));
    }

    private void overdueStep(long journeyId, int sequence, String name, Long owner, Instant dueAt) {
        jdbc.update("""
                INSERT INTO ob_journey_steps (journey_id, sequence, name, tat_days, status,
                                              owner_user_id, started_at, due_at)
                VALUES (?, ?, ?, 3, 'IN_PROGRESS', ?, ?, ?)
                """, journeyId, sequence, name, owner,
                Timestamp.from(dueAt.minus(3, ChronoUnit.DAYS)), Timestamp.from(dueAt));
    }

    private void clockEvent(long stepId, String type, String attributedTo, Instant occurredAt) {
        Long journeyId = jdbc.queryForObject(
                "SELECT journey_id FROM ob_journey_steps WHERE id = ?", Long.class, stepId);
        jdbc.update("""
                INSERT INTO ob_step_clock_events (step_id, journey_id, event_type, pause_reason,
                                                  attributed_to, occurred_at, actor_type)
                VALUES (?, ?, ?, ?, ?, ?, 'SYSTEM')
                """, stepId, journeyId, type,
                "PAUSED".equals(type) ? "WAITING_ON_CLIENT" : null,
                attributedTo, Timestamp.from(occurredAt));
    }

    private long insertContact(long clientId, String name, String email) {
        jdbc.update("""
                INSERT INTO ob_client_contacts (ob_client_id, name, email, is_primary)
                VALUES (?, ?, ?, 0)
                """, clientId, name, email);
        return lastId();
    }

    private void insertSignoff(long clientId, long journeyId, long contactId,
                               String status, Instant requestedAt) {
        jdbc.update("""
                INSERT INTO ob_signoffs (ob_client_id, journey_id, step_id, kind, status,
                                         token_hash, token_expires_at, requested_at,
                                         sent_to_contact_id)
                VALUES (?, ?, NULL, 'GO_LIVE', ?, ?, ?, ?, ?)
                """, clientId, journeyId, status,
                String.format("%064d", requestedAt.toEpochMilli() % 1_000_000_000L),
                Timestamp.from(requestedAt.plus(7, ChronoUnit.DAYS)),
                Timestamp.from(requestedAt), contactId);
    }

    private long lastId() {
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
