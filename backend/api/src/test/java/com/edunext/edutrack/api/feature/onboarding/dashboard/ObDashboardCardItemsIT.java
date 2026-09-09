package com.edunext.edutrack.api.feature.onboarding.dashboard;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.api.security.scope.OnboardingScopeResolver;
import com.edunext.edutrack.domain.masters.WorkingCalendarRepository;
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

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-127 · the S-06 slide-over's union query against real MySQL.
 *
 * <p>What is worth a container here: whether the two branches actually union
 * correctly, whether each card's predicate matches
 * {@code ObDashboardStatsRepository}'s own arithmetic, whether the keyset
 * cursor round-trips a page boundary without skipping or repeating a row, and
 * — the test this class exists for — whether {@link ObDashboardScope}'s SQL
 * predicate selects the same journeys as {@code OnboardingScopeResolver}'s
 * specification. The day/week boundary arithmetic is cheaper in
 * {@code ObDashboardCardItemsServiceTest}, which needs no database.
 *
 * <p>Fixtures use product and user names no seed migration will claim, for
 * the reason {@code AuthLoginIT} records.
 */
@SpringBootTest
@Testcontainers
class ObDashboardCardItemsIT {

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

    /** A Wednesday. Its week is 31 Aug (Mon) – 7 Sep (Mon). */
    private static final Instant NOW = Instant.parse("2026-09-02T10:00:00Z");
    private static final Instant TODAY_START = Instant.parse("2026-09-02T00:00:00Z");
    private static final Instant TODAY_DUE = Instant.parse("2026-09-02T15:00:00Z");
    /** Saturday of the same week — inside the week window, outside today's. */
    private static final Instant THIS_WEEK_DUE = Instant.parse("2026-09-05T09:00:00Z");
    /** Before the week even starts, so it can only ever match on being overdue. */
    private static final Instant OVERDUE_DUE = Instant.parse("2026-08-20T09:00:00Z");

    @Autowired
    ObDashboardCardItemsRepository repository;

    @Autowired
    ObDashboardSummaryRepository summaries;

    @Autowired
    WorkingCalendarRepository calendars;

    @Autowired
    ObJourneyRepository journeyRepository;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    JdbcTemplate jdbc;

    private long ravi;
    private long meera;
    private long sunita;
    private long erp;
    private long biometric;
    private long horizon;
    private long crestwood;
    private long meadow;
    private long horizonErpJourney;
    private long crestwoodErpJourney;
    private long erpTemplate;

    @BeforeEach
    void seed() {
        // UTC, so the fixed instants above are exactly the boundaries the
        // service computes — AuthLoginIT's reasoning on why fixtures pick their
        // own values rather than trust whatever a shared seed happens to hold.
        jdbc.update("UPDATE working_calendar SET timezone = 'UTC' WHERE id = 1");

        jdbc.update("DELETE FROM ob_client_escalations");
        jdbc.update("DELETE FROM ob_client_prereq_tasks");
        jdbc.update("DELETE FROM ob_client_prereqs");
        jdbc.update("DELETE FROM ob_prereq_template_versions");
        jdbc.update("DELETE FROM ob_journey_steps");
        jdbc.update("DELETE FROM ob_journeys");
        jdbc.update("DELETE FROM ob_journey_templates WHERE name LIKE 'it_obcards_%'");
        jdbc.update("DELETE FROM ob_client_contacts");
        jdbc.update("DELETE FROM ob_clients WHERE name LIKE 'it_obcards_%'");
        jdbc.update("DELETE FROM ob_products WHERE code LIKE 'IT_OBCARDS_%'");
        jdbc.update("DELETE FROM users WHERE username LIKE 'it_obcards_%'");

        ravi = insertUser("it_obcards_ravi");
        meera = insertUser("it_obcards_meera");
        sunita = insertUser("it_obcards_sunita");

        erp = insertProduct("IT_OBCARDS_ERP", "ERP");
        biometric = insertProduct("IT_OBCARDS_BIO", "Biometric");
        erpTemplate = insertTemplate(erp);
        long bioTemplate = insertTemplate(biometric);

        // Horizon: created by Ravi, ONBOARDING, one RUNNING ERP journey.
        horizon = insertClient("it_obcards_horizon", ravi, "ONBOARDING");
        // Crestwood: created by Meera, LIVE — the client the "live" card reads.
        crestwood = insertClient("it_obcards_crestwood", meera, "LIVE");
        // Meadow: created by Ravi, ONBOARDING, one LOCKED journey with an
        // outstanding prerequisite and no eligible service item at all.
        meadow = insertClient("it_obcards_meadow", ravi, "ONBOARDING");

        // fk_ob_journeys_application: a journey needs the purchase behind it.
        application(horizon, erp);
        application(crestwood, erp);
        application(meadow, biometric);
        application(meadow, erp);

        horizonErpJourney = insertJourney(horizon, erp, erpTemplate, "OPEN", null);
        crestwoodErpJourney = insertJourney(crestwood, erp, erpTemplate, "OPEN",
                Instant.parse("2026-08-25T09:00:00Z"));
        insertJourney(meadow, biometric, bioTemplate, "LOCKED", null);
    }

