package com.edunext.edutrack.api.feature.reports;

import com.edunext.edutrack.api.security.CallerIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B-065 · {@code timesheet_approvals} (V20260822_1200) against real MySQL.
 *
 * <p>Two things only a database can answer, on {@code TimesheetIT}'s own
 * argument: the direct-manager rule, which is a row — who reports to whom —
 * and {@code uq_timesheet_approvals_week}'s own guarantee, which a mock
 * repository cannot enforce by accident the way a real unique index does.
 */
@SpringBootTest
@Testcontainers
class TimesheetApprovalIT {

    static {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
    }

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_timesheet_approval_it")
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
    TimesheetApprovalService service;

    @Autowired
    JdbcTemplate jdbc;

    /** Monday 10 August 2026. */
    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 10);
    private static final LocalDate WEEK_OF = LocalDate.of(2026, 8, 13);

    private static final AtomicInteger SEQ = new AtomicInteger();

    private long subject;
    private long manager;
    private long teammatePm;
    private long admin;

    @BeforeEach
    void seed() {
        long project = project("TAA");
        subject = user("tsa.subject", "DEVELOPER");
        manager = user("tsa.manager", "PM");
        teammatePm = user("tsa.mate", "PM");
        admin = user("tsa.admin", "ADMIN");

        jdbc.update("UPDATE users SET reporting_manager_id = ? WHERE id = ?", manager, subject);
        member(subject, project);
        member(teammatePm, project);
    }

    @Nested
    @DisplayName("who may approve — a row question, resolved against the real reporting chain")
    class Authorisation {

        @Test
        @DisplayName("the direct reporting manager may approve")
        void directManager() {
            assertThat(service.approve(caller(manager, "PM"), subject, WEEK_OF, "Looks right")).isPresent();

            Long count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM timesheet_approvals WHERE user_id = ? AND week_start_date = ?",
                    Long.class, subject, MONDAY.toString());
            assertThat(count).isEqualTo(1L);
        }

        @Test
        @DisplayName("an Admin may approve anybody's week")
        void admin() {
            assertThat(service.approve(caller(admin, "ADMIN"), subject, WEEK_OF, null)).isPresent();
        }

        @Test
        @DisplayName("a PM sharing only a project, and not the reporting line, may not")
        void projectMateRefused() {
            // isVisibleTo would admit this PM to the GET; approving is narrower.
            assertThat(service.approve(caller(teammatePm, "PM"), subject, WEEK_OF, null)).isEmpty();

            Long count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM timesheet_approvals WHERE user_id = ?", Long.class, subject);
            assertThat(count).isZero();
        }

        @Test
        @DisplayName("a refusal and a missing subject are the same answer")
        void refusalLooksLikeAbsence() {
            assertThat(service.approve(caller(teammatePm, "PM"), subject, WEEK_OF, null)).isEmpty();
            assertThat(service.approve(caller(teammatePm, "PM"), 9_999_999L, WEEK_OF, null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("uq_timesheet_approvals_week")
    class OneReviewPerWeek {

        @Test
        @DisplayName("a second approval of an already-reviewed week is refused, naming the first reviewer")
        void secondApprovalRefused() {
            service.approve(caller(manager, "PM"), subject, WEEK_OF, "First pass");

            assertThatThrownBy(() -> service.approve(caller(admin, "ADMIN"), subject, WEEK_OF, "Second pass"))
                    .isInstanceOf(TimesheetApprovalService.AlreadyApprovedException.class);

            Long count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM timesheet_approvals WHERE user_id = ? AND week_start_date = ?",
                    Long.class, subject, MONDAY.toString());
            assertThat(count).isEqualTo(1L);
        }

        @Test
        @DisplayName("a different week for the same resource is a separate row")
        void differentWeekIsFine() {
            service.approve(caller(manager, "PM"), subject, WEEK_OF, null);

            assertThat(service.approve(caller(manager, "PM"), subject, WEEK_OF.plusWeeks(1), null))
                    .isPresent();
        }
    }

    // ── fixture ──────────────────────────────────────────────────────────────

    private CallerIdentity caller(long id, String role) {
        return new CallerIdentity(id, role, List.of());
    }

    private long project(String code) {
        jdbc.update("INSERT INTO projects (project_code, name, status) VALUES (?, ?, 'ACTIVE')",
                code + SEQ.incrementAndGet(), "Timesheet Approval IT");
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long user(String name, String role) {
        Long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code = ?", Long.class, role);
        String u = name + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id, is_active)
                VALUES (?, ?, ?, 'x', ?, ?, 1)
                """, u, u, u + "@example.test", u, roleId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void member(long userId, long projectId) {
        jdbc.update("""
                INSERT INTO project_members (project_id, user_id, role_in_project)
                VALUES (?, ?, 'DEVELOPER')
                """, projectId, userId);
    }
}
