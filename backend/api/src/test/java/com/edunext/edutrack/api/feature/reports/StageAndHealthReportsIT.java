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
 * A-067 · §7.8's reports 8–12, against real MySQL.
 *
 * <p>The interesting failures here are arithmetic rather than plumbing: stock
 * summed like flow, an average dragged down by visits that have not finished,
 * an idle figure that goes negative. Each of those produces a plausible chart,
 * so each gets a case with numbers chosen to make the wrong answer obvious.
 *
 * <p>Isolation is by identity, as in {@code ReportRunnersIT}: nothing is
 * deleted — A-008's trigger refuses it on the append-only tables — so every
 * test seeds its own project and bounds every query to it.
 */
@SpringBootTest
@Testcontainers
class StageAndHealthReportsIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_a067_it")
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
    private long me;
    private long taskType;
    private final Map<String, Long> ticketIds = new HashMap<>();

    @BeforeEach
    void seed() {
        ticketIds.clear();
        myProject = project("A67");
        me = user("a67.me");
        taskType = taskType("Bug");
        member(me, myProject);
    }

    private CallerIdentity pm() {
        return new CallerIdentity(2L, "PM", List.of(myProject));
    }

    private List<Map<String, Object>> run(String key) {
        return service.run(pm(), key, FROM, TO, myProject, null, ReportFilters.NONE).orElseThrow().report().rows();
    }

    @Nested
    @DisplayName("8 · project health")
    class Health {

        @Test
        @DisplayName("sums flow over the window but reads stock at its last day")
        void flowSumsStockDoesNot() {
            // Three days, each with 2 created and 1 closed, and an open figure
            // that ends at 9. Flow must total 6 and 3; stock must read 9 — not
            // 27, which is what summing the stock column would give.
            projectStat(LocalDate.of(2026, 8, 10), 2, 1, 11);
            projectStat(LocalDate.of(2026, 8, 11), 2, 1, 10);
            projectStat(LocalDate.of(2026, 8, 12), 2, 1, 9);

            List<Map<String, Object>> rows = run(ProjectHealthRunner.KEY);

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).get("created")).isEqualTo(6L);
            assertThat(rows.get(0).get("closed")).isEqualTo(3L);
            assertThat(rows.get(0).get("openTotal")).isEqualTo(9L);
        }

        @Test
        @DisplayName("net change is closed minus raised, so a shrinking backlog is positive")
        void netChange() {
            projectStat(LocalDate.of(2026, 8, 10), 1, 4, 5);

            assertThat(run(ProjectHealthRunner.KEY).get(0).get("net")).isEqualTo(3L);
        }
    }

    @Nested
    @DisplayName("9 · aging")
    class Aging {

        @Test
        @DisplayName("reads one snapshot, not a sum of days, and stamps which one")
        void snapshot() {
            agingStat(LocalDate.of(2026, 8, 10), 1, 2, 3, 4);
            agingStat(LocalDate.of(2026, 8, 20), 5, 5, 5, 5);

            List<Map<String, Object>> rows = run(AgingReportRunner.KEY);

            // The later day only. Summing both would give 6/7/8/9 and a chart
            // that looked entirely reasonable.
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).get("bucket0to2")).isEqualTo(5L);
            assertThat(rows.get(0).get("bucket31Plus")).isEqualTo(5L);
            assertThat(rows.get(0).get("asOf")).isEqualTo("2026-08-20");
        }

        @Test
        @DisplayName("states the share over thirty days, which is what makes projects comparable")
        void staleShare() {
            agingStat(LocalDate.of(2026, 8, 20), 5, 5, 5, 5);

            // 5 of 20 open are over thirty days. open_total is the sum of the
            // buckets, so seeding only the last one would make the share 100%.
            assertThat(run(AgingReportRunner.KEY).get(0).get("staleShare")).hasToString("25.0");
        }
    }

    @Nested
    @DisplayName("10 · workload and capacity")
    class Workload {

        @Test
        @DisplayName("carries the load at the latest snapshot with real capacity beside it")
        void loadAndCapacity() {
            resourceStat(LocalDate.of(2026, 8, 10), me, 4);
            resourceStat(LocalDate.of(2026, 8, 20), me, 7);

            List<Map<String, Object>> rows = service
                    .run(pm(), WorkloadCapacityRunner.KEY, FROM, TO, null, null, ReportFilters.NONE)
                    .orElseThrow().report().rows();

            Map<String, Object> mine = rows.stream()
                    .filter(r -> String.valueOf(r.get("resource")).startsWith("a67.me"))
                    .findFirst().orElseThrow();

            // 7, not 11 — stock is read, never summed.
            assertThat(mine.get("assignedOpen")).isEqualTo(7L);
            // Capacity comes from B-023's calendar, so it must be present and
            // positive over a working month. Null is what a missing wiring gives.
            assertThat(mine.get("capacityHours")).isNotNull();
            assertThat(mine.get("perAvailableDay")).isNotNull();
        }

        /**
         * B-061 · the figure B-017 could not compute and flagged for this task.
         *
         * <p>The Team tab holds one project's rows, so it can show what a
         * project committed one person to and never what that person is
         * committed to in total — which is the only version of the number that
         * is a warning. Here it spans every active membership, including
         * projects the caller cannot see, for the reason the load columns beside
         * it already do: narrowing it would tell a PM who owns one of somebody's
         * three projects that there is room.
         */
        @Test
        @DisplayName("allocation totals every project the person is on, not the one being looked at")
        void allocationSpansEveryProject() {
            resourceStat(LocalDate.of(2026, 8, 20), me, 7);

            memberWithAllocation(me, project("A67B"), 60);
            memberWithAllocation(me, project("A67C"), 70);

            // Removed from a fourth. B-017 deactivates rather than deletes so a
            // membership can be restored without a fabricated departure — which
            // means a commitment that ended would otherwise be counted forever.
            long past = project("A67D");
            memberWithAllocation(me, past, 100);
            jdbc.update("UPDATE project_members SET is_active = 0 WHERE user_id = ? AND project_id = ?",
                    me, past);

            Map<String, Object> mine = mine();

            // 130, not 230: the ended membership is not a commitment.
            assertThat(new java.math.BigDecimal(String.valueOf(mine.get("allocationPct"))))
                    .isEqualByComparingTo("130");
            // Three active memberships, two of which stated a figure — the
            // fixture's own left allocation_pct NULL, as most real rows do.
            // Published so the reader can see that 130% is a floor: two more
            // projects could be committing this person to anything at all.
            assertThat(mine.get("projects")).isEqualTo(3L);
            assertThat(mine.get("allocationStated")).isEqualTo(2L);
        }

        /**
         * B-017 kept {@code allocation_pct} nullable and refused the contract's
         * {@code default: 100} precisely so that "not stated" and "committed to
         * nothing" would stay distinguishable. This is the report that would
         * have collapsed them: a 0% here is a decision somebody made, and a
         * backfilled 100 would have read as a resourcing crisis across the
         * fixture corpus.
         */
        @Test
        @DisplayName("no stated allocation anywhere reads as absent, never as 0%")
        void unstatedAllocationIsNullNotZero() {
            resourceStat(LocalDate.of(2026, 8, 20), me, 3);

            Map<String, Object> mine = mine();

            // SUM over an all-null set is SQL NULL, and getBigDecimal keeps it.
            // getInt would have answered 0 — B-017 named that exact trap on the
            // write side and it is the same one here.
            assertThat(mine.get("allocationPct")).isNull();
            assertThat(mine.get("projects")).isEqualTo(1L);
            assertThat(mine.get("allocationStated")).isEqualTo(0L);
        }

        /**
         * B-061 · this report declared a RESOURCE filter and
         * {@code ReportRepository.workload} had no parameter to honour it with,
         * so picking a person changed nothing. See {@code ReportRunner#run}.
         */
        @Test
        @DisplayName("?resourceId= narrows to one person")
        void resourceFilterNarrows() {
            long other = user("a67.other");
            member(other, myProject);
            resourceStat(LocalDate.of(2026, 8, 20), me, 7);
            resourceStat(LocalDate.of(2026, 8, 20), other, 4);

            // Both are on this test's project, so both are in range unfiltered —
            // which is what makes the filtered case mean something.
            assertThat(workload(null)).hasSize(2);

            List<Map<String, Object>> justMe = workload(me);
            assertThat(justMe).hasSize(1);
            assertThat(justMe.get(0).get("assignedOpen")).isEqualTo(7L);
        }

        private Map<String, Object> mine() {
            return workload(null).stream()
                    .filter(r -> String.valueOf(r.get("resource")).startsWith("a67.me"))
                    .findFirst().orElseThrow();
        }

        private List<Map<String, Object>> workload(Long resourceId) {
            return service
                    .run(pm(), WorkloadCapacityRunner.KEY, FROM, TO, null, resourceId, ReportFilters.NONE)
                    .orElseThrow().report().rows();
        }
    }

    @Nested
    @DisplayName("11 · stage funnel")
    class Funnel {

        @Test
        @DisplayName("counts what entered a stage and what is still sitting there")
        void enteredAndSitting() {
            ticket("f1", "DEV");
            ticket("f2", "DEV");
            ticket("f3", "QA");
            transition("f1", "DEV", "2026-08-05", "2026-08-06");
            transition("f2", "DEV", "2026-08-05", null);
            transition("f3", "QA", "2026-08-07", null);

            List<Map<String, Object>> rows = run(StageFunnelRunner.KEY);

            // Matched on the counts, not on the label: the funnel renders a
            // stage's display name rather than its code, and the seeded
            // templates name DEV something a test should not hardcode.
            Map<String, Object> dev = rows.stream()
                    .filter(r -> Long.valueOf(2L).equals(r.get("passedThrough")))
                    .findFirst().orElseThrow();

            // Two entered DEV; both tickets are open and still sit at DEV.
            assertThat(dev.get("passedThrough")).isEqualTo(2L);
            assertThat(dev.get("sitting")).isEqualTo(2L);
        }
    }

    @Nested
    @DisplayName("12 · stage cycle time")
    class CycleTime {

        @Test
        @DisplayName("splits elapsed time into worked and waiting")
        void split() {
            ticket("c1", "DEV");
            // Sealed, 10 hours elapsed. 2 hours of effort logged against DEV.
            transition("c1", "DEV", "2026-08-05", "2026-08-06", 600);
            effort("c1", "DEV", "2026-08-05", "2.00");

            List<Map<String, Object>> rows = run(StageCycleTimeRunner.KEY);

            Map<String, Object> dev = rows.stream()
                    .filter(r -> "DEV".equals(r.get("stage")))
                    .findFirst().orElseThrow();

            assertThat(dev.get("activeHours")).hasToString("2.0");
            // 10 elapsed minus 2 worked. This is the number the report exists
            // for: the stage was slow because the ticket waited.
            assertThat(dev.get("idleHours")).hasToString("8.0");
            assertThat(dev.get("activeShare")).hasToString("20.0");
        }

        @Test
        @DisplayName("ignores visits still in progress, which would drag every average down")
        void onlySealed() {
            ticket("c2", "DEV");
            transition("c2", "DEV", "2026-08-05", "2026-08-06", 600);
            ticket("c3", "DEV");
            // Unsealed: the ticket is still there and its duration is not a fact.
            transition("c3", "DEV", "2026-08-07", null, null);

            Map<String, Object> dev = run(StageCycleTimeRunner.KEY).stream()
                    .filter(r -> "DEV".equals(r.get("stage")))
                    .findFirst().orElseThrow();

            assertThat(dev.get("visits")).isEqualTo(1L);
        }

        @Test
        @DisplayName("waiting never goes negative, even when logged effort exceeds elapsed time")
        void idleIsFloored() {
            ticket("c4", "DEV");
            // One hour in the stage, three hours logged against it — possible
            // on a stage entered twice, since effort attributes by stage code
            // rather than by visit.
            transition("c4", "DEV", "2026-08-05", "2026-08-05", 60);
            effort("c4", "DEV", "2026-08-05", "3.00");

            Map<String, Object> dev = run(StageCycleTimeRunner.KEY).stream()
                    .filter(r -> "DEV".equals(r.get("stage")))
                    .findFirst().orElseThrow();

            // A negative "waiting" would read as a bug rather than as the
            // attribution limit it actually is.
            assertThat(dev.get("idleHours")).hasToString("0.0");
            assertThat(dev.get("activeShare")).hasToString("100.0");
        }
    }

    // ── fixture ──────────────────────────────────────────────────────────────

    private long project(String code) {
        jdbc.update("INSERT INTO projects (project_code, name, status) VALUES (?, ?, 'ACTIVE')",
                code + SEQ.incrementAndGet(), "A067 IT");
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

    /** B-061 · a membership that states what it commits the person to. */
    private void memberWithAllocation(long userId, long projectId, int allocationPct) {
        jdbc.update("""
                INSERT INTO project_members (project_id, user_id, role_in_project, allocation_pct)
                VALUES (?, ?, 'DEVELOPER', ?)
                """, projectId, userId, allocationPct);
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

    private void ticket(String label, String stage) {
        jdbc.update("""
                INSERT INTO tickets (ticket_code, project_id, title, task_type_id, level, original_level,
                                     status, date_reported, reported_by, assigned_to, current_stage,
                                     estimated_effort_hrs, total_effort_hrs, current_cycle_no)
                VALUES (?, ?, 'A067 IT', ?, 'MEDIUM', 'MEDIUM', 'IN_PROGRESS',
                        '2026-08-02 09:00:00', ?, ?, ?, 1, 1, 1)
                """, label + "-" + SEQ.incrementAndGet(), myProject, taskType, me, me, stage);
        ticketIds.put(label, jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
    }

    private void transition(String label, String stage, String entered, String exited) {
        transition(label, stage, entered, exited, exited == null ? null : 600);
    }

    private void transition(String label, String stage, String entered, String exited, Integer durationMins) {
        jdbc.update("""
                INSERT INTO ticket_stage_transitions (ticket_id, cycle_no, iteration_no, seq_no, action_code,
                                                      to_stage, entered_at, exited_at, duration_mins,
                                                      is_current, prev_hash, row_hash)
                VALUES (?, 1, 1, ?, 'HANDOFF', ?, ?, ?, ?, ?, 'x', ?)
                """, ticketIds.get(label), SEQ.incrementAndGet(), stage,
                entered + " 09:00:00",
                exited == null ? null : exited + " 19:00:00",
                durationMins,
                exited == null ? 1 : 0,
                "s" + SEQ.incrementAndGet());
    }

    private void effort(String label, String stage, String workDate, String hours) {
        jdbc.update("""
                INSERT INTO ticket_effort_logs (ticket_id, cycle_no, stage_code, iteration_no,
                                                user_id, work_date, hours, prev_hash, row_hash)
                VALUES (?, 1, ?, 1, ?, ?, ?, 'x', ?)
                """, ticketIds.get(label), stage, me, workDate, new BigDecimal(hours),
                "e" + SEQ.incrementAndGet());
    }

    private void projectStat(LocalDate day, int created, int closed, int openTotal) {
        jdbc.update("""
                INSERT INTO daily_ticket_stats (stat_date, project_id, created, closed, reopened,
                                                open_total, open_critical, open_delayed, open_reopened,
                                                aging_0_2, aging_3_7, aging_8_30, aging_31_plus,
                                                computed_at)
                VALUES (?, ?, ?, ?, 0, ?, 0, 0, 0, 0, 0, 0, 0, '2026-08-20 06:00:00')
                """, day, myProject, created, closed, openTotal);
    }

    private void agingStat(LocalDate day, int b02, int b37, int b830, int b31) {
        jdbc.update("""
                INSERT INTO daily_ticket_stats (stat_date, project_id, created, closed, reopened,
                                                open_total, open_critical, open_delayed, open_reopened,
                                                aging_0_2, aging_3_7, aging_8_30, aging_31_plus,
                                                computed_at)
                VALUES (?, ?, 0, 0, 0, ?, 0, 0, 0, ?, ?, ?, ?, '2026-08-20 06:00:00')
                ON DUPLICATE KEY UPDATE aging_0_2 = VALUES(aging_0_2), aging_3_7 = VALUES(aging_3_7),
                                        aging_8_30 = VALUES(aging_8_30),
                                        aging_31_plus = VALUES(aging_31_plus),
                                        open_total = VALUES(open_total)
                """, day, myProject, b02 + b37 + b830 + b31, b02, b37, b830, b31);
    }

    private void resourceStat(LocalDate day, long userId, int assignedOpen) {
        jdbc.update("""
                INSERT INTO resource_daily_stats (stat_date, user_id, closed, effort_hours,
                                                  assigned_open, assigned_critical, assigned_delayed,
                                                  assigned_in_progress, computed_at)
                VALUES (?, ?, 0, 0, ?, 0, 0, 0, '2026-08-20 06:00:00')
                ON DUPLICATE KEY UPDATE assigned_open = VALUES(assigned_open)
                """, day, userId, assignedOpen);
    }
}