    private void application(long clientId, long productId) {
        jdbc.update("INSERT INTO ob_client_applications (ob_client_id, product_id) VALUES (?, ?)",
                clientId, productId);
    }

    // ── this-weeks-deadlines / todays-delivery: the union itself ────────────

    @Test
    @DisplayName("this week's deadlines mixes a service and a prerequisite due the same week")
    void thisWeeksDeadlinesUnionsBothKinds() {
        long serviceStep = step(horizonErpJourney, 1, "Kick-off", "IN_PROGRESS", meera, null,
                THIS_WEEK_DUE.minus(Duration.ofDays(1)), THIS_WEEK_DUE);
        long prereqTask = prereqTask(meadow, "PAN card", "PENDING", THIS_WEEK_DUE);
        // Out of the window entirely — must not appear.
        step(horizonErpJourney, 2, "Too early", "PENDING", meera, null, null, null);

        List<ObDashboardCardItemsRepository.ItemRow> rows = fetch(
                ObDashboardCardKey.THIS_WEEKS_DEADLINES, unrestricted(), null, null);

        assertThat(rows).extracting(ObDashboardCardItemsRepository.ItemRow::itemId)
                .containsExactlyInAnyOrder(serviceStep, prereqTask);
        assertThat(rows).extracting(ObDashboardCardItemsRepository.ItemRow::itemType)
                .containsExactlyInAnyOrder("SERVICE", "PREREQUISITE");
    }

    @Test
    @DisplayName("today's delivery excludes an item due later this week")
    void todaysDeliveryExcludesLaterThisWeek() {
        long today = step(horizonErpJourney, 1, "Due today", "IN_PROGRESS", meera, null,
                TODAY_DUE.minus(Duration.ofDays(1)), TODAY_DUE);
        step(horizonErpJourney, 2, "Due Saturday", "IN_PROGRESS", meera, null,
                THIS_WEEK_DUE.minus(Duration.ofDays(1)), THIS_WEEK_DUE);

        List<ObDashboardCardItemsRepository.ItemRow> rows = fetch(
                ObDashboardCardKey.TODAYS_DELIVERY, unrestricted(), null, null);

        assertThat(rows).extracting(ObDashboardCardItemsRepository.ItemRow::itemId)
                .containsExactly(today);
    }

    @Test
    @DisplayName("a settled item never appears, however close its due date")
    void aSettledItemIsExcluded() {
        doneStep(horizonErpJourney, 1, "Already done", meera, TODAY_DUE.minus(Duration.ofDays(4)),
                TODAY_DUE.minus(Duration.ofDays(1)), TODAY_DUE);

        assertThat(fetch(ObDashboardCardKey.TODAYS_DELIVERY, unrestricted(), null, null)).isEmpty();
    }

    // ── overdue-clients: services only ───────────────────────────────────────

