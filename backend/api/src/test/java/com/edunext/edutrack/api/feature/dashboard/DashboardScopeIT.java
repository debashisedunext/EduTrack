package com.edunext.edutrack.api.feature.dashboard;

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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-054 · role-awareness, asserted against the summary tables.
 *
 * <h2>Why this suite exists rather than trusting {@code ScopeResolver}</h2>
 *
 * <p>The dashboard cannot reuse {@code ScopeResolver}: that produces a
 * {@code Specification<Ticket>} and these reads deliberately never touch
 * {@code tickets} — CLAUDE.md forbids a live {@code COUNT(*)} behind a
 * dashboard, which is why A-050 built the summary tables at all. So the same
 * rule is stated twice in the codebase, and <b>this suite is what stops the two
 * from drifting</b>. Every assertion below is one the ticket list already makes
 * in {@code TicketScopeIT}; if one passes there and fails here, the dashboard is
 * showing somebody rows their ticket list would refuse them.
 *
 * <p>Summary rows are inserted directly rather than produced by A-051's worker.
 * What is under test is the read path and the role decision, and computing the
 * rows first would make a failure ambiguous between the two.
 */
@SpringBootTest
@Testcontainers
class DashboardScopeIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_dashboard_it")
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
    DashboardService service;

    @Autowired
    JdbcTemplate jdbc;

    private static final LocalDate D1 = LocalDate.of(2026, 8, 10);
    private static final LocalDate D2 = LocalDate.of(2026, 8, 11);
    private static final LocalDate D3 = LocalDate.of(2026, 8, 12);

    private long mine;
    private long theirs;
    private long me;
    private long colleague;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM daily_ticket_stats");
        jdbc.update("DELETE FROM resource_daily_stats");

        mine = project("DSHA");
        theirs = project("DSHB");
        me = user("dsh.me");
        colleague = user("dsh.them");

        // Project rows: my project has 5 open every day; theirs has 100.
        for (LocalDate d : List.of(D1, D2, D3)) {
            projectStat(d, mine, 2, 1, 0, 5, 1, 1, 1);
            projectStat(d, theirs, 40, 20, 3, 100, 30, 20, 10);
        }

        // Resource rows: I hold 3, my colleague holds 60.
        for (LocalDate d : List.of(D1, D2, D3)) {
            resourceStat(d, me, 1, 3, 1, 0);
            resourceStat(d, colleague, 15, 60, 25, 12);
        }
    }

    /**
     * A plain counter, not {@code nanoTime() % 100000}.
     *
     * <p>That was the first draft and it collided — {@code project_code} is
     * unique, five digits of a nanosecond clock is a small space, and these
     * methods run milliseconds apart. A fixture whose uniqueness is
     * probabilistic gives a suite that is green until it is not, on a machine
     * nobody can reproduce.
     *
     * <p>Seeding it from the clock would be the obvious fix and is the wrong
     * one: {@code project_code} is {@code VARCHAR(10)} — it is the ticket-ID
     * prefix — and a nanosecond seed is nineteen digits. This container is
     * created for this class alone, so counting from zero is both unique and
     * short enough to fit.
     */
    private static final java.util.concurrent.atomic.AtomicInteger SEQ =
            new java.util.concurrent.atomic.AtomicInteger();

    private long project(String code) {
        jdbc.update("INSERT INTO projects (project_code, name, status) VALUES (?, ?, 'ACTIVE')",
                code + SEQ.incrementAndGet(), "Dashboard IT");
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long user(String name) {
        Long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code = 'DEVELOPER'", Long.class);
        String u = name + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id, is_active)
                VALUES (?, ?, ?, 'x', 'Dashboard IT', ?, 1)
                """, u, u, u + "@example.test", roleId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void projectStat(LocalDate day, long projectId, int created, int closed, int reopened,
                             int openTotal, int openCritical, int openDelayed, int openReopened) {
        jdbc.update("""
                INSERT INTO daily_ticket_stats (stat_date, project_id, created, closed, reopened,
                                                open_total, open_critical, open_delayed, open_reopened,
                                                computed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, '2026-08-12 06:00:00')
                """, day, projectId, created, closed, reopened, openTotal, openCritical, openDelayed,
                openReopened);
    }

    private void resourceStat(LocalDate day, long userId, int closed, int assignedOpen,
                              int assignedCritical, int assignedDelayed) {
        jdbc.update("""
                INSERT INTO resource_daily_stats (stat_date, user_id, closed, effort_hours,
                                                  assigned_open, assigned_critical, assigned_delayed,
                                                  computed_at)
                VALUES (?, ?, ?, 0.00, ?, ?, ?, '2026-08-12 06:00:00')
                """, day, userId, closed, assignedOpen, assignedCritical, assignedDelayed);
    }

    private CallerIdentity caller(long userId, String role, List<Long> projects) {
        return new CallerIdentity(userId, role, projects);
    }

    private static BigDecimal valueOf(DashboardDtos.Summary s, String key) {
        return s.cards().stream().filter(c -> c.key().equals(key)).findFirst().orElseThrow().value();
    }

    // ── which table answers ──────────────────────────────────────────────────

    @Nested
    @DisplayName("role decides which summary table answers")
    class TableChoice {

        /**
         * The leak this whole design avoids. A Developer works inside a project
         * that holds 100 open tickets; three are theirs. Reading the
         * project-keyed table — however it were filtered — would show 100.
         */
        @Test
        @DisplayName("a developer sees their own work, not their project's")
        void developerReadsTheResourceTable() {
            DashboardDtos.Summary s = service.summary(
                    caller(me, "DEVELOPER", List.of(mine, theirs)), null, D1, D3, null);

            assertThat(valueOf(s, "open"))
                    .as("3 assigned to me, not the 105 open across projects I work in")
                    .isEqualByComparingTo("3");
        }

        @Test
        @DisplayName("a colleague's tickets do not move a developer's numbers")
        void colleagueChangesAreInvisible() {
            DashboardDtos.Summary before = service.summary(
                    caller(me, "DEVELOPER", List.of(mine)), null, D1, D3, null);

            resourceStat(D3.plusDays(1), colleague, 99, 999, 99, 99);

            DashboardDtos.Summary after = service.summary(
                    caller(me, "DEVELOPER", List.of(mine)), null, D1, D3, null);

            assertThat(valueOf(after, "open")).isEqualByComparingTo(valueOf(before, "open"));
        }

        /**
         * The other half of the same rule: a Developer must not be able to read
         * somebody else's dashboard by naming them.
         */
        @Test
        @DisplayName("a developer naming another assignee still gets their own figures")
        void assigneeIdCannotBeBorrowed() {
            DashboardDtos.Summary s = service.summary(
                    caller(me, "DEVELOPER", List.of(mine)), null, D1, D3, colleague);

            assertThat(valueOf(s, "open"))
                    .as("asking for the colleague's 60 returns my own 3")
                    .isEqualByComparingTo("3");
        }
    }

    // ── project scope ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("project scope narrows and cannot be widened")
    class ProjectScope {

        @Test
        @DisplayName("a PM sees only their own projects")
        void pmSeesOwnProjects() {
            DashboardDtos.Summary s = service.summary(
                    caller(me, "PM", List.of(mine)), null, D1, D3, null);

            assertThat(valueOf(s, "open")).as("5 in my project, not 105").isEqualByComparingTo("5");
        }

        @Test
        @DisplayName("a PM asking for a project they do not hold gets zeroes, not that project")
        void filterCannotWidenScope() {
            DashboardDtos.Summary s = service.summary(
                    caller(me, "PM", List.of(mine)), theirs, D1, D3, null);

            assertThat(valueOf(s, "open")).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("an admin sees across projects")
        void adminIsUnrestricted() {
            DashboardDtos.Summary s = service.summary(
                    caller(me, "ADMIN", List.of()), null, D1, D3, null);

            assertThat(valueOf(s, "open")).as("5 + 100").isEqualByComparingTo("105");
        }
    }

    // ── the arithmetic ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("flow sums, stock does not")
    class Arithmetic {

        /**
         * The bug that would look plausible for months. Three days of 5 open
         * tickets is 5 open tickets, not 15 — "how many were open" is a state,
         * and summing a week of it answers a question nobody asked.
         */
        @Test
        @DisplayName("stock reads the latest day, never a sum across the range")
        void stockDoesNotSumAcrossDays() {
            DashboardDtos.Summary s = service.summary(
                    caller(me, "PM", List.of(mine)), null, D1, D3, null);

            assertThat(valueOf(s, "open"))
                    .as("3 days x 5 open is still 5 open")
                    .isEqualByComparingTo("5");
        }

        @Test
        @DisplayName("flow sums across the range")
        void flowSums() {
            DashboardDtos.Summary s = service.summary(
                    caller(me, "PM", List.of(mine)), null, D1, D3, null);

            assertThat(valueOf(s, "total")).as("2 created x 3 days").isEqualByComparingTo("6");
            assertThat(valueOf(s, "closed")).as("1 closed x 3 days").isEqualByComparingTo("3");
        }
    }

    // ── the shell's contract ─────────────────────────────────────────────────

    @Nested
    @DisplayName("what the shell is given")
    class ShellContract {

        @Test
        @DisplayName("asOf reports how stale the figures are")
        void asOfIsSurfaced() {
            DashboardDtos.Summary s = service.summary(
                    caller(me, "ADMIN", List.of()), null, D1, D3, null);

            assertThat(s.asOf())
                    .as("the tables refresh every 5 minutes; a dashboard that hides that invites misplaced trust")
                    .isNotNull();
        }

        @Test
        @DisplayName("every card carries the list it opens")
        void everyCardDeepLinks() {
            DashboardDtos.Summary s = service.summary(
                    caller(me, "ADMIN", List.of()), null, D1, D3, null);

            assertThat(s.cards()).hasSize(6).allSatisfy(c -> {
                assertThat(c.drillDown()).as("§S-05: a number nobody can click is a number nobody trusts")
                        .startsWith("/tickets?");
                assertThat(c.drillDown()).contains("from=" + D1, "to=" + D3);
            });
        }

        /**
         * A-055 fills these. Null and empty render as absent; zero would render
         * as "no change" and a zero-filled sparkline as a flat line — both are
         * assertions nobody computed.
         */
        @Test
        @DisplayName("deltaPct and sparkline stay empty until A-055")
        void unbuiltFieldsAreNotFaked() {
            DashboardDtos.Summary s = service.summary(
                    caller(me, "ADMIN", List.of()), null, D1, D3, null);

            assertThat(s.cards()).allSatisfy(c -> {
                assertThat(c.deltaPct()).isNull();
                assertThat(c.sparkline()).isEmpty();
            });
        }
    }
}
