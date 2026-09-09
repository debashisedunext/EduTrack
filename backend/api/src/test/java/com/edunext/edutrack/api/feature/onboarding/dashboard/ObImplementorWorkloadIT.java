package com.edunext.edutrack.api.feature.onboarding.dashboard;

import com.edunext.edutrack.api.feature.onboarding.dashboard.ObDashboardDtos.ObImplementorWorkload;
import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.domain.masters.WorkingCalendarRepository;
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

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-128 · {@link ObImplementorWorkloadRepository} and {@link ObImplementorWorkloadService}
 * against real MySQL — the population (including the bench), the
 * {@code isActive} join against {@code user_module_access}, the row-scope
 * predicate and the cursor's real SQL ordering. {@code ObImplementorWorkloadServiceTest}
 * covers {@code performanceScore} and the arithmetic sum without a database.
 */
@SpringBootTest
@Testcontainers
class ObImplementorWorkloadIT {

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

    private static final LocalDate STAT_DATE = LocalDate.of(2026, 9, 2);
    private static final LocalDate EARLIER_DATE = LocalDate.of(2026, 9, 1);

    @Autowired
    ObImplementorWorkloadRepository repository;

    @Autowired
    WorkingCalendarRepository calendars;

    @Autowired
    JdbcTemplate jdbc;

    private long priya;
    private long arjun;

    @BeforeEach
    void seed() {
        jdbc.update("UPDATE working_calendar SET timezone = 'UTC' WHERE id = 1");

        jdbc.update("DELETE FROM ob_implementor_daily_stats WHERE user_id IN "
                + "(SELECT id FROM users WHERE username LIKE 'it_obworkload_%')");
        jdbc.update("DELETE FROM user_module_access WHERE user_id IN "
                + "(SELECT id FROM users WHERE username LIKE 'it_obworkload_%')");
        jdbc.update("DELETE FROM users WHERE username LIKE 'it_obworkload_%'");

        priya = insertUser("it_obworkload_priya", "Priya Iyer");
        arjun = insertUser("it_obworkload_arjun", "Arjun Nair");
    }

    @Test
    @DisplayName("a row maps straight through, with isActive true for a live OB_STEP_OWNER grant")
    void aRowMapsThroughWithIsActive() {
        grant(priya);
        statsRow(STAT_DATE, priya, 5, 2, 1, 1, 0, 1, 0, 3, 1, 0, 0);

        ObImplementorWorkload row = service().list(unrestrictedCaller(), STAT_DATE, false, null, null)
                .data().get(0);

        assertThat(row.user().displayName()).isEqualTo("Priya Iyer");
        assertThat(row.isActive()).isTrue();
        assertThat(row.clientsOpen()).isEqualTo(5);
        assertThat(row.statDate()).isEqualTo(STAT_DATE);
    }