    @Test
    @DisplayName("overdue-clients matches the exact predicate the count is built from, and excludes prerequisites")
    void overdueClientsExcludesPrerequisites() {
        long overdueService = step(horizonErpJourney, 1, "Late", "IN_PROGRESS", meera, null,
                OVERDUE_DUE.minus(Duration.ofDays(3)), OVERDUE_DUE);
        prereqTask(meadow, "Overdue paperwork", "PENDING", OVERDUE_DUE);
        // WAITING_ON_CLIENT is excluded even though it is past due — the clock is stopped.
        step(horizonErpJourney, 2, "Waiting on client", "WAITING_ON_CLIENT", meera, null,
                OVERDUE_DUE.minus(Duration.ofDays(3)), OVERDUE_DUE);

        List<ObDashboardCardItemsRepository.ItemRow> rows = fetch(
                ObDashboardCardKey.OVERDUE_CLIENTS, unrestricted(), null, null);

        assertThat(rows).extracting(ObDashboardCardItemsRepository.ItemRow::itemId)
                .containsExactly(overdueService);
        assertThat(rows).allMatch(ObDashboardCardItemsRepository.ItemRow::isOverdue);
    }

    // ── at-risk: only the RUNNING bucket ─────────────────────────────────────

    @Test
    @DisplayName("at-risk excludes a locked journey's steps entirely, even one shaped to look overdue")
    void atRiskExcludesLockedJourneys() {
        // Meadow's journey is LOCKED. Plan §5.2 says a locked journey's steps
        // never actually reach this shape — but the predicate is what has to
        // refuse it, not the fixture data being well-formed, so this forces one
        // in directly to prove jr.gate_status is checked and not merely assumed.
        long meadowJourney = jdbc.queryForObject(
                "SELECT id FROM ob_journeys WHERE ob_client_id = ?", Long.class, meadow);
        step(meadowJourney, 1, "Shaped like overdue, but locked", "IN_PROGRESS", meera, null,
                OVERDUE_DUE.minus(Duration.ofDays(3)), OVERDUE_DUE);

        assertThat(fetch(ObDashboardCardKey.AT_RISK, unrestricted(), null, null)).isEmpty();
    }

    @Test
    @DisplayName("at-risk includes an amber step on a running journey and an overdue one, nothing settled")
    void atRiskIncludesAmberAndOverdue() {
        long amber = step(horizonErpJourney, 1, "Amber", "IN_PROGRESS", meera, null,
                NOW.minus(Duration.ofDays(9)), NOW.plus(Duration.ofDays(1)));
        long overdue = step(horizonErpJourney, 2, "Red", "IN_PROGRESS", meera, null,
                OVERDUE_DUE.minus(Duration.ofDays(3)), OVERDUE_DUE);
        // Not due for weeks and not started — green, must not appear.
        step(horizonErpJourney, 3, "Green", "PENDING", meera, null, null, null);

        List<ObDashboardCardItemsRepository.ItemRow> rows =
                fetch(ObDashboardCardKey.AT_RISK, unrestricted(), null, null);

        assertThat(rows).extracting(ObDashboardCardItemsRepository.ItemRow::itemId)
                .containsExactlyInAnyOrder(amber, overdue);
    }

    // ── live: completed services of a LIVE client only ───────────────────────

    @Test
    @DisplayName("live reads the client's own status, not the journey's")
    void liveReadsClientStatus() {
        long shipped = doneStep(crestwoodErpJourney, 1, "Shipped", meera,
                Instant.parse("2026-08-10T09:00:00Z"), Instant.parse("2026-08-18T09:00:00Z"),
                Instant.parse("2026-08-20T09:00:00Z"));
        // Horizon is ONBOARDING, not LIVE — a DONE step there must not appear.
        doneStep(horizonErpJourney, 1, "Also done", meera,
                Instant.parse("2026-08-10T09:00:00Z"), Instant.parse("2026-08-18T09:00:00Z"),
                Instant.parse("2026-08-20T09:00:00Z"));

        List<ObDashboardCardItemsRepository.ItemRow> rows =
                fetch(ObDashboardCardKey.LIVE, unrestricted(), null, null);

        assertThat(rows).extracting(ObDashboardCardItemsRepository.ItemRow::itemId)
                .containsExactly(shipped);
    }

