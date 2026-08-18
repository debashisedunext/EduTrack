package com.edunext.edutrack.api.feature.reports;

import com.edunext.edutrack.api.security.CallerIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-069 · S-28's profile, against real MySQL.
 *
 * <p>Most of this suite is about <b>who may look at whom</b>. The figures are
 * A-066's arithmetic reapplied and are covered there; what is new and dangerous
 * here is that a profile names a person, and getting the visibility rule wrong
 * discloses staff rather than tickets.
 *
 * <p>Isolation is by identity, as everywhere in this feature: nothing is
 * deleted — A-008's triggers refuse it on the append-only tables — so each test
 * seeds its own people and projects.
 */
@SpringBootTest
@Testcontainers
class Profile360IT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_p360_it")
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
    Profile360Service service;

    @Autowired
    JdbcTemplate jdbc;

    private static final LocalDate FROM = LocalDate.of(2026, 8, 1);
    private static final LocalDate TO = LocalDate.of(2026, 8, 31);
    private static final AtomicInteger SEQ = new AtomicInteger();

    private long projectA;
    private long projectB;
    private long subject;      // the person being looked at
    private long manager;      // subject's reporting manager, on another project
    private long teammate;     // shares projectA with subject
    private long outsider;     // shares nothing
    private long taskType;

    @BeforeEach
    void seed() {
        projectA = project("PA");
        projectB = project("PB");
        subject = user("p360.subject", "DEVELOPER");
        manager = user("p360.manager", "PM");
        teammate = user("p360.mate", "PM");
        outsider = user("p360.outsider", "PM");
        taskType = taskType("Bug");

        // The subject reports to `manager`, who is on a different project — the
        // case an "and" reading of §2 would wrongly deny.
        jdbc.update("UPDATE users SET reporting_manager_id = ? WHERE id = ?", manager, subject);

        member(subject, projectA);
        member(teammate, projectA);
        member(manager, projectB);
        member(outsider, projectB);
    }

    private CallerIdentity caller(long id, String role) {
        return new CallerIdentity(id, role, List.of(projectA));
    }

    @Nested
    @DisplayName("who may look at whom — §2's team-history row")
    class Visibility {

        @Test
        @DisplayName("an Admin sees anybody")
        void admin() {
            assertThat(service.profile(new CallerIdentity(1L, "ADMIN", List.of()), subject, FROM, TO))
                    .isPresent();
        }

        @Test
        @DisplayName("you always see yourself, whatever your role")
        void self() {
            // §2's row denies a Developer other people's history. It does not
            // deny them their own — the Reports row grants exactly that as
            // "Own perf.", and this screen is where it lives.
            assertThat(service.profile(caller(subject, "DEVELOPER"), subject, FROM, TO)).isPresent();
        }

        @Test
        @DisplayName("a PM sees a direct reportee even on another project")
        void reporteeAcrossProjects() {
            // §2 writes "reportees + project". Read as an intersection this
            // would fail — and it is exactly the person a manager most needs.
            assertThat(service.profile(caller(manager, "PM"), subject, FROM, TO)).isPresent();
        }

        @Test
        @DisplayName("a PM sees somebody on a project they share, without managing them")
        void projectMate() {
            assertThat(service.profile(caller(teammate, "PM"), subject, FROM, TO)).isPresent();
        }

        @Test
        @DisplayName("a PM who shares neither a project nor a reporting line sees nothing")
        void outsiderRefused() {
            assertThat(service.profile(caller(outsider, "PM"), subject, FROM, TO)).isEmpty();
        }

        @ParameterizedTest(name = "{0} cannot open a colleague''s profile")
        @ValueSource(strings = {"DEVELOPER", "QA", "DEPLOYMENT"})
        @DisplayName("the three delivery roles see nobody but themselves")
        void deliveryRolesRefused(String role) {
            assertThat(service.profile(caller(teammate, role), subject, FROM, TO)).isEmpty();
        }

        @Test
        @DisplayName("a refusal and a missing user are the same answer, so ids cannot be enumerated")
        void refusalLooksLikeAbsence() {
            // Both empty, so both become 404. A 403 for one and a 404 for the
            // other would let anyone walk the id space and learn who exists.
            assertThat(service.profile(caller(outsider, "PM"), subject, FROM, TO)).isEmpty();
            assertThat(service.profile(caller(outsider, "PM"), 9_999_999L, FROM, TO)).isEmpty();
        }
    }

    @Nested
    @DisplayName("the figures")
    class Figures {

        @Test
        @DisplayName("separates open now from closed in the window")
        void openVersusClosed() {
            ticket("open1", null, 0);
            ticket("open2", null, 0);
            ticket("done1", "2026-08-10", 0);

            Profile360Dtos.Profile p = service
                    .profile(new CallerIdentity(1L, "ADMIN", List.of()), subject, FROM, TO)
                    .orElseThrow();

            assertThat(p.openNow()).isEqualTo(2L);
            assertThat(p.closedInWindow()).isEqualTo(1L);
        }

        @Test
        @DisplayName("computes SLA compliance against committed tickets only")
        void slaAgainstCommitted() {
            // Two closed with a planned date, one met. One closed with none,
            // which must not count as met — that would report 66.7%.
            committed("c1", "2026-08-10", "2026-08-12");   // on time
            committed("c2", "2026-08-14", "2026-08-12");   // late
            ticket("c3", "2026-08-10", 0);                 // no commitment

            Profile360Dtos.Profile p = service
                    .profile(new CallerIdentity(1L, "ADMIN", List.of()), subject, FROM, TO)
                    .orElseThrow();

            assertThat(p.slaCompliancePct()).isEqualTo(new BigDecimal("50.0"));
        }

        @Test
        @DisplayName("a rate with nothing to divide by is null, not zero")
        void nullRates() {
            Profile360Dtos.Profile p = service
                    .profile(new CallerIdentity(1L, "ADMIN", List.of()), subject, FROM, TO)
                    .orElseThrow();

            // Nothing closed. 0% would say "nothing was on time"; null says the
            // question has no answer for this window.
            assertThat(p.slaCompliancePct()).isNull();
            assertThat(p.reworkRatePct()).isNull();
        }

        @Test
        @DisplayName("groups open work by the stage it is sitting at, busiest first")
        void stageLoad() {
            ticket("s1", null, 0, "DEV");
            ticket("s2", null, 0, "DEV");
            ticket("s3", null, 0, "QA");

            Profile360Dtos.Profile p = service
                    .profile(new CallerIdentity(1L, "ADMIN", List.of()), subject, FROM, TO)
                    .orElseThrow();

            assertThat(p.currentStages()).hasSize(2);
            assertThat(p.currentStages().get(0).stage()).isEqualTo("DEV");
            assertThat(p.currentStages().get(0).openCount()).isEqualTo(2L);
        }

        @Test
        @DisplayName("states the window it measured, so the screen need not guess")
        void statesItsWindow() {
            Profile360Dtos.Profile p = service
                    .profile(new CallerIdentity(1L, "ADMIN", List.of()), subject, FROM, TO)
                    .orElseThrow();

            assertThat(p.from()).isEqualTo("2026-08-01");
            assertThat(p.to()).isEqualTo("2026-08-31");
        }

        @Test
        @DisplayName("carries who they report to, by name")
        void managerName() {
            Profile360Dtos.Profile p = service
                    .profile(new CallerIdentity(1L, "ADMIN", List.of()), subject, FROM, TO)
                    .orElseThrow();

            assertThat(p.person().managerName()).startsWith("p360.manager");
        }
    }

    // ── fixture ──────────────────────────────────────────────────────────────

    private long project(String code) {
        jdbc.update("INSERT INTO projects (project_code, name, status) VALUES (?, ?, 'ACTIVE')",
                code + SEQ.incrementAndGet(), "P360 IT");
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

    private long taskType(String name) {
        jdbc.update("INSERT INTO task_types (code, name, is_active) VALUES (?, ?, 1)",
                name.toUpperCase() + SEQ.incrementAndGet(), name);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void ticket(String label, String closed, int reopens) {
        ticket(label, closed, reopens, "DEV");
    }

    private void ticket(String label, String closed, int reopens, String stage) {
        jdbc.update("""
                INSERT INTO tickets (ticket_code, project_id, title, task_type_id, level, original_level,
                                     status, date_reported, reported_by, assigned_to, current_stage,
                                     actual_close_date, estimated_effort_hrs, total_effort_hrs,
                                     reopen_count, is_reopened, current_cycle_no)
                VALUES (?, ?, 'P360 IT', ?, 'MEDIUM', 'MEDIUM', ?, '2026-08-02 09:00:00', ?, ?, ?,
                        ?, 1, 1, ?, ?, 1)
                """, label + "-" + SEQ.incrementAndGet(), projectA, taskType,
                closed == null ? "IN_PROGRESS" : "CLOSED", subject, subject, stage,
                closed == null ? null : closed + " 12:00:00", reopens, reopens > 0 ? 1 : 0);
    }

    /** Closed with a planned close date, so it counts towards SLA compliance. */
    private void committed(String label, String planned, String closed) {
        jdbc.update("""
                INSERT INTO tickets (ticket_code, project_id, title, task_type_id, level, original_level,
                                     status, date_reported, reported_by, assigned_to, current_stage,
                                     planned_close_date, actual_close_date, estimated_effort_hrs,
                                     total_effort_hrs, reopen_count, is_reopened, current_cycle_no)
                VALUES (?, ?, 'P360 IT', ?, 'MEDIUM', 'MEDIUM', 'CLOSED', '2026-08-02 09:00:00', ?, ?,
                        'DEV', ?, ?, 1, 1, 0, 0, 1)
                """, label + "-" + SEQ.incrementAndGet(), projectA, taskType, subject, subject,
                planned + " 17:00:00", closed + " 12:00:00");
    }
}