    @Test
    @DisplayName("the bench: a live grant with zero clients is still a row, not an absence")
    void theBenchIsARowNotAnAbsence() {
        grant(arjun);
        statsRow(STAT_DATE, arjun, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        ObImplementorWorkload row = service().list(unrestrictedCaller(), STAT_DATE, false, null, null)
                .data().get(0);

        assertThat(row.clientsOpen()).isZero();
        assertThat(row.performanceScore()).isNull();
    }

    @Test
    @DisplayName("a revoked grant is excluded by default and included with includeInactive")
    void aRevokedGrantIsExcludedByDefault() {
        grant(priya);
        revoke(priya);
        statsRow(STAT_DATE, priya, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        assertThat(service().list(unrestrictedCaller(), STAT_DATE, false, null, null).data()).isEmpty();

        List<ObImplementorWorkload> withInactive =
                service().list(unrestrictedCaller(), STAT_DATE, true, null, null).data();
        assertThat(withInactive).hasSize(1);
        assertThat(withInactive.get(0).isActive()).isFalse();
    }

    @Test
    @DisplayName("no statDate reads the most recently computed day, not every day stored")
    void noStatDateReadsTheLatestDay() {
        grant(priya);
        statsRow(EARLIER_DATE, priya, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        statsRow(STAT_DATE, priya, 4, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        List<ObImplementorWorkload> rows = service().list(unrestrictedCaller(), null, false, null, null).data();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).statDate()).isEqualTo(STAT_DATE);
        assertThat(rows.get(0).clientsOpen()).isEqualTo(4);
    }

    // ── scope ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("OB_STEP_OWNER sees only their own row")
    void stepOwnerSeesOnlyTheirOwnRow() {
        grant(priya);
        grant(arjun);
        statsRow(STAT_DATE, priya, 5, 5, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        statsRow(STAT_DATE, arjun, 3, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        List<ObImplementorWorkload> rows =
                service().list(caller(priya, "OB_STEP_OWNER"), STAT_DATE, false, null, null).data();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).user().id()).isEqualTo(priya);
    }

    /**
     * {@code ob_implementor_daily_stats} carries no client-creator column, so
     * OB_SALES cannot be narrowed from it at all — the same genuine gap the
     * summary board has for this role, restated here. An empty page, never
     * everyone's figures.
     */
    @Test
    @DisplayName("OB_SALES sees nothing — this table has no client-creator column to narrow against")
    void salesSeesNothing() {
        grant(priya);
        statsRow(STAT_DATE, priya, 5, 5, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        assertThat(service().list(caller(99, "OB_SALES"), STAT_DATE, false, null, null).data()).isEmpty();
    }

    @Test
    void anUnrestrictedRoleSeesEveryone() {
        grant(priya);
        grant(arjun);
        statsRow(STAT_DATE, priya, 5, 5, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        statsRow(STAT_DATE, arjun, 3, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        assertThat(service().list(unrestrictedCaller(), STAT_DATE, false, null, null).data()).hasSize(2);
    }

    // ── cursor ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the cursor round-trips the real SQL order: full name, then user id")
    void cursorRoundTripsTheRealOrder() {
        grant(priya);
        grant(arjun);
        statsRow(STAT_DATE, priya, 5, 5, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        statsRow(STAT_DATE, arjun, 3, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        var firstPage = service().list(unrestrictedCaller(), STAT_DATE, false, null, 1);
        assertThat(firstPage.data()).extracting(w -> w.user().displayName()).containsExactly("Arjun Nair");
        assertThat(firstPage.meta().hasMore()).isTrue();

        var secondPage = service().list(unrestrictedCaller(), STAT_DATE, false, firstPage.meta().nextCursor(), 1);
        assertThat(secondPage.data()).extracting(w -> w.user().displayName()).containsExactly("Priya Iyer");
        assertThat(secondPage.meta().hasMore()).isFalse();
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private ObImplementorWorkloadService service() {
        return new ObImplementorWorkloadService(repository, calendars);
    }

    private static CallerIdentity unrestrictedCaller() {
        return caller(0, "OB_MANAGER");
    }

    private static CallerIdentity caller(long userId, String moduleRole) {
        return new CallerIdentity(
                userId, "SUPPORT", List.of(), List.of("ONBOARDING"), Map.of("ONBOARDING", moduleRole));
    }

    private long insertUser(String username, String fullName) {
        Long roleId = jdbc.queryForObject("SELECT id FROM roles ORDER BY id LIMIT 1", Long.class);
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id)
                VALUES (?, ?, ?, 'not-a-real-hash', ?, ?)
                """, username, username, username + "@example.com", fullName, roleId);
        return lastId();
    }

    private void grant(long userId) {
        jdbc.update("""
                INSERT INTO user_module_access (user_id, module, module_role)
                VALUES (?, 'ONBOARDING', 'OB_STEP_OWNER')
                """, userId);
    }

    private void revoke(long userId) {
        jdbc.update("""
                UPDATE user_module_access SET revoked_at = NOW(6), revoked_by = ?
                 WHERE user_id = ? AND module = 'ONBOARDING' AND revoked_at IS NULL
                """, userId, userId);
    }

    private void statsRow(LocalDate statDate, long userId, int clientsOpen, int onTrack, int notStarted,
            int delayed, int atRisk, int blockedWaiting, int aheadOfSchedule,
            int completedOnTime, int completedEarly, int completedLate, int blockedHours) {
        jdbc.update("""
                INSERT INTO ob_implementor_daily_stats (
                    stat_date, user_id, clients_open, on_track, not_started, `delayed`,
                    at_risk, blocked_waiting, ahead_of_schedule,
                    completed_on_time, completed_early, completed_late, blocked_hours, computed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(6))
                """, statDate, userId, clientsOpen, onTrack, notStarted, delayed,
                atRisk, blockedWaiting, aheadOfSchedule, completedOnTime, completedEarly, completedLate,
                blockedHours);
    }

    private long lastId() {
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