    // ── client-escalations: open, newest first ───────────────────────────────

    @Test
    @DisplayName("client-escalations matches only steps with an open escalation, newest raised first")
    void clientEscalationsOrdersNewestFirst() {
        long contact = insertContact(horizon);
        long older = step(horizonErpJourney, 1, "Escalated a while ago", "IN_PROGRESS", meera, null,
                THIS_WEEK_DUE.minus(Duration.ofDays(1)), THIS_WEEK_DUE);
        long newer = step(horizonErpJourney, 2, "Escalated just now", "IN_PROGRESS", meera, null,
                THIS_WEEK_DUE.minus(Duration.ofDays(1)), THIS_WEEK_DUE);
        long resolvedAlready = step(horizonErpJourney, 3, "Escalated then resolved", "IN_PROGRESS", meera, null,
                THIS_WEEK_DUE.minus(Duration.ofDays(1)), THIS_WEEK_DUE);

        escalation(horizon, horizonErpJourney, older, contact, NOW.minus(Duration.ofDays(2)), null, null);
        escalation(horizon, horizonErpJourney, newer, contact, NOW.minus(Duration.ofHours(1)), null, null);
        escalation(horizon, horizonErpJourney, resolvedAlready, contact,
                NOW.minus(Duration.ofDays(5)), NOW.minus(Duration.ofDays(4)), meera);

        List<ObDashboardCardItemsRepository.ItemRow> rows =
                fetch(ObDashboardCardKey.CLIENT_ESCALATIONS, unrestricted(), null, null);

        assertThat(rows).extracting(ObDashboardCardItemsRepository.ItemRow::itemId)
                .containsExactly(newer, older);
    }

    // ── ongoing-projects: open items, services and prerequisites alike ──────

    @Test
    @DisplayName("ongoing-projects surfaces a locked journey's own blocking prerequisite")
    void ongoingProjectsSurfacesALockedJourneysPrerequisite() {
        long blocking = prereqTask(meadow, "Sign the agreement", "PENDING", THIS_WEEK_DUE);

        List<ObDashboardCardItemsRepository.ItemRow> rows =
                fetch(ObDashboardCardKey.ONGOING_PROJECTS, unrestricted(), null, null);

        assertThat(rows).extracting(ObDashboardCardItemsRepository.ItemRow::itemId).contains(blocking);
        assertThat(rows).filteredOn(r -> r.itemId() == blocking)
                .extracting(ObDashboardCardItemsRepository.ItemRow::itemType)
                .containsExactly("PREREQUISITE");
    }

    @Test
    @DisplayName("ongoing-projects excludes a completed journey's steps")
    void ongoingProjectsExcludesCompletedJourneys() {
        // Crestwood's journey is completed (see seed()); nothing open on it counts.
        doneStep(crestwoodErpJourney, 1, "Long since shipped", meera,
                Instant.parse("2026-08-10T09:00:00Z"), Instant.parse("2026-08-18T09:00:00Z"),
                Instant.parse("2026-08-20T09:00:00Z"));

        assertThat(fetch(ObDashboardCardKey.ONGOING_PROJECTS, unrestricted(), null, null))
                .noneMatch(r -> r.journeyId() != null && r.journeyId() == crestwoodErpJourney);
    }

    // ── ownerUserId: matches owner OR backup, and excludes prerequisites ────

