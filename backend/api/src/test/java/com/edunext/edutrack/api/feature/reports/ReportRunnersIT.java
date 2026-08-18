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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-066 · §7.8's first six reports, against real MySQL.
 *
 * <p>Each of these is a SQL question, and the ways they fail are ways no unit
 * test can see: a bound that drops the last day of the range, an average taken
 * over the wrong population, a scope clause that lets one person's rows into
 * another's report. The fixture is deliberately asymmetric — mine small and
 * legible, the colleague's an order of magnitude larger — so a scope failure
 * reads as an obviously wrong number rather than a plausible one.
 *
 * <h2>Isolation is by identity, not by truncation</h2>
 *
 * <p>Nothing is deleted between tests, and {@code ticket_effort_logs} could not
 * be even if it were convenient: A-008's trigger refuses a DELETE, which is the
 * append-only guarantee doing its job rather than an obstacle. So every test
 * seeds its own project, users and task types, and <b>every query is bounded to
 * this test's project</b> — including the Admin ones, which are unscoped by
 * definition and would otherwise see every previous test's rows.
 *
 * <p>Worth stating because it bit: the first version asserted "an Admin sees two
 * rows" with no project bound, passed alone, and reported eighteen when run with
 * the rest of the class.
 */
@SpringBootTest
@Testcontainers
class ReportRunnersIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_runners_it")
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
    ReportService service;

    @Autowired
    JdbcTemplate jdbc;

    private static final LocalDate FROM = LocalDate.of(2026, 8, 1);
    private static final LocalDate TO = LocalDate.of(2026, 8, 31);

    private static final AtomicInteger SEQ = new AtomicInteger();

    private long myProject;
    private long otherProject;
    private long me;
    private long colleague;
    private long taskTypeBug;
    private long taskTypeServer;

    /** Ticket ids by the label this fixture used, so effort attaches to the right row. */
    private final Map<String, Long> ticketIds = new HashMap<>();

    @BeforeEach
    void seed() {
        ticketIds.clear();

        myProject = project("RNA");
        otherProject = project("RNB");
        me = user("rn.me");
        colleague = user("rn.them");
        taskTypeBug = taskType("Bug");
        taskTypeServer = taskType("Server");

        // Velocity scopes a PM by project *membership*, because
        // resource_daily_stats has no project column. Without these rows it
        // correctly returns nothing — which looked like a broken query the first
        // time round, and was a missing fixture.
        member(me, myProject);
        member(colleague, otherProject);

        // Mine: two closed on time, one closed late and reopened twice, one
        // still open and past its date.
        ticket("t1", myProject, me, taskTypeBug, "2026-08-02", "2026-08-10", "2026-08-08", 0, 5, 4);
        ticket("t2", myProject, me, taskTypeBug, "2026-08-03", "2026-08-12", "2026-08-11", 0, 6, 8);
        ticket("t3", myProject, me, taskTypeServer, "2026-08-04", "2026-08-09", "2026-08-15", 2, 4, 10);
        ticket("t4", myProject, me, taskTypeBug, "2026-08-05", "2026-08-06", null, 0, 3, 0);

        for (int i = 0; i < 20; i++) {
            ticket("other" + i, otherProject, colleague, taskTypeBug,
                    "2026-08-02", "2026-08-20", "2026-08-19", 0, 2, 2);
        }

        effort(me, "t1", "2026-08-05", "3.00");
        effort(me, "t2", "2026-08-06", "5.00");
        // 20 rather than 40: ck_effort_hours caps one entry at 24, because
        // nobody works a forty-hour day and the schema says so.
        effort(colleague, "other0", "2026-08-06", "20.00");
    }

    private CallerIdentity admin() {
        return new CallerIdentity(1L, "ADMIN", List.of());
    }

    private CallerIdentity pm() {
        return new CallerIdentity(2L, "PM", List.of(myProject));
    }

    private CallerIdentity developer() {
        return new CallerIdentity(me, "DEVELOPER", List.of(myProject));
    }

    /** Bounded to this test's project — see the class note on isolation. */
    private List<Map<String, Object>> run(CallerIdentity caller, String key) {
        return run(caller, key, myProject);
    }

    private List<Map<String, Object>> run(CallerIdentity caller, String key, Long projectId) {
        return service.run(caller, key, FROM, TO, projectId, null).orElseThrow().report().rows();
    }

    private static Object cell(List<Map<String, Object>> rows, int index, String column) {
        return rows.get(index).get(column);
    }

    @Nested
    @DisplayName("1 · resource scorecard")
    class Scorecard {

        @Test
        @DisplayName("counts closed work, on-time closures and the SLA rate")
        void figures() {
            List<Map<String, Object>> rows = run(pm(), ResourceScorecardRunner.KEY);

            // Three closed. The open one is excluded: on-time and cycle time are
            // undefined for unfinished work, and including it would divide a real
            // numerator by a denominator holding work nobody could have finished.
            assertThat(rows).hasSize(1);
            assertThat(cell(rows, 0, "closed")).isEqualTo(3L);
            assertThat(cell(rows, 0, "onTime")).isEqualTo(2L);
            assertThat(cell(rows, 0, "slaPct")).hasToString("66.7");
        }

        @Test
        @DisplayName("reports estimated-versus-actual as signed hours")
        void variance() {
            // Actual 4+8+10 = 22 against estimated 5+6+4 = 15. Positive means it
            // took longer than estimated.
            assertThat(cell(run(pm(), ResourceScorecardRunner.KEY), 0, "variance")).hasToString("7.0");
        }

        @Test
        @DisplayName("a PM asking for a project that is not theirs is narrowed, not widened")
        void scoped() {
            // The colleague closed twenty in otherProject. Asking for it must
            // still answer with my project's single row, never theirs.
            List<Map<String, Object>> narrowed = run(pm(), ResourceScorecardRunner.KEY, otherProject);

            assertThat(narrowed).hasSize(1);
            assertThat(cell(narrowed, 0, "closed")).isEqualTo(3L);
        }

        @Test
        @DisplayName("an Admin can reach the other project, where a PM could not")
        void adminReachesBoth() {
            List<Map<String, Object>> theirs = run(admin(), ResourceScorecardRunner.KEY, otherProject);

            assertThat(theirs).hasSize(1);
            assertThat(cell(theirs, 0, "closed")).isEqualTo(20L);
        }

        @Test
        @DisplayName("utilisation is computed against the working calendar, not left null")
        void utilisation() {
            // Not pinned to a number — that would test the seeded calendar rather
            // than the behaviour. Null is what a missing WorkingHoursService
            // wiring would produce, and that is the failure worth catching.
            assertThat(cell(run(pm(), ResourceScorecardRunner.KEY), 0, "utilisation")).isNotNull();
        }
    }

    @Nested
    @DisplayName("2 · resource velocity")
    class Velocity {

        @Test
        @DisplayName("groups into ISO weeks and averages over the weeks actually seen")
        void oneResource() {
            resourceStat(LocalDate.of(2026, 8, 3), me, 2, "6.0");
            resourceStat(LocalDate.of(2026, 8, 10), me, 4, "9.0");
            resourceStat(LocalDate.of(2026, 8, 17), me, 1, "3.0");

            List<Map<String, Object>> rows = run(developer(), ResourceVelocityRunner.KEY, null);

            assertThat(rows).hasSize(3);
            assertThat(cell(rows, 0, "closed")).isEqualTo(2L);
            // Averaged over the weeks so far, not always over four — dividing by
            // 4 from week one draws a ramp that reads as somebody speeding up.
            assertThat(cell(rows, 0, "rolling")).hasToString("2.0");
            assertThat(cell(rows, 1, "rolling")).hasToString("3.0");
        }

        @Test
        @DisplayName("a developer's weeks are their own")
        void ownWorkOnly() {
            resourceStat(LocalDate.of(2026, 8, 3), me, 2, "6.0");
            resourceStat(LocalDate.of(2026, 8, 3), colleague, 9, "20.0");

            List<Map<String, Object>> rows = run(developer(), ResourceVelocityRunner.KEY, null);

            // 2, not 11. The colleague's nine are invisible.
            assertThat(rows).hasSize(1);
            assertThat(cell(rows, 0, "closed")).isEqualTo(2L);
        }
    }

    @Nested
    @DisplayName("3 · effort summary")
    class Effort {

        @Test
        @DisplayName("sums the effort log by resource, project and task type")
        void sums() {
            List<Map<String, Object>> rows = run(pm(), EffortSummaryRunner.KEY);

            // My two entries, one project, one task type: 3 + 5.
            assertThat(rows).hasSize(1);
            assertThat(cell(rows, 0, "hours")).hasToString("8.0");
            assertThat(cell(rows, 0, "tickets")).isEqualTo(2L);
        }

        @Test
        @DisplayName("a developer sees their own hours, never the colleague's twenty")
        void ownWork() {
            List<Map<String, Object>> rows = run(developer(), EffortSummaryRunner.KEY);

            assertThat(rows).hasSize(1);
            assertThat(cell(rows, 0, "hours")).hasToString("8.0");
        }
    }

    @Nested
    @DisplayName("4 · SLA breach")
    class Breach {

        @Test
        @DisplayName("lists the late closure and the still-open overdue one, and nothing on time")
        void lists() {
            List<Map<String, Object>> rows = run(pm(), SlaBreachRunner.KEY);

            // t3 closed six days late; t4 is open past its date. t1 and t2 met
            // theirs and must be absent.
            assertThat(rows).hasSize(2);
            assertThat(rows).extracting(r -> String.valueOf(r.get("ticket")))
                    .allSatisfy(code -> assertThat(code).matches("t[34]-\\d+"));
        }

        @Test
        @DisplayName("orders by how far overdue, because the list is read from the top")
        void worstFirst() {
            List<Map<String, Object>> rows = run(pm(), SlaBreachRunner.KEY);

            long first = ((Number) cell(rows, 0, "overdueHours")).longValue();
            long second = ((Number) cell(rows, 1, "overdueHours")).longValue();
            assertThat(first).isGreaterThanOrEqualTo(second);
        }

        @Test
        @DisplayName("a ticket with no planned close date cannot breach")
        void noCommitmentNoBreach() {
            ticket("nocommit", myProject, me, taskTypeBug, "2026-08-02", null, null, 0, 1, 0);

            // No commitment was made, so none was broken. A-057 drew the same
            // line for the gauge, which is why it has two columns.
            assertThat(run(pm(), SlaBreachRunner.KEY)).hasSize(2);
        }
    }

    @Nested
    @DisplayName("5 · task type analysis")
    class TaskTypes {

        @Test
        @DisplayName("counts raised and closed as separate populations")
        void twoPopulations() {
            List<Map<String, Object>> rows = run(pm(), TaskTypeAnalysisRunner.KEY);

            Map<String, Object> bug = rows.stream()
                    .filter(r -> "Bug".equals(r.get("taskType")))
                    .findFirst().orElseThrow();

            // Three bugs raised here, two closed in the window, one still open.
            assertThat(bug.get("raised")).isEqualTo(3L);
            assertThat(bug.get("closed")).isEqualTo(2L);
            assertThat(bug.get("stillOpen")).isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("6 · reopen analysis")
    class Reopens {

        @Test
        @DisplayName("counts reopen events, not merely reopened tickets")
        void countsEvents() {
            List<Map<String, Object>> rows = run(pm(), ReopenAnalysisRunner.KEY);

            // t3 was reopened twice. A boolean would report 1 and put it in the
            // same bucket as a ticket reopened once — the case this report exists
            // to surface.
            assertThat(rows).hasSize(1);
            assertThat(cell(rows, 0, "reopens")).isEqualTo(2L);
            assertThat(cell(rows, 0, "reopenedTickets")).isEqualTo(1L);
        }

        @Test
        @DisplayName("omits rows with no reopens — a signal is not a roll call")
        void onlyProblems() {
            // The colleague's twenty have no reopens and must be absent even for
            // an Admin who can see them.
            assertThat(run(admin(), ReopenAnalysisRunner.KEY, otherProject)).isEmpty();
        }
    }

    // ── fixture ──────────────────────────────────────────────────────────────

    private long project(String code) {
        jdbc.update("INSERT INTO projects (project_code, name, status) VALUES (?, ?, 'ACTIVE')",
                code + SEQ.incrementAndGet(), "Runners IT");
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long user(String name) {
        Long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code = 'DEVELOPER'", Long.class);
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

    private void ticket(String label, long projectId, long assignee, long taskTypeId,
                        String reported, String planned, String closed,
                        int reopenCount, int estimated, int actual) {
        String code = label + "-" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO tickets (ticket_code, project_id, title, task_type_id, level, original_level,
                                     status, date_reported, reported_by, assigned_to, planned_close_date,
                                     actual_close_date, estimated_effort_hrs, total_effort_hrs,
                                     reopen_count, is_reopened, current_cycle_no)
                VALUES (?, ?, 'Runners IT', ?, 'MEDIUM', 'MEDIUM', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                """,
                code, projectId, taskTypeId,
                closed == null ? "IN_PROGRESS" : "CLOSED",
                reported + " 09:00:00", assignee, assignee,
                planned == null ? null : planned + " 17:00:00",
                closed == null ? null : closed + " 12:00:00",
                estimated, actual, reopenCount, reopenCount > 0 ? 1 : 0);

        ticketIds.put(label, jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
    }

    /**
     * Attached by id, not by a {@code LIKE} on the ticket code.
     *
     * <p>The first version matched {@code ticket_code LIKE 't1%'} and took the
     * lowest id, which is a <em>previous test's</em> ticket once anything has run
     * before it — so the effort landed on somebody else's row and this test's
     * resource reported zero hours.
     */
    private void effort(long userId, String label, String workDate, String hours) {
        jdbc.update("""
                INSERT INTO ticket_effort_logs (ticket_id, cycle_no, stage_code, iteration_no,
                                                user_id, work_date, hours, prev_hash, row_hash)
                VALUES (?, 1, 'DEV', 1, ?, ?, ?, 'x', ?)
                """, ticketIds.get(label), userId, workDate, new BigDecimal(hours),
                "h" + SEQ.incrementAndGet());
    }

    private void resourceStat(LocalDate day, long userId, int closed, String effortHours) {
        jdbc.update("""
                INSERT INTO resource_daily_stats (stat_date, user_id, closed, effort_hours,
                                                  assigned_open, assigned_critical, assigned_delayed,
                                                  computed_at)
                VALUES (?, ?, ?, ?, 0, 0, 0, '2026-08-20 06:00:00')
                ON DUPLICATE KEY UPDATE closed = VALUES(closed), effort_hours = VALUES(effort_hours)
                """, day, userId, closed, new BigDecimal(effortHours));
    }
}
