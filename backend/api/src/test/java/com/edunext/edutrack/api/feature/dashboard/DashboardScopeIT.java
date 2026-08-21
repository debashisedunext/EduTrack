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

    /**
     * A-062 · the due counts, set on a row {@link #resourceStat} has already
     * written.
     *
     * <p>An UPDATE rather than more parameters on the insert, so that every
     * existing case keeps seeding the same row it always did and a failure in
     * one of them cannot be blamed on a wider fixture.
     */
    private void resourceDue(LocalDate day, long userId, int dueToday, int dueNext7) {
        jdbc.update("""
                UPDATE resource_daily_stats
                   SET assigned_due_today = ?, assigned_due_next_7 = ?
                 WHERE stat_date = ? AND user_id = ?
                """, dueToday, dueNext7, day, userId);
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

    // ── A-062 · §S-05's developer variant ────────────────────────────────────

    @Nested
    @DisplayName("the developer card set")
    class DeveloperVariant {

        private static final List<String> RESOURCE_KEYS =
                List.of("open", "closed", "critical", "delayed", "dueToday", "dueThisWeek");

        /**
         * The two cards that could only ever read zero are gone, and the two
         * §S-05 asks for in their place are here.
         *
         * <p>Asserted as the whole set rather than as "does not contain total",
         * because the failure being guarded against is a card <em>reappearing</em>
         * — a future edit adding the project set back for a role the resource
         * table answers — and a negative assertion on one key would not see it.
         */
        @Test
        @DisplayName("a delivery role gets the six of §S-05's variant, not the project six")
        void deliveryRoleGetsTheResourceCardSet() {
            DashboardDtos.Summary s = service.summary(
                    caller(me, "DEVELOPER", List.of(mine)), null, D1, D3, null);

            assertThat(s.cards().stream().map(DashboardDtos.Card::key))
                    .containsExactlyElementsOf(RESOURCE_KEYS);
        }

        /**
         * "Total tasks created" and "Reopened" answered <b>0</b> for every
         * delivery role on every window, for ever — creation is a reporter's act
         * and reopening a manager's, and neither is recorded per assignee. A
         * chart with no series says "nothing to show"; a KPI card reading zero
         * is a measurement, and that one was wrong.
         */
        @Test
        @DisplayName("the two cards that could only ever read zero are not shown")
        void permanentZeroesAreNotShown() {
            DashboardDtos.Summary s = service.summary(
                    caller(me, "DEVELOPER", List.of(mine)), null, D1, D3, null);

            assertThat(s.cards().stream().map(DashboardDtos.Card::key))
                    .doesNotContain("total", "reopened");
        }

        /**
         * The card set follows <b>which table answered</b>, not the role. A PM
         * who picks a resource in §S-05's filter is reading
         * {@code resource_daily_stats} too, and was getting a "Total tasks
         * created" over it that could only read zero.
         */
        @Test
        @DisplayName("a PM filtering to one resource gets the resource card set as well")
        void resourceFilterSwitchesTheCardSetForAPmToo() {
            DashboardDtos.Summary s = service.summary(
                    caller(me, "PM", List.of(mine, theirs)), null, D1, D3, colleague);

            assertThat(s.cards().stream().map(DashboardDtos.Card::key))
                    .containsExactlyElementsOf(RESOURCE_KEYS);
            assertThat(valueOf(s, "open"))
                    .as("the colleague's 60, which a PM may legitimately ask for")
                    .isEqualByComparingTo("60");
        }

        @Test
        @DisplayName("due is stock: the latest summarised day, never summed across the window")
        void dueReadsTheLatestDayOnly() {
            resourceDue(D1, me, 4, 9);
            resourceDue(D2, me, 4, 9);
            resourceDue(D3, me, 2, 7);

            DashboardDtos.Summary s = service.summary(
                    caller(me, "DEVELOPER", List.of(mine)), null, D1, D3, null);

            assertThat(valueOf(s, "dueToday"))
                    .as("three days of due counts is not ten tickets due today")
                    .isEqualByComparingTo("2");
            assertThat(valueOf(s, "dueThisWeek")).isEqualByComparingTo("7");
        }

        /**
         * The card says "due today" and the list behind it has to agree. A person
         * only earns a row on days they held or did something, so their latest
         * summarised day can sit several days behind the window's end —
         * anchoring the link on {@code to} would open a different day's work
         * under the same figure, and it would look entirely reasonable.
         */
        @Test
        @DisplayName("the due drill-downs are anchored on the day the figure was measured")
        void dueLinksUseTheMeasuredDayNotTheWindowEnd() {
            jdbc.update("DELETE FROM resource_daily_stats WHERE user_id = ? AND stat_date > ?", me, D2);
            resourceDue(D2, me, 2, 5);

            DashboardDtos.Summary s = service.summary(
                    caller(me, "DEVELOPER", List.of(mine)), null, D1, D3, null);

            assertThat(drillDownOf(s, "dueToday"))
                    .as("D2 is the last day summarised for this person; D3 is merely the window's end")
                    .contains("dueFrom=" + D2 + "&dueTo=" + D2);
            assertThat(drillDownOf(s, "dueThisWeek"))
                    .as("inclusive of the measured day, so seven days is +6")
                    .contains("dueFrom=" + D2 + "&dueTo=" + D2.plusDays(6));
        }

        @Test
        @DisplayName("every resource card names the assignee it counts")
        void everyResourceCardCarriesAssigneeId() {
            DashboardDtos.Summary s = service.summary(
                    caller(me, "DEVELOPER", List.of(mine)), null, D1, D3, null);

            assertThat(s.cards()).allSatisfy(c ->
                    assertThat(c.drillDown()).contains("assigneeId=" + me));
        }

        /**
         * A stock figure is what was true at a moment, and the tickets behind it
         * were raised whenever they were raised. Narrowing the list by the
         * window would open four rows under a card reading nine.
         */
        @Test
        @DisplayName("the stock cards do not narrow their list by the reported-date window")
        void stockCardsCarryNoReportedWindow() {
            DashboardDtos.Summary s = service.summary(
                    caller(me, "DEVELOPER", List.of(mine)), null, D1, D3, null);

            assertThat(drillDownOf(s, "open")).doesNotContain("reportedFrom=");
            assertThat(drillDownOf(s, "critical")).doesNotContain("reportedFrom=");
            assertThat(drillDownOf(s, "delayed")).doesNotContain("reportedFrom=");
        }

        /**
         * Not an oversight. A sparkline under a card is read as that card's own
         * history, and "how much was due on each of the last thirty days" is a
         * different quantity from "how much is due today"; the delta would be
         * worse still, rendering a month-apart comparison as an arrow that looks
         * like progress.
         */
        @Test
        @DisplayName("the due cards carry no delta and no sparkline")
        void dueCardsMakeNoComparison() {
            resourceDue(D3, me, 2, 7);

            DashboardDtos.Summary s = service.summary(
                    caller(me, "DEVELOPER", List.of(mine)), null, D1, D3, null);

            assertThat(cardOf(s, "dueToday").deltaPct()).isNull();
            assertThat(cardOf(s, "dueToday").sparkline()).isEmpty();
        }

        @Test
        @DisplayName("a person with nothing summarised in the window reads zero rather than failing")
        void noSummarisedDayIsZeroNotAnError() {
            jdbc.update("DELETE FROM resource_daily_stats WHERE user_id = ?", me);

            DashboardDtos.Summary s = service.summary(
                    caller(me, "DEVELOPER", List.of(mine)), null, D1, D3, null);

            assertThat(valueOf(s, "dueToday")).isEqualByComparingTo("0");
            assertThat(drillDownOf(s, "dueToday"))
                    .as("falls back to the window's end, since there is no measured day to name")
                    .contains("dueFrom=" + D3);
        }
    }

    private static DashboardDtos.Card cardOf(DashboardDtos.Summary s, String key) {
        return s.cards().stream().filter(c -> c.key().equals(key)).findFirst().orElseThrow();
    }

    private static String drillDownOf(DashboardDtos.Summary s, String key) {
        return cardOf(s, key).drillDown();
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

        /**
         * A-077 · this test used to assert the figures came back as <b>zeroes</b>,
         * and it was renamed rather than deleted because the property it was
         * written to protect is still the important one and still holds.
         *
         * <p>What it proves is that the filter cannot <em>widen</em> scope: a PM
         * naming somebody else's project does not receive that project's
         * numbers. That was true before and is true now, and it is the security
         * property.
         *
         * <p>What changed is how the absence is expressed. Zero is a
         * measurement — "this project has no open tickets" — and it is false
         * about a project holding a hundred of them. The old assertion was
         * therefore pinning the defect A-077 exists to remove, which is why the
         * change broke it: exactly the signal a pinned expectation is for.
         *
         * <p>Both halves are asserted below, because dropping either would lose
         * something. Without the first, a refusal that leaked the real figures
         * would pass; without the second, a silent return to zeroes would.
         */
        @Test
        @DisplayName("a PM asking for a project they do not hold is told why, and gets no figures")
        void filterCannotWidenScope() {
            DashboardDtos.Summary s = service.summary(
                    caller(me, "PM", List.of(mine)), theirs, D1, D3, null);

            // The security property, unchanged: none of the other project's 100
            // open tickets reaches this caller.
            assertThat(s.cards()).isEmpty();

            // The A-077 property: the absence is stated rather than drawn as a
            // number somebody would read as a measurement.
            assertThat(s.unavailableReason()).isEqualTo(WidgetService.NOT_YOUR_PROJECT);

            // Nothing was read, so there is no recompute time to report — a
            // timestamp beside a refusal would suggest figures were fetched and
            // came back empty.
            assertThat(s.asOf()).isNull();
        }

        @Test
        @DisplayName("a PM asking for a project they DO hold still gets its figures")
        void ownProjectFilterStillWorks() {
            // The complement, and it is not redundant: a gate that refused every
            // ?projectId= would pass the test above and break the screen this
            // task exists to build.
            DashboardDtos.Summary s = service.summary(
                    caller(me, "PM", List.of(mine)), mine, D1, D3, null);

            assertThat(s.unavailableReason()).isNull();
            assertThat(valueOf(s, "open")).isEqualByComparingTo("5");
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
                // A-060 · named in full. The old assertion was `contains("from="
                // + D1)`, which is a substring of `reportedFrom=` and therefore
                // passed identically before and after the rename — it could
                // never have caught the parameter the list did not implement.
                assertThat(c.drillDown()).contains("reportedFrom=" + D1, "reportedTo=" + D3);
            });
        }

        @Test
        @DisplayName("every card carries one sparkline point per summarised day")
        void sparklineHasAPointPerDay() {
            DashboardDtos.Summary s = service.summary(
                    caller(me, "PM", List.of(mine)), null, D1, D3, null);

            assertThat(s.cards()).allSatisfy(c ->
                    assertThat(c.sparkline()).as("three days seeded").hasSize(3));
        }

        /**
         * The comparison has no denominator, so there is no percentage to state.
         * "+100%" would be a confident green arrow on a number that means
         * nothing — a team that closed nothing last week and eleven this week
         * has not improved by a hundred per cent.
         */
        @Test
        @DisplayName("deltaPct is null when the previous window held nothing")
        void deltaIsNullWithNothingToCompareAgainst() {
            DashboardDtos.Summary s = service.summary(
                    caller(me, "PM", List.of(mine)), null, D1, D3, null);

            assertThat(valueOf(s, "closed")).isEqualByComparingTo("3");
            assertThat(s.cards().stream().filter(c -> c.key().equals("closed")).findFirst()
                    .orElseThrow().deltaPct())
                    .as("nothing was summarised in the three days before D1")
                    .isNull();
        }

        /**
         * Like against like: a three-day window compares with the three days
         * before it, never with a fixed "last month". Otherwise a Monday-to-
         * Friday view would read as a collapse whenever it was opened on a
         * Saturday.
         */
        @Test
        @DisplayName("deltaPct compares against the preceding window of equal length")
        void deltaComparesEqualWindows() {
            // Three days immediately before D1, closing 1 per day — same as the
            // window itself, so the delta is exactly zero rather than absent.
            for (int back = 1; back <= 3; back++) {
                projectStat(D1.minusDays(back), mine, 2, 1, 0, 5, 1, 1, 1);
            }

            DashboardDtos.Summary s = service.summary(
                    caller(me, "PM", List.of(mine)), null, D1, D3, null);

            assertThat(s.cards().stream().filter(c -> c.key().equals("closed")).findFirst()
                    .orElseThrow().deltaPct())
                    .as("3 closed against 3 closed")
                    .isEqualByComparingTo("0.0");
        }
    }
}