    @Test
    @DisplayName("ownerUserId matches the backup owner too, and drops every prerequisite row")
    void ownerUserIdMatchesBackupAndDropsPrerequisites() {
        // Meera's own step must not match — Sunita is neither its owner nor its backup.
        step(horizonErpJourney, 1, "Meera's", "IN_PROGRESS", meera, null,
                THIS_WEEK_DUE.minus(Duration.ofDays(1)), THIS_WEEK_DUE);
        long backedUp = step(horizonErpJourney, 2, "Sunita backs Ravi up here", "IN_PROGRESS", ravi, sunita,
                THIS_WEEK_DUE.minus(Duration.ofDays(1)), THIS_WEEK_DUE);
        step(horizonErpJourney, 3, "Nobody's", "IN_PROGRESS", null, null,
                THIS_WEEK_DUE.minus(Duration.ofDays(1)), THIS_WEEK_DUE);
        prereqTask(meadow, "Would otherwise be ongoing too", "PENDING", THIS_WEEK_DUE);

        List<ObDashboardCardItemsRepository.ItemRow> rows = repository.page(
                ObDashboardCardKey.THIS_WEEKS_DEADLINES, unrestricted(), null, sunita, null, 50,
                NOW, new BigDecimal("0.75"), TODAY_START, TODAY_START.plus(Duration.ofDays(1)),
                Instant.parse("2026-08-31T00:00:00Z"), Instant.parse("2026-09-07T00:00:00Z"));

        assertThat(rows).extracting(ObDashboardCardItemsRepository.ItemRow::itemId)
                .containsExactly(backedUp);
    }

    // ── the cursor round-trips a page boundary ───────────────────────────────

    @Test
    @DisplayName("a keyset cursor page two continues exactly where page one stopped, no repeats or gaps")
    void theCursorRoundTrips() {
        long first = step(horizonErpJourney, 1, "First due", "IN_PROGRESS", meera, null,
                TODAY_DUE.minus(Duration.ofDays(1)), TODAY_DUE);
        long second = step(horizonErpJourney, 2, "Second due", "IN_PROGRESS", meera, null,
                TODAY_DUE.minus(Duration.ofDays(1)), TODAY_DUE.plus(Duration.ofHours(1)));
        long third = prereqTask(meadow, "Third due", "PENDING", TODAY_DUE.plus(Duration.ofHours(2)));

        var page1 = fetchPage(ObDashboardCardKey.TODAYS_DELIVERY, null, 2);
        assertThat(page1.data()).extracting(o -> o.itemId())
                .containsExactly(first, second);
        assertThat(page1.meta().hasMore()).isTrue();
        assertThat(page1.meta().nextCursor()).isNotBlank();

        var page2 = fetchPage(ObDashboardCardKey.TODAYS_DELIVERY, page1.meta().nextCursor(), 2);
        assertThat(page2.data()).extracting(o -> o.itemId()).containsExactly(third);
        assertThat(page2.meta().hasMore()).isFalse();
        assertThat(page2.meta().nextCursor()).isNull();
    }

    // ── the scope predicate, checked against the specification it restates ──

    /**
     * <b>The test {@link ObDashboardScope#journeyPredicate} and
     * {@link ObDashboardScope#clientPredicate} depend on.</b>
     *
     * <p>{@code ObReportsIT.theSqlAndTheSpecificationAgree}'s own pattern, one
     * package over: A-112's rule is a JPA specification and this class
     * re-expresses it as SQL because the union query has no single
     * {@code ObJourney} root to build a {@code Specification} against. Run for
     * every role against the same rows and asserted to select the same
     * journeys — if this test is deleted, the two predicates on
     * {@link ObDashboardScope} must be too.
     */
    @ParameterizedTest
    @ValueSource(strings = {"OB_ADMIN", "OB_MANAGER", "OB_VIEWER", "OB_SALES",
            "OB_STEP_OWNER", "TICKETING_MEMBER", ""})
    @DisplayName("the scope predicate and A-112's specification select the same journeys")
    void theScopePredicateAndTheSpecificationAgree(String role) {
        // Meera owns both steps outright. Ravi owns neither directly, but backs
        // Meera up on Crestwood's (Meera's own client) — so, tested as Ravi,
        // OB_SALES finds Horizon (Ravi created it) and OB_STEP_OWNER finds
        // Crestwood (Ravi backs up a step on it), which is both halves of the
        // rule in one pass rather than one role each proving only its own half.
        step(horizonErpJourney, 1, "Meera's step on Ravi's client", "DONE", meera, null, NOW, NOW);
        step(crestwoodErpJourney, 1, "Meera's step, Ravi backs it up", "DONE", meera, ravi, NOW, NOW);

        CallerIdentity caller = new CallerIdentity(ravi, "SUPPORT", List.of(),
                List.of("ONBOARDING"), role.isEmpty() ? Map.of() : Map.of("ONBOARDING", role));

        List<Long> viaSpecification = journeyRepository
                .findAll(OnboardingScopeResolver.journeyScope(caller)).stream()
                .map(ObJourney::getId)
                .filter(id -> id == horizonErpJourney || id == crestwoodErpJourney)
                .sorted()
                .toList();

        ObDashboardScope scope = ObDashboardScope.of(caller);
        var query = jdbcClient.sql("""
                SELECT jr.id FROM ob_journeys jr JOIN ob_clients cl ON cl.id = jr.ob_client_id
                 WHERE jr.id IN (:j1, :j2) AND (%s)
                """.formatted(scope.journeyPredicate("jr", "cl")))
                .param("j1", horizonErpJourney)
                .param("j2", crestwoodErpJourney);
        if (!scope.unrestricted()) {
            query = query.param(ObDashboardScope.USER_PARAM, scope.userId());
        }
        List<Long> viaSql = query.query(Long.class).list().stream().sorted().toList();

        assertThat(viaSql).as("role %s", role.isEmpty() ? "(none)" : role).isEqualTo(viaSpecification);
    }

