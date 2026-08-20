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
    private long myClient;
    private long otherClient;

    /** Ticket ids by the label this fixture used, so effort attaches to the right row. */
    private final Map<String, Long> ticketIds = new HashMap<>();

    @BeforeEach
    void seed() {
        ticketIds.clear();

        myProject = project("RNA");
        // A-070 · its own project, so the four-quadrant assertions read one row
        // rather than sharing a table with six other nested classes' fixtures.
        criticalProject = project("RNC");
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

        /*
          B-060 · clients are *attached to the existing tickets* rather than
          seeded with tickets of their own. Every assertion above counts rows
          this fixture already produces, and four more tickets would have moved
          six reports' numbers to test a seventh. `client_id` is nullable and no
          other query in this package joins `clients`, so attaching changes
          nothing for them.

          t4 is deliberately left with no client: it is the internally-raised
          ticket the client report must drop, and without one here the JOIN and
          a LEFT JOIN would give identical answers.
        */
        myClient = client("RN-A", "Ariadne Systems");
        otherClient = client("RN-B", "Borealis Freight");
        attachClient(myClient, "t1", "t2", "t3");
        for (int i = 0; i < 20; i++) {
            attachClient(otherClient, "other" + i);
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

    /**
     * A-070's callers, scoped to its own project.
     *
     * <p>Reusing {@link #pm()} here returns nothing and looks like a broken
     * query: it is scoped to {@code myProject}, and {@code ReportScope} narrows
     * a requested project to the caller's own rather than widening to it — so
     * asking for {@code criticalProject} as that PM intersects to empty, which
     * is the guard working exactly as intended.
     */
    private CallerIdentity criticalPm() {
        return new CallerIdentity(2L, "PM", List.of(criticalProject));
    }

    private CallerIdentity criticalDeveloper() {
        return new CallerIdentity(me, "DEVELOPER", List.of(criticalProject));
    }

    /** Bounded to this test's project — see the class note on isolation. */
    private List<Map<String, Object>> run(CallerIdentity caller, String key) {
        return run(caller, key, myProject);
    }

    private List<Map<String, Object>> run(CallerIdentity caller, String key, Long projectId) {
        return run(caller, key, projectId, null);
    }

    /** B-061 · the same call with {@code ?resourceId=} actually set. */
    private List<Map<String, Object>> run(CallerIdentity caller, String key, Long projectId,
                                          Long resourceId) {
        return service.run(caller, key, FROM, TO, projectId, resourceId, ReportFilters.NONE)
                .orElseThrow().report().rows();
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

    @Nested
    @DisplayName("13 · client report (B-060)")
    class ClientReport {

        private List<Map<String, Object>> forClient(Long clientId) {
            return service.run(pm(), ClientReportRunner.KEY, FROM, TO, myProject, null,
                            new ReportFilters(clientId, null, null))
                    .orElseThrow().report().rows();
        }

        @Test
        @DisplayName("counts raised, closed and still-open as three separate populations")
        void volumes() {
            List<Map<String, Object>> rows = forClient(null);

            // One row: the PM's scope is myProject, and the other client's
            // twenty tickets live in otherProject.
            assertThat(rows).hasSize(1);
            assertThat(cell(rows, 0, "client")).isEqualTo("Ariadne Systems");
            // t1, t2, t3 — all raised in the window. t4 has no client and is
            // absent, which is the JOIN doing its job.
            assertThat(cell(rows, 0, "raised")).isEqualTo(3L);
            assertThat(cell(rows, 0, "closed")).isEqualTo(3L);
            // Stock, not flow: nothing of this client's is still open, and the
            // one open ticket in the fixture (t4) belongs to no client.
            assertThat(cell(rows, 0, "openNow")).isEqualTo(0L);
        }

        @Test
        @DisplayName("SLA compliance divides met by committed, not by closed")
        void slaRate() {
            // t1 and t2 closed on or before their planned date; t3 closed six
            // days late. All three carried a planned_close_date, so the
            // denominator is three and not the four tickets in the project.
            assertThat(cell(forClient(null), 0, "slaPct")).hasToString("66.7");
        }

        @Test
        @DisplayName("the client filter narrows, and an out-of-scope client returns nothing")
        void filtered() {
            assertThat(forClient(myClient)).hasSize(1);

            // The other client exists and has twenty tickets, none of them on a
            // project this PM can see. Empty rather than 403 — there is no row
            // to deny, which is the shape §7 of the conventions asks for.
            assertThat(forClient(otherClient)).isEmpty();
        }

        @Test
        @DisplayName("an Admin reaches the other client, where the PM could not")
        void adminSeesBoth() {
            List<Map<String, Object>> rows = service
                    .run(admin(), ClientReportRunner.KEY, FROM, TO, otherProject, null,
                            new ReportFilters(otherClient, null, null))
                    .orElseThrow().report().rows();

            assertThat(rows).hasSize(1);
            assertThat(cell(rows, 0, "raised")).isEqualTo(20L);
        }

        @Test
        @DisplayName("declares no satisfaction column, because no rating is recorded")
        void noSatisfactionColumn() {
            List<ReportDtos.Column> columns = service
                    .run(pm(), ClientReportRunner.KEY, FROM, TO, myProject, null, ReportFilters.NONE)
                    .orElseThrow().report().columns();

            // §7.8 names five figures and the schema holds four. Blueprint §17
            // item 19 puts CSAT in phase 2–3, so there is nothing to average.
            // Asserted rather than assumed: a later task adding the rating should
            // find a failing test naming exactly what it now makes possible.
            assertThat(columns).extracting(ReportDtos.Column::key)
                    .doesNotContain("satisfaction", "csat", "rating");
        }

        @Test
        @DisplayName("the client cell carries a drill-in, and the id it links by is not a column")
        void drillsIntoClient360() {
            ReportService.Rendered rendered = service
                    .run(pm(), ClientReportRunner.KEY, FROM, TO, myProject, null, ReportFilters.NONE)
                    .orElseThrow();

            ReportDtos.Column client = rendered.report().columns().stream()
                    .filter(c -> c.key().equals("client")).findFirst().orElseThrow();

            assertThat(client.linkTo()).isEqualTo(ReportEntityKind.CLIENT);
            assertThat(client.linkIdKey()).isEqualTo("clientId");

            // The row carries the id the column names — a link kind whose key is
            // missing from the row renders a dead anchor, which is harder to
            // notice than a missing one.
            assertThat(rendered.report().rows().get(0)).containsKey("clientId");

            // And `clientId` has no column of its own, so it stays out of the
            // table and out of ?export=, which iterates columns. An internal id
            // is not a figure to send a client.
            assertThat(rendered.report().columns()).extracting(ReportDtos.Column::key)
                    .doesNotContain("clientId");
        }

        @Test
        @DisplayName("a delivery role cannot run it at all — a client is not a person's work")
        void notAnsweredPerPerson() {
            // In NOT_KEPT_PER_PERSON since A-063. Asserted here because B-060
            // flipping the card to `built` is exactly the change that could have
            // made it runnable by a Developer for the first time.
            assertThat(service.run(developer(), ClientReportRunner.KEY, FROM, TO,
                    myProject, null, ReportFilters.NONE)).isEmpty();
        }
    }

    /**
     * B-061 · the Resource filter, which four of these reports declared and none
     * of them applied.
     *
     * <h2>What was wrong</h2>
     *
     * <p>{@link ReportService} resolved {@code ?resourceId=} into a subject and
     * handed it to the ETag and to {@code meta.appliedScope} — and never to the
     * runner. Every runner re-derived it as {@code scope.resourceSubject(null)},
     * which answers null for everybody who is not a delivery role. So an Admin
     * or a PM — the only callers the control exists for — picked a person and
     * got the whole report back, under a line reading <i>"showing one resource,
     * across all projects"</i>.
     *
     * <p>That is worse than an inert control. A filter that is absent asks no
     * question; this one answered, in words, that it had narrowed rows it had
     * not touched. The ETag varied by a parameter the body did not depend on,
     * which is the same defect from the caching side.
     *
     * <h2>Why the assertions are shaped as they are</h2>
     *
     * <p>Each case asks for a project holding exactly one person's work and
     * filters to the <em>other</em> person. Before B-061 every one of these
     * returned the resident's rows; the empty result is only reachable if the
     * parameter reaches SQL. Asserting the positive case alone would have passed
     * against the bug, because filtering to the only person present is
     * indistinguishable from not filtering at all — which is precisely how this
     * survived A-066 and A-067.
     */
    @Nested
    @DisplayName("B-061 · the resource filter")
    class ResourceFilter {

        @Test
        @DisplayName("the scorecard narrows to the person asked for")
        void scorecard() {
            // otherProject holds only the colleague's twenty.
            assertThat(run(admin(), ResourceScorecardRunner.KEY, otherProject, colleague)).hasSize(1);
            assertThat(cell(run(admin(), ResourceScorecardRunner.KEY, otherProject, colleague), 0, "closed"))
                    .isEqualTo(20L);

            // Before B-061 this returned the colleague's row too.
            assertThat(run(admin(), ResourceScorecardRunner.KEY, otherProject, me)).isEmpty();
        }

        @Test
        @DisplayName("effort summary narrows to the person asked for")
        void effortSummary() {
            assertThat(run(admin(), EffortSummaryRunner.KEY, otherProject, colleague)).isNotEmpty();
            assertThat(run(admin(), EffortSummaryRunner.KEY, otherProject, me)).isEmpty();
        }

        @Test
        @DisplayName("reopen analysis narrows to the person asked for")
        void reopenAnalysis() {
            // t3 is mine and reopened twice; nothing in myProject is the
            // colleague's, so filtering to them empties a report that has rows.
            assertThat(run(admin(), ReopenAnalysisRunner.KEY, myProject, me)).isNotEmpty();
            assertThat(run(admin(), ReopenAnalysisRunner.KEY, myProject, colleague)).isEmpty();
        }

        @Test
        @DisplayName("velocity narrows to the person asked for")
        void velocity() {
            resourceStat(LocalDate.of(2026, 8, 10), me, 4, "9.0");
            resourceStat(LocalDate.of(2026, 8, 10), colleague, 9, "20.0");

            // An Admin, unbounded by project, so both people are in range and
            // the filter is the only thing that can separate them.
            List<Map<String, Object>> justMine = run(admin(), ResourceVelocityRunner.KEY, null, me);

            assertThat(justMine).isNotEmpty();
            assertThat(justMine).allSatisfy(row ->
                    assertThat(row.get("closed")).isEqualTo(4L));
        }

        /**
         * The half that has to keep working now that the parameter is live.
         *
         * <p>Making an inert parameter live is exactly the change that can open
         * what §2 withholds. {@link ReportScope#resourceSubject} overrules a
         * delivery role's request <em>before</em> {@link ReportService} passes
         * it on, so a Developer naming a colleague's id reads their own rows.
         * Asserted on the figure and not merely on the row count: one row is
         * what both the correct and the leaking answer return here.
         */
        @Test
        @DisplayName("a delivery role naming a colleague still reads their own rows")
        void aDeliveryRoleCannotAimIt() {
            List<Map<String, Object>> rows =
                    run(developer(), ResourceScorecardRunner.KEY, myProject, colleague);

            assertThat(rows).hasSize(1);
            // Mine, not the colleague's twenty.
            assertThat(cell(rows, 0, "closed")).isEqualTo(3L);
        }
    }


    @Nested
    @DisplayName("A-070 · born critical vs became critical")
    class CriticalOrigin {

        /**
         * The four quadrants of {@code original_level} against {@code level},
         * asserted together on one row.
         *
         * <p>Together rather than in four tests, because the mistake worth
         * catching is a predicate that overlaps another — counting a
         * de-escalated ticket as both born and became, say — and that is
         * invisible unless the four numbers are read against the same fixture
         * at the same time.
         */
        @Test
        @DisplayName("separates arrived-critical from raised-to-critical, and counts both ways back")
        void theFourQuadrants() {
            seedCriticalCohort();

            List<Map<String, Object>> rows = run(criticalPm(), CriticalOriginRunner.KEY, criticalProject);

            assertThat(rows).hasSize(1);
            assertThat(cell(rows, 0, "tickets")).isEqualTo(5L);
            // born:    arrived CRITICAL, whatever it is now — c1 and c4
            assertThat(cell(rows, 0, "bornCritical")).isEqualTo(2L);
            // became:  arrived lower and is CRITICAL now — c2 and c3
            assertThat(cell(rows, 0, "becameCritical")).isEqualTo(2L);
            // de-esc:  arrived CRITICAL and is not now — c4 alone
            assertThat(cell(rows, 0, "deEscalated")).isEqualTo(1L);
            // now:     c1 (born, still) + c2 + c3 (became) = 3
            assertThat(cell(rows, 0, "criticalNow")).isEqualTo(3L);

            // The identity the report prints and a reader can check on screen.
            assertThat((Long) cell(rows, 0, "criticalNow"))
                    .isEqualTo((Long) cell(rows, 0, "bornCritical")
                            - (Long) cell(rows, 0, "deEscalated")
                            + (Long) cell(rows, 0, "becameCritical"));
        }

        /**
         * 🔴 The attribution, which is the half that carries the management
         * insight.
         *
         * <p>"Became critical" is mostly self-inflicted — §6 raises a ticket
         * when its Planned Close Date passes — and separating the scanner's
         * escalations from a person's decisions is what distinguishes "we ran
         * late" from "somebody judged this urgent". A report that lumped them
         * would show a number nobody can act on.
         */
        @Test
        @DisplayName("splits became-critical into the scanner's doing and a person's")
        void attribution() {
            seedCriticalCohort();

            List<Map<String, Object>> rows = run(criticalPm(), CriticalOriginRunner.KEY, criticalProject);

            // c2 was escalated by the SLA scanner, c3 raised by a manager.
            assertThat(cell(rows, 0, "escalatedBySla")).isEqualTo(1L);
            assertThat(cell(rows, 0, "raisedByPerson")).isEqualTo(1L);
            // Both of this fixture's became-critical tickets carry a history
            // row, so nothing is left unattributed.
            assertThat(cell(rows, 0, "unrecorded")).isEqualTo(0L);
            // The three partition becameCritical by construction — two counted
            // from one mutually-exclusive expression and the third derived —
            // so this can never disagree with its own total.
            assertThat((Long) cell(rows, 0, "escalatedBySla")
                    + (Long) cell(rows, 0, "raisedByPerson")
                    + (Long) cell(rows, 0, "unrecorded"))
                    .isEqualTo(cell(rows, 0, "becameCritical"));
        }

        /**
         * 🔴 A ticket can cross CRITICAL more than once.
         *
         * <p>Escalated by the scanner, downgraded by a manager, raised again by
         * hand: {@code EXISTS (… actor_type = 'SYSTEM')} would call that an SLA
         * escalation, which is what it was two changes ago and not what it is.
         * The query takes the most recent row that set CRITICAL, so this
         * belongs to the person who last raised it.
         */
        @Test
        @DisplayName("attribution follows the latest change to critical, not the first")
        void attributionFollowsTheLatestChange() {
            long tt = taskType("Rework");
            criticalTicket("cx", criticalProject, tt, "LOW", "CRITICAL", "2026-08-03");
            // The scanner got there first…
            levelChange("cx", "LOW", "CRITICAL", "SYSTEM", null, "2026-08-05 10:00:00");
            // …a manager stood it down…
            levelChange("cx", "CRITICAL", "MEDIUM", "USER", 2L, "2026-08-06 10:00:00");
            // …and then raised it again by hand. That is who it belongs to.
            levelChange("cx", "MEDIUM", "CRITICAL", "USER", 2L, "2026-08-07 10:00:00");

            List<Map<String, Object>> rows = run(criticalPm(), CriticalOriginRunner.KEY, criticalProject);

            assertThat(rows).hasSize(1);
            assertThat(cell(rows, 0, "becameCritical")).isEqualTo(1L);
            assertThat(cell(rows, 0, "escalatedBySla"))
                    .as("the SYSTEM row is older than the USER one that set it where it stands")
                    .isEqualTo(0L);
            assertThat(cell(rows, 0, "raisedByPerson")).isEqualTo(1L);
        }

        /**
         * 🔴 A became-critical ticket with no history row is its own answer.
         *
         * <p>The first version made this the remainder of "escalated by the
         * scanner" and labelled it "raised by a person" — sound arithmetic and
         * a false label. Running against the B-007 corpus made it plain: 77 of
         * its tickets are critical without having arrived that way and only 7
         * carry a {@code LEVEL_CHANGED} row, so seventy would have been
         * reported as somebody's decision when nothing recorded one.
         *
         * <p>It should be zero in production — every real change is journalled
         * by {@code PriorityChangeController} or {@code SlaEscalation} — which
         * is precisely why a number there is worth seeing rather than
         * absorbing into a column that names an actor.
         */
        @Test
        @DisplayName("with no history row at all, the change is reported as unrecorded")
        void unattributedIsItsOwnColumn() {
            long tt = taskType("Silent");
            criticalTicket("cy", criticalProject, tt, "LOW", "CRITICAL", "2026-08-03");

            List<Map<String, Object>> rows = run(criticalPm(), CriticalOriginRunner.KEY, criticalProject);

            assertThat(cell(rows, 0, "becameCritical")).isEqualTo(1L);
            assertThat(cell(rows, 0, "escalatedBySla")).isEqualTo(0L);
            // Not attributed to anybody, because nobody is recorded.
            assertThat(cell(rows, 0, "raisedByPerson")).isEqualTo(0L);
            assertThat(cell(rows, 0, "unrecorded")).isEqualTo(1L);
        }

        /**
         * The share is against what is critical <em>now</em>, not against the
         * cohort — "how much of our critical load did we create", not "what
         * fraction of all work escalated". The second moves when quiet work is
         * added, which has nothing to do with escalation.
         */
        @Test
        @DisplayName("the share is of the current critical load, not of every ticket raised")
        void shareIsOfTheCriticalLoad() {
            seedCriticalCohort();

            List<Map<String, Object>> rows = run(criticalPm(), CriticalOriginRunner.KEY, criticalProject);

            // 2 became out of 3 critical now = 66.7%, not 2 of 5 raised = 40%.
            assertThat(cell(rows, 0, "becameShare")).hasToString("66.7");
        }

        /**
         * A row where nothing was ever critical is every other project and
         * type, and printing them would bury the ones that matter —
         * {@code ReopenAnalysisRunner} omits its quiet rows for the same
         * reason.
         */
        @Test
        @DisplayName("a project with nothing critical is left out entirely")
        void quietRowsAreOmitted() {
            long tt = taskType("Calm");
            criticalTicket("cq", criticalProject, tt, "LOW", "LOW", "2026-08-03");
            criticalTicket("cq2", criticalProject, tt, "MEDIUM", "MEDIUM", "2026-08-04");

            List<Map<String, Object>> rows = run(criticalPm(), CriticalOriginRunner.KEY, criticalProject);

            assertThat(rows).isEmpty();
        }

        /**
         * The cohort is the reported-date window, for both halves. A ticket
         * raised before it does not appear even though it became critical
         * inside it — one denominator, one comparable share, and the lag is
         * stated in the report's own description rather than hidden.
         */
        @Test
        @DisplayName("the window is the reporting date, so an older ticket that escalated is out")
        void cohortIsTheReportedWindow() {
            long tt = taskType("Older");
            // Reported well before FROM, escalated inside the window.
            criticalTicket("cold", criticalProject, tt, "LOW", "CRITICAL", "2026-05-01");
            levelChange("cold", "LOW", "CRITICAL", "SYSTEM", null, "2026-08-05 10:00:00");

            assertThat(run(criticalPm(), CriticalOriginRunner.KEY, criticalProject)).isEmpty();
        }

        /**
         * §2's row rule, through a report that has no per-person table behind
         * it. A delivery role reads their own tickets and nobody else's.
         */
        @Test
        @DisplayName("a delivery role sees only the tickets assigned to them")
        void deliveryRolesSeeOnlyTheirOwn() {
            long tt = taskType("Scoped");
            criticalTicketFor("cme", criticalProject, tt, "LOW", "CRITICAL", "2026-08-03", me);
            criticalTicketFor("cthem", criticalProject, tt, "LOW", "CRITICAL", "2026-08-03", colleague);

            List<Map<String, Object>> asPm = run(criticalPm(), CriticalOriginRunner.KEY, criticalProject);
            List<Map<String, Object>> asDev = run(criticalDeveloper(), CriticalOriginRunner.KEY, criticalProject);

            assertThat(cell(asPm, 0, "becameCritical")).isEqualTo(2L);
            assertThat(cell(asDev, 0, "becameCritical"))
                    .as("their own, not the colleague's")
                    .isEqualTo(1L);
        }
    }
    // ── fixture ────────────────────────────────────────────────────

    private long client(String code, String name) {
        jdbc.update("INSERT INTO clients (client_code, name, status) VALUES (?, ?, 'ACTIVE')",
                code + SEQ.incrementAndGet(), name);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    /** Attaches by the fixture's own label, since ticket codes carry a sequence suffix. */
    private void attachClient(long clientId, String... labels) {
        for (String label : labels) {
            jdbc.update("UPDATE tickets SET client_id = ? WHERE id = ?", clientId, ticketIds.get(label));
        }
    }

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

    // ── A-070 fixture ────────────────────────────────────────────────────────

    /**
     * Its own project, so the four-quadrant assertions read one row.
     *
     * <p>The shared {@code ticket()} helper writes every ticket at MEDIUM/MEDIUM
     * and is depended on by six other nested classes; A-070 needs the two level
     * columns to differ, so it has its own writer rather than a seventh
     * parameter on that one.
     */
    private long criticalProject;

    private void criticalTicket(String label, long projectId, long taskTypeId,
                                String originalLevel, String level, String reported) {
        criticalTicketFor(label, projectId, taskTypeId, originalLevel, level, reported, me);
    }

    private void criticalTicketFor(String label, long projectId, long taskTypeId,
                                   String originalLevel, String level, String reported,
                                   long assignee) {
        String code = label.toUpperCase(java.util.Locale.ROOT) + "-" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO tickets (ticket_code, project_id, title, task_type_id, level, original_level,
                                     status, date_reported, reported_by, assigned_to, current_cycle_no)
                VALUES (?, ?, 'Critical origin IT', ?, ?, ?, 'IN_PROGRESS', ?, ?, ?, 1)
                """, code, projectId, taskTypeId, level, originalLevel,
                reported + " 09:00:00", assignee, assignee);
        ticketIds.put(label, jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
    }

    /**
     * One {@code LEVEL_CHANGED} row, written straight in.
     *
     * <p>Hashes are placeholders, as {@code effort()} above does: the journal's
     * chain is A-042's subject and this report reads {@code actor_type},
     * {@code new_value} and the ordering — nothing that a real hash changes.
     *
     * <p>{@code created_at} is set explicitly rather than defaulted, because the
     * attribution turns on <em>which</em> row is most recent and a default would
     * make three rows written in one statement share a timestamp and fall back
     * on insertion order.
     */
    private void levelChange(String label, String from, String to, String actorType,
                             Long actorId, String at) {
        jdbc.update("""
                INSERT INTO ticket_history (ticket_id, cycle_no, event_type, field_name,
                                            old_value, new_value, actor_id, actor_type,
                                            created_at, prev_hash, row_hash)
                VALUES (?, 1, 'LEVEL_CHANGED', 'level', ?, ?, ?, ?, ?, 'x', ?)
                """, ticketIds.get(label), from, to, actorId, actorType, at,
                "h" + SEQ.incrementAndGet());
    }

    /**
     * Five tickets covering all four quadrants, plus one that was never
     * critical at all so the cohort is larger than the critical set.
     *
     * <pre>
     *   c1  CRITICAL → CRITICAL   born, still critical
     *   c2  LOW      → CRITICAL   became, by the SLA scanner
     *   c3  MEDIUM   → CRITICAL   became, raised by a person
     *   c4  CRITICAL → MEDIUM     de-escalated
     *   c5  LOW      → LOW        never critical — the cohort denominator
     * </pre>
     */
    private void seedCriticalCohort() {
        long tt = taskType("Escalating");
        criticalTicket("c1", criticalProject, tt, "CRITICAL", "CRITICAL", "2026-08-02");
        criticalTicket("c2", criticalProject, tt, "LOW", "CRITICAL", "2026-08-02");
        criticalTicket("c3", criticalProject, tt, "MEDIUM", "CRITICAL", "2026-08-03");
        criticalTicket("c4", criticalProject, tt, "CRITICAL", "MEDIUM", "2026-08-03");
        criticalTicket("c5", criticalProject, tt, "LOW", "LOW", "2026-08-04");

        levelChange("c2", "LOW", "CRITICAL", "SYSTEM", null, "2026-08-06 10:00:00");
        levelChange("c3", "MEDIUM", "CRITICAL", "USER", 2L, "2026-08-06 11:00:00");
        levelChange("c4", "CRITICAL", "MEDIUM", "USER", 2L, "2026-08-06 12:00:00");
    }
}
