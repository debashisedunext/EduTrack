package com.edunext.edutrack.api.feature.onboarding.dashboard;

import com.edunext.edutrack.api.feature.onboarding.dashboard.ObDashboardDtos.ObDelayedProject;
import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.domain.masters.WorkingCalendarRepository;
import com.edunext.edutrack.domain.masters.WorkingHoursService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-128 · {@link ObDelayedProjectsRepository} and {@link ObDelayedProjectsService}
 * against real MySQL — the SQL union of overdue-step and current-step, the
 * journey-running gate, {@code productsBought}, and the row-scope predicate,
 * all of which {@code ObDelayedProjectsServiceTest} mocks around rather than
 * exercises for real.
 *
 * <p>{@link #aFridayDueJourneyStillOpenMondayIsOneWorkingDayLate()} is the
 * pinned example CLAUDE.md itself names, run against the real
 * {@link WorkingHoursService} rather than a stubbed one — the one thing the
 * unit test cannot prove on its own.
 */
@SpringBootTest
@Testcontainers
class ObDelayedProjectsIT {

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

    /** A Wednesday. */
    private static final Instant NOW = Instant.parse("2026-09-02T10:00:00Z");
    /** Friday of the same week, at the end of a 09:30–18:30 working day. */
    private static final Instant FRIDAY_DUE = Instant.parse("2026-08-28T18:30:00Z");
    /** The following Monday, an hour into the working day. */
    private static final Instant MONDAY_AFTER = Instant.parse("2026-08-31T10:30:00Z");

    @Autowired
    ObDelayedProjectsRepository repository;

    @Autowired
    WorkingHoursService workingHours;

    @Autowired
    WorkingCalendarRepository calendars;

    @Autowired
    JdbcTemplate jdbc;

    private long ravi;
    private long meera;
    private long erp;
    private long biometric;
    private long erpTemplate;
    private long horizon;
    private long horizonErpJourney;

    @BeforeEach
    void seed() {
        jdbc.update("UPDATE working_calendar SET timezone = 'UTC' WHERE id = 1");

        jdbc.update("DELETE FROM ob_journey_steps");
        jdbc.update("DELETE FROM ob_journeys");
        jdbc.update("DELETE FROM ob_journey_templates WHERE name LIKE 'it_obdelayed_%'");
        jdbc.update("DELETE FROM ob_client_applications WHERE ob_client_id IN "
                + "(SELECT id FROM ob_clients WHERE name LIKE 'it_obdelayed_%')");
        jdbc.update("DELETE FROM ob_clients WHERE name LIKE 'it_obdelayed_%'");
        jdbc.update("DELETE FROM ob_products WHERE code LIKE 'IT_OBDELAYED_%'");
        jdbc.update("DELETE FROM users WHERE username LIKE 'it_obdelayed_%'");

        ravi = insertUser("it_obdelayed_ravi");
        meera = insertUser("it_obdelayed_meera");

        erp = insertProduct("IT_OBDELAYED_ERP", "ERP");
        biometric = insertProduct("IT_OBDELAYED_BIO", "Biometric");
        erpTemplate = insertTemplate(erp);

        horizon = insertClient("it_obdelayed_horizon", ravi);
        application(horizon, erp);
        application(horizon, biometric);

        horizonErpJourney = insertJourney(horizon, erp, erpTemplate, "OPEN", null, null, null);
    }

    @Test
    @DisplayName("a Friday-due journey still open Monday is one working day late, not three")
    void aFridayDueJourneyStillOpenMondayIsOneWorkingDayLate() {
        step(horizonErpJourney, 1, "Kick-off", "IN_PROGRESS", meera, FRIDAY_DUE.minusSeconds(3600), FRIDAY_DUE);

        ObDelayedProjectsService service = new ObDelayedProjectsService(
                repository, workingHours, calendars, Clock.fixed(MONDAY_AFTER, ZoneOffset.UTC));

        List<ObDelayedProject> rows = service.list(unrestrictedCaller(), null, null, null, null, null).data();

        assertThat(rows).hasSize(1);
        // A naive Instant.until(ChronoUnit.DAYS) would say 3 (Sat, Sun, Mon).
        assertThat(rows.get(0).delayedByDays()).isEqualTo(1);
    }

    @Test
    @DisplayName("a journey with no overdue step does not appear")
    void aJourneyWithNoOverdueStepIsAbsent() {
        step(horizonErpJourney, 1, "Not due yet", "IN_PROGRESS", meera, NOW, NOW.plusSeconds(3600 * 24));

        assertThat(service().list(unrestrictedCaller(), null, null, null, null, null).data()).isEmpty();
    }

    @Test
    @DisplayName("a locked journey never appears, even with a step shaped to look overdue")
    void aLockedJourneyIsExcluded() {
        long lockedJourney = insertJourney(horizon, biometric,
                insertTemplate(biometric), "LOCKED", null, null, null);
        step(lockedJourney, 1, "Shaped like overdue but locked", "IN_PROGRESS", meera,
                NOW.minusSeconds(3600 * 24 * 3), NOW.minusSeconds(3600));

        assertThat(service().list(unrestrictedCaller(), null, null, null, null, null).data()).isEmpty();
    }

    @Test
    @DisplayName("currentStep is null when the only open step is BLOCKED, per the contract's own wording")
    void currentStepIsNullWhenTheOnlyOpenStepIsBlocked() {
        step(horizonErpJourney, 1, "Stuck", "BLOCKED", meera,
                NOW.minusSeconds(3600 * 24 * 3), NOW.minusSeconds(3600));

        ObDelayedProject row = service().list(unrestrictedCaller(), null, null, null, null, null).data().get(0);

        assertThat(row.currentStep()).isNull();
        assertThat(row.responsible().id()).isEqualTo(meera);
    }

    @Test
    @DisplayName("currentStep names the in-flight step even when a different, earlier step is the overdue one")
    void currentStepCanDifferFromTheOverdueStep() {
        step(horizonErpJourney, 1, "Overdue and blocked", "BLOCKED", meera,
                NOW.minusSeconds(3600 * 24 * 3), NOW.minusSeconds(3600));
        long inFlight = step(horizonErpJourney, 2, "Running in parallel", "IN_PROGRESS", ravi,
                NOW.minusSeconds(1800), NOW.plusSeconds(3600 * 24));

        ObDelayedProject row = service().list(unrestrictedCaller(), null, null, null, null, null).data().get(0);

        assertThat(row.currentStep()).isNotNull();
        assertThat(row.currentStep().id()).isEqualTo(inFlight);
        // Responsible still names the overdue step's owner, not the in-flight one's.
        assertThat(row.responsible().id()).isEqualTo(meera);
    }

    @Test
    @DisplayName("productsBought lists every product the client bought, not only this journey's")
    void productsBoughtListsEveryPurchase() {
        step(horizonErpJourney, 1, "Late", "IN_PROGRESS", meera,
                NOW.minusSeconds(3600 * 24 * 3), NOW.minusSeconds(3600));

        ObDelayedProject row = service().list(unrestrictedCaller(), null, null, null, null, null).data().get(0);

        assertThat(row.productsBought()).extracting(p -> p.id()).containsExactlyInAnyOrder(erp, biometric);
        assertThat(row.product().id()).isEqualTo(erp);
    }

    // ── scope ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("OB_SALES sees only journeys of clients they created")
    void salesSeesOnlyTheirOwnClients() {
        step(horizonErpJourney, 1, "Late", "IN_PROGRESS", meera,
                NOW.minusSeconds(3600 * 24 * 3), NOW.minusSeconds(3600));

        assertThat(service().list(caller(ravi, "OB_SALES"), null, null, null, null, null).data()).hasSize(1);
        assertThat(service().list(caller(meera, "OB_SALES"), null, null, null, null, null).data()).isEmpty();
    }

    @Test
    @DisplayName("OB_STEP_OWNER sees only journeys containing one of their own steps")
    void stepOwnerSeesOnlyJourneysWithTheirOwnSteps() {
        step(horizonErpJourney, 1, "Late", "IN_PROGRESS", meera,
                NOW.minusSeconds(3600 * 24 * 3), NOW.minusSeconds(3600));

        assertThat(service().list(caller(meera, "OB_STEP_OWNER"), null, null, null, null, null).data()).hasSize(1);
        assertThat(service().list(caller(ravi, "OB_STEP_OWNER"), null, null, null, null, null).data()).isEmpty();
    }

    @Test
    @DisplayName("a caller with no recognised module role sees nothing")
    void anUnrecognisedRoleSeesNothing() {
        step(horizonErpJourney, 1, "Late", "IN_PROGRESS", meera,
                NOW.minusSeconds(3600 * 24 * 3), NOW.minusSeconds(3600));

        assertThat(service().list(caller(99, "TICKETING_MEMBER"), null, null, null, null, null).data()).isEmpty();
    }

    // ── filters ───────────────────────────────────────────────────────────────

    @Test
    void productIdNarrowsToThatJourneysProduct() {
        step(horizonErpJourney, 1, "Late", "IN_PROGRESS", meera,
                NOW.minusSeconds(3600 * 24 * 3), NOW.minusSeconds(3600));

        assertThat(service().list(unrestrictedCaller(), erp, null, null, null, null).data()).hasSize(1);
        assertThat(service().list(unrestrictedCaller(), biometric, null, null, null, null).data()).isEmpty();
    }

    @Test
    void ownerUserIdMatchesTheOverdueStepsOwnerOrBackup() {
        step(horizonErpJourney, 1, "Late", "IN_PROGRESS", meera,
                NOW.minusSeconds(3600 * 24 * 3), NOW.minusSeconds(3600));

        assertThat(service().list(unrestrictedCaller(), null, meera, null, null, null).data()).hasSize(1);
        assertThat(service().list(unrestrictedCaller(), null, ravi, null, null, null).data()).isEmpty();
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private ObDelayedProjectsService service() {
        return new ObDelayedProjectsService(repository, workingHours, calendars, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static CallerIdentity unrestrictedCaller() {
        return caller(0, "OB_ADMIN");
    }

    private static CallerIdentity caller(long userId, String moduleRole) {
        return new CallerIdentity(
                userId, "SUPPORT", List.of(), List.of("ONBOARDING"), Map.of("ONBOARDING", moduleRole));
    }

    private void application(long clientId, long productId) {
        jdbc.update("INSERT INTO ob_client_applications (ob_client_id, product_id) VALUES (?, ?)",
                clientId, productId);
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
                """, productId, "it_obdelayed_template_" + productId + "_" + System.nanoTime());
        return lastId();
    }

    private long insertClient(String name, long createdBy) {
        jdbc.update("""
                INSERT INTO ob_clients (name, onboarding_date, overall_status, created_by)
                VALUES (?, '2026-03-01', 'ONBOARDING', ?)
                """, name, createdBy);
        return lastId();
    }

    private long insertJourney(long clientId, long productId, long templateId, String gate,
            Instant completedAt, Long heldByJourneyId, Instant releasedAt) {
        jdbc.update("""
                INSERT INTO ob_journeys (ob_client_id, product_id, template_id, gate_status,
                                         gate_opened_at, started_at, completed_at,
                                         held_by_journey_id, released_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, clientId, productId, templateId, gate,
                "OPEN".equals(gate) ? Timestamp.from(Instant.parse("2026-03-01T09:00:00Z")) : null,
                "OPEN".equals(gate) ? Timestamp.from(Instant.parse("2026-03-01T09:00:00Z")) : null,
                completedAt == null ? null : Timestamp.from(completedAt),
                heldByJourneyId, releasedAt == null ? null : Timestamp.from(releasedAt));
        return lastId();
    }

    private long step(long journeyId, int sequence, String name, String status, Long owner,
            Instant startedAt, Instant dueAt) {
        // ck_ob_journey_steps_blocked_reason: BLOCKED requires a reason in the
        // same row, so it has to travel in this INSERT rather than a follow-up
        // UPDATE — a two-step write would violate the check on the first one.
        String blockedReason = "BLOCKED".equals(status) ? "OTHER" : null;
        jdbc.update("""
                INSERT INTO ob_journey_steps (journey_id, sequence, name, tat_days, status,
                                              owner_user_id, started_at, due_at, blocked_reason_code)
                VALUES (?, ?, ?, 3, ?, ?, ?, ?, ?)
                """, journeyId, sequence, name, status, owner,
                startedAt == null ? null : Timestamp.from(startedAt),
                dueAt == null ? null : Timestamp.from(dueAt), blockedReason);
        return lastId();
    }

    private long lastId() {
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