    // ── OB_SALES and OB_STEP_OWNER, the two roles the summary route cannot answer ──

    @Test
    @DisplayName("OB_SALES sees only items belonging to clients they created")
    void obSalesSeesOnlyTheirOwnClients() {
        long ravisItem = step(horizonErpJourney, 1, "Ravi's client", "IN_PROGRESS", meera, null,
                THIS_WEEK_DUE.minus(Duration.ofDays(1)), THIS_WEEK_DUE);
        step(crestwoodErpJourney, 1, "Meera's client", "IN_PROGRESS", meera, null,
                THIS_WEEK_DUE.minus(Duration.ofDays(1)), THIS_WEEK_DUE);

        List<ObDashboardCardItemsRepository.ItemRow> rows = fetch(
                ObDashboardCardKey.THIS_WEEKS_DEADLINES, scope(ravi, "OB_SALES"), null, null);

        assertThat(rows).extracting(ObDashboardCardItemsRepository.ItemRow::itemId)
                .containsExactly(ravisItem);
    }

    @Test
    @DisplayName("OB_STEP_OWNER sees every item of a journey containing one of their own steps")
    void obStepOwnerSeesTheWholeJourney() {
        long own = step(horizonErpJourney, 1, "Sunita's own step", "IN_PROGRESS", sunita, null,
                THIS_WEEK_DUE.minus(Duration.ofDays(1)), THIS_WEEK_DUE);
        // Not Sunita's, but on the same journey — "journeys containing their
        // steps" is journey-wide visibility, the same reading the summary card
        // and OnboardingScopeResolver both take.
        long sameJourney = step(horizonErpJourney, 2, "Meera's, same journey", "IN_PROGRESS", meera, null,
                THIS_WEEK_DUE.minus(Duration.ofDays(1)), THIS_WEEK_DUE);
        // A different client entirely — must not appear.
        step(crestwoodErpJourney, 1, "Different journey", "IN_PROGRESS", meera, null,
                THIS_WEEK_DUE.minus(Duration.ofDays(1)), THIS_WEEK_DUE);

        List<ObDashboardCardItemsRepository.ItemRow> rows = fetch(
                ObDashboardCardKey.THIS_WEEKS_DEADLINES, scope(sunita, "OB_STEP_OWNER"), null, null);

        assertThat(rows).extracting(ObDashboardCardItemsRepository.ItemRow::itemId)
                .containsExactlyInAnyOrder(own, sameJourney);
    }

    @Test
    @DisplayName("OB_STEP_OWNER also sees a prerequisite of a client whose journey contains their step")
    void obStepOwnerSeesThePrerequisiteToo() {
        // Give Meadow (Ravi's client) an open journey Sunita owns a step on, so
        // the client-level predicate has something to find. Reuses erpTemplate
        // rather than a fresh insertTemplate(erp) — uq_ob_journey_templates_version
        // is (product_id, version), and seed() already claimed version 1.
        long openJourney = insertJourney(meadow, erp, erpTemplate, "OPEN", null);
        step(openJourney, 1, "Sunita's step", "IN_PROGRESS", sunita, null,
                THIS_WEEK_DUE.minus(Duration.ofDays(1)), THIS_WEEK_DUE);
        long task = prereqTask(meadow, "Meadow's own prerequisite", "PENDING", THIS_WEEK_DUE);

        List<ObDashboardCardItemsRepository.ItemRow> rows = fetch(
                ObDashboardCardKey.THIS_WEEKS_DEADLINES, scope(sunita, "OB_STEP_OWNER"), null, null);

        assertThat(rows).extracting(ObDashboardCardItemsRepository.ItemRow::itemId).contains(task);
    }

    @Test
    @DisplayName("a role that denies everything gets nothing, not the org-wide list")
    void aDeniedRoleSeesNothing() {
        step(horizonErpJourney, 1, "Anything at all", "IN_PROGRESS", meera, null,
                THIS_WEEK_DUE.minus(Duration.ofDays(1)), THIS_WEEK_DUE);

        var response = service().items(caller(99, "TICKETING_MEMBER"),
                "this-weeks-deadlines", null, null, null, null);

        assertThat(response.data()).isEmpty();
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private List<ObDashboardCardItemsRepository.ItemRow> fetch(
            ObDashboardCardKey cardKey, ObDashboardScope scope, Long productId, Long ownerUserId) {
        return repository.page(cardKey, scope, productId, ownerUserId, null, 50, NOW,
                new BigDecimal("0.75"), TODAY_START, TODAY_START.plus(Duration.ofDays(1)),
                Instant.parse("2026-08-31T00:00:00Z"), Instant.parse("2026-09-07T00:00:00Z"));
    }

    private ObDashboardDtos.ObDashboardItemListResponse fetchPage(
            ObDashboardCardKey cardKey, String cursor, int limit) {
        return service().items(caller(0, "OB_MANAGER"), cardKey.wireName(), null, null, cursor, limit);
    }

    /** A fresh service per call, fixed to {@link #NOW} rather than the autowired bean's real clock. */
    private ObDashboardCardItemsService service() {
        return new ObDashboardCardItemsService(
                repository, summaries, calendars, Clock.fixed(NOW, ZoneOffset.UTC), new BigDecimal("0.75"));
    }

    private static ObDashboardScope unrestricted() {
        return new ObDashboardScope(true, "OB_ADMIN", 0L);
    }

    private static ObDashboardScope scope(long userId, String role) {
        return ObDashboardScope.of(caller(userId, role));
    }

    private static CallerIdentity caller(long userId, String moduleRole) {
        return new CallerIdentity(
                userId, "SUPPORT", List.of(), List.of("ONBOARDING"), Map.of("ONBOARDING", moduleRole));
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

    private long insertTemplate(long productId) {
        jdbc.update("""
                INSERT INTO ob_journey_templates (product_id, name, version, is_active)
                VALUES (?, ?, 1, 1)
                """, productId, "it_obcards_template_" + productId + "_" + System.nanoTime());
        return lastId();
    }

    private long insertClient(String name, long createdBy, String status) {
        jdbc.update("""
                INSERT INTO ob_clients (name, onboarding_date, overall_status, created_by, live_at)
                VALUES (?, '2026-03-01', ?, ?, ?)
                """, name, status, createdBy,
                "LIVE".equals(status) ? Timestamp.from(Instant.parse("2026-08-20T09:00:00Z")) : null);
        return lastId();
    }

    private long insertJourney(long clientId, long productId, long templateId, String gate, Instant completedAt) {
        jdbc.update("""
                INSERT INTO ob_journeys (ob_client_id, product_id, template_id, gate_status,
                                         gate_opened_at, started_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, clientId, productId, templateId, gate,
                "OPEN".equals(gate) ? Timestamp.from(Instant.parse("2026-03-01T09:00:00Z")) : null,
                "OPEN".equals(gate) ? Timestamp.from(Instant.parse("2026-03-01T09:00:00Z")) : null,
                completedAt == null ? null : Timestamp.from(completedAt));
        return lastId();
    }

    private long step(long journeyId, int sequence, String name, String status, Long owner, Long backup,
                      Instant startedAt, Instant dueAt) {
        jdbc.update("""
                INSERT INTO ob_journey_steps (journey_id, sequence, name, tat_days, status,
                                              owner_user_id, backup_owner_user_id, started_at, due_at)
                VALUES (?, ?, ?, 3, ?, ?, ?, ?, ?)
                """, journeyId, sequence, name, status, owner, backup,
                startedAt == null ? null : Timestamp.from(startedAt),
                dueAt == null ? null : Timestamp.from(dueAt));
        return lastId();
    }

    private long doneStep(long journeyId, int sequence, String name, Long owner,
                          Instant startedAt, Instant finishedAt, Instant dueAt) {
        jdbc.update("""
                INSERT INTO ob_journey_steps (journey_id, sequence, name, tat_days, status,
                                              owner_user_id, started_at, finished_at, due_at)
                VALUES (?, ?, ?, 3, 'DONE', ?, ?, ?, ?)
                """, journeyId, sequence, name, owner,
                Timestamp.from(startedAt), Timestamp.from(finishedAt), Timestamp.from(dueAt));
        return lastId();
    }

    private long prereqHeader(long clientId) {
        // ck_ob_prereq_template_versions_draft_not_active: an active version must
        // be published — a draft cannot hold the single active slot.
        jdbc.update("""
                INSERT INTO ob_prereq_template_versions (version, is_active, published_at, published_by)
                VALUES (1, 1, ?, ?)
                """, Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")), ravi);
        long versionId = lastId();
        jdbc.update("""
                INSERT INTO ob_client_prereqs (ob_client_id, template_version_id, template_version, status)
                VALUES (?, ?, 1, 'IN_PROGRESS')
                """, clientId, versionId);
        return lastId();
    }

    /**
     * {@code is_ad_hoc = 1} with no {@code template_task_id} — the simpler side
     * of {@code ck_ob_client_prereq_tasks_ad_hoc}, which forbids the other
     * combination this fixture would otherwise default into (a non-ad-hoc task
     * with no template row behind it).
     */
    private long prereqTask(long clientId, String title, String status, Instant dueAt) {
        long headerId = prereqHeader(clientId);
        jdbc.update("""
                INSERT INTO ob_client_prereq_tasks (ob_client_prereqs_id, ob_client_id, sequence, title,
                                                     is_mandatory, is_ad_hoc, status, due_at)
                VALUES (?, ?, 1, ?, 1, 1, ?, ?)
                """, headerId, clientId, title, status, Timestamp.from(dueAt));
        return lastId();
    }

    private long insertContact(long clientId) {
        jdbc.update("""
                INSERT INTO ob_client_contacts (ob_client_id, name, email, is_primary)
                VALUES (?, 'A Contact', ?, 0)
                """, clientId, "contact" + System.nanoTime() + "@example.com");
        return lastId();
    }

    /** {@code resolved_by} is a {@code users} id — the raiser is a contact, the resolver is staff. */
    private void escalation(long clientId, long journeyId, long stepId, long contactId,
                            Instant raisedAt, Instant resolvedAt, Long resolvedByUserId) {
        jdbc.update("""
                INSERT INTO ob_client_escalations (ob_client_id, journey_id, step_id, raised_by_contact_id,
                                                    comment, raised_at, resolved_at, resolved_by)
                VALUES (?, ?, ?, ?, 'Please help', ?, ?, ?)
                """, clientId, journeyId, stepId, contactId, Timestamp.from(raisedAt),
                resolvedAt == null ? null : Timestamp.from(resolvedAt), resolvedByUserId);
    }

    private long lastId() {
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
