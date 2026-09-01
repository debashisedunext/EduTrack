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
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Dashboard Rework Dev 2, PR 12 · {@link WeeklyProgressService} against a
 * real database — the {@code TodayProgressIT}/{@code OverviewIT} pattern
 * applied to {@code GET /dashboard/weekly}.
 *
 * <p>Summary rows are inserted directly rather than produced by the worker,
 * for {@code TodayProgressIT}'s stated reason: what is under test is the read
 * path, the day-selection rules and the delta arithmetic, not the refresh.
 * {@code StatsRefreshIT} already proves the worker writes these columns.
 *
 * <h2>Which day inside the week each card reads is the thing worth pinning</h2>
 *
 * <p>Three of the four cards read three <em>different</em> days of the same
 * week — the latest summarised day, the Monday, and the latest day carrying a
 * measured progress figure. Seeding a week whose days disagree is the only way
 * an assertion can tell them apart; a week of identical rows would pass
 * whichever day the service picked.
 */
@SpringBootTest
@Testcontainers
class WeeklyProgressIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_weekly_it")
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
    WeeklyStatsRepository weeklyRepo;

    @Autowired
    DashboardRepository dashboardRepo;

    @Autowired
    JdbcTemplate jdbc;

    /** Monday 31 Aug 2026 … Sunday 6 Sep. TODAY sits inside it, on the Wednesday. */
    private static final LocalDate WEEK = LocalDate.of(2026, 8, 31);
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 2);
    private static final LocalDate PRIOR_WEEK = WEEK.minusDays(7);
    private static final Clock CLOCK =
            Clock.fixed(TODAY.atTime(6, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    private WeeklyProgressService service;

    private long mine;
    private long theirs;
    private long dev;
    private long pm;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @BeforeEach
    void setUp() {
        service = new WeeklyProgressService(weeklyRepo, dashboardRepo, CLOCK);

        jdbc.update("DELETE FROM daily_ticket_stats");
        jdbc.update("DELETE FROM resource_daily_stats");
        jdbc.update("DELETE FROM project_members");

        mine = project("WKA");
        theirs = project("WKB");
        dev = user("wk.dev", "DEVELOPER");
        pm = user("wk.pm", "PM");
        member(mine, dev);
        member(mine, pm);
    }

    private long project(String code) {
        jdbc.update("INSERT INTO projects (project_code, name, status) VALUES (?, ?, 'ACTIVE')",
                code + SEQ.incrementAndGet(), "Weekly IT");
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long user(String name, String roleCode) {
        Long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code = ?", Long.class, roleCode);
        String u = name + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id, is_active)
                VALUES (?, ?, ?, 'x', ?, ?, 1)
                """, u, u, u + "@example.test", "Weekly " + name, roleId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void member(long projectId, long userId) {
        jdbc.update("INSERT INTO project_members (project_id, user_id, is_active) VALUES (?, ?, 1)",
                projectId, userId);
    }

    /** @param openPctSum null models a day the worker never measured — most days. */
    private void projectStat(LocalDate day, long projectId, long created, long closed, long openTotal,
                             long openDelayed, Long openPctSum, long delayDaysSum, long openDueNext7) {
        jdbc.update("""
                INSERT INTO daily_ticket_stats (
                    stat_date, project_id, created, closed, open_total, open_delayed,
                    open_pct_sum, delay_days_sum, open_due_next_7, computed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, '2026-09-02 06:00:00')
                """, day, projectId, created, closed, openTotal, openDelayed, openPctSum,
                delayDaysSum, openDueNext7);
    }

    private void resourceStat(LocalDate day, long userId, long closed, long assignedOpen, long assignedDelayed,
                              long assignedDueNext7, long pctSum, long delayDaysSum) {
        jdbc.update("""
                INSERT INTO resource_daily_stats (
                    stat_date, user_id, closed, assigned_open, assigned_delayed, assigned_due_next_7,
                    pct_sum, delay_days_sum, computed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, '2026-09-02 06:00:00')
                """, day, userId, closed, assignedOpen, assignedDelayed, assignedDueNext7, pctSum, delayDaysSum);
    }

    private static CallerIdentity caller(long userId, String role, List<Long> projects) {
        return new CallerIdentity(userId, role, projects);
    }

    // ── request validation ────────────────────────────────────────────────

    @Nested
    @DisplayName("weekStart validation")
    class Validation {

        @Test
        @DisplayName("a weekStart that is not a Monday is refused, not silently shifted")
        void nonMondayIsRefused() {
            assertThatThrownBy(() ->
                    service.weekly(caller(pm, "PM", List.of(mine)), null, WEEK.plusDays(2), null))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("400")
                    .hasMessageContaining("Monday");
        }

        @Test
        @DisplayName("no weekStart defaults to the Monday of the week containing today")
        void defaultsToCurrentWeeksMonday() {
            DashboardWeeklyDtos.DashboardWeeklyData data =
                    service.weekly(caller(pm, "PM", List.of(mine)), null, null, null);

            assertThat(data.weekStart()).isEqualTo(WEEK);
            assertThat(data.weekEnd()).as("the Sunday, inclusive").isEqualTo(WEEK.plusDays(6));
        }
    }

    // ── project-keyed ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("project-keyed cards")
    class ProjectKeyed {

        @Test
        @DisplayName("each card reads its own day of the week, not one shared day")
        void eachCardReadsItsOwnDay() {
            // Monday: the due window the "due this week" card must read.
            projectStat(WEEK, mine, 0, 0, 10, 1, null, 1, 18);
            // Tuesday: the only day carrying a measured progress figure.
            projectStat(WEEK.plusDays(1), mine, 0, 0, 10, 2, 540L, 2, 99);
            // Wednesday (TODAY): the latest summarised day — delayed reads here.
            projectStat(TODAY, mine, 0, 0, 20, 7, null, 21, 99);

            DashboardWeeklyDtos.DashboardWeeklyData data =
                    service.weekly(caller(pm, "PM", List.of(mine)), null, WEEK, null);

            assertThat(cardValue(data, "due-this-week"))
                    .as("Monday's open_due_next_7, not another day's straddling window").isEqualTo(18);
            assertThat(cardValue(data, "delayed-vs-last-week"))
                    .as("the latest summarised day's open_delayed").isEqualTo(7);
            assertThat(cardValue(data, "avg-progress"))
                    .as("Tuesday's 540 over its own 10 open — the only measured day").isEqualTo(54.0);
            assertThat(cardValue(data, "avg-delay-days"))
                    .as("21 delay-days over 7 delayed tickets, on the latest day").isEqualTo(3.0);
        }

        @Test
        @DisplayName("avg-progress is omitted entirely when no day of the week was measured")
        void avgProgressOmittedWhenUnmeasured() {
            projectStat(WEEK, mine, 0, 0, 10, 2, null, 4, 5);
            projectStat(TODAY, mine, 0, 0, 10, 2, null, 4, 5);

            DashboardWeeklyDtos.DashboardWeeklyData data =
                    service.weekly(caller(pm, "PM", List.of(mine)), null, WEEK, null);

            assertThat(data.cards()).extracting(DashboardWeeklyDtos.WeeklyCard::key)
                    .as("a fabricated 0% would claim no progress was made")
                    .doesNotContain("avg-progress")
                    .containsExactly("due-this-week", "delayed-vs-last-week", "avg-delay-days");
        }

        @Test
        @DisplayName("the progress delta is in percentage points against the same week seven days earlier")
        void progressDeltaIsInPoints() {
            projectStat(WEEK.plusDays(1), mine, 0, 0, 10, 0, 540L, 0, 0);
            projectStat(PRIOR_WEEK.plusDays(1), mine, 0, 0, 10, 0, 480L, 0, 0);

            DashboardWeeklyDtos.DashboardWeeklyData data =
                    service.weekly(caller(pm, "PM", List.of(mine)), null, WEEK, null);

            assertThat(cardValue(data, "avg-progress")).isEqualTo(54.0);
            assertThat(card(data, "avg-progress").deltaPct())
                    .as("54% against 48% is six POINTS, never +12.5%")
                    .isEqualTo(6.0);
        }

        @Test
        @DisplayName("a week with no prior data shows no delta rather than a fabricated zero")
        void noPriorWeekMeansNullDelta() {
            projectStat(TODAY, mine, 0, 0, 10, 3, 500L, 9, 4);

            DashboardWeeklyDtos.DashboardWeeklyData data =
                    service.weekly(caller(pm, "PM", List.of(mine)), null, WEEK, null);

            assertThat(card(data, "avg-progress").deltaPct()).isNull();
            assertThat(card(data, "delayed-vs-last-week").deltaPct()).isNull();
            assertThat(card(data, "avg-delay-days").deltaPct())
                    .as("0.0 would claim the delay held steady against a week that was never computed")
                    .isNull();
        }

        @Test
        @DisplayName("finished-so-far sums flow across the week but stops at today")
        void finishedSoFarStopsAtToday() {
            projectStat(WEEK, mine, 0, 2, 5, 0, null, 0, 18);
            projectStat(WEEK.plusDays(1), mine, 0, 3, 5, 0, null, 0, 0);
            projectStat(TODAY, mine, 0, 4, 5, 0, null, 0, 0);
            // Thursday is still in the future against the fixed clock; its
            // closures must not be counted as "so far".
            projectStat(WEEK.plusDays(3), mine, 0, 99, 5, 0, null, 0, 0);

            DashboardWeeklyDtos.DashboardWeeklyData data =
                    service.weekly(caller(pm, "PM", List.of(mine)), null, WEEK, null);

            assertThat(card(data, "due-this-week").secondaryValue())
                    .as("2 + 3 + 4 through today, never the future Thursday's 99").isEqualTo(9.0);
        }

        @Test
        @DisplayName("a PM sees only their own project, and an out-of-scope one is refused in words")
        void scopeIsApplied() {
            projectStat(TODAY, mine, 0, 0, 5, 2, null, 0, 0);
            projectStat(TODAY, theirs, 0, 0, 50, 40, null, 0, 0);

            DashboardWeeklyDtos.DashboardWeeklyData ownProject =
                    service.weekly(caller(pm, "PM", List.of(mine)), null, WEEK, null);
            assertThat(cardValue(ownProject, "delayed-vs-last-week")).as("2 in my project, not 42").isEqualTo(2);

            DashboardWeeklyDtos.DashboardWeeklyData outOfScope =
                    service.weekly(caller(pm, "PM", List.of(mine)), theirs, WEEK, null);
            assertThat(outOfScope.unavailableReason()).isEqualTo(WidgetService.NOT_YOUR_PROJECT);
            assertThat(outOfScope.cards()).isEmpty();
            assertThat(outOfScope.weekStart()).as("the week is still echoed back").isEqualTo(WEEK);
        }

        @Test
        @DisplayName("an empty week reads zero rather than failing")
        void emptyWeekIsZeroNotAnError() {
            DashboardWeeklyDtos.DashboardWeeklyData data =
                    service.weekly(caller(pm, "PM", List.of(mine)), null, WEEK, null);

            assertThat(cardValue(data, "delayed-vs-last-week")).isZero();
            assertThat(cardValue(data, "avg-delay-days")).as("no divide-by-zero on an empty week").isZero();
            assertThat(data.cards()).extracting(DashboardWeeklyDtos.WeeklyCard::key)
                    .doesNotContain("avg-progress");
        }

        @Test
        @DisplayName("average progress spans only the projects that reported a figure")
        void averageIgnoresProjectsWithoutAFigure() {
            member(theirs, pm);
            // Same day, two projects: one measured, one not. SUM ignores the
            // NULL rather than propagating it, so the denominator has to be
            // restricted to match or the average is diluted.
            projectStat(WEEK.plusDays(1), mine, 0, 0, 10, 0, 600L, 0, 0);
            projectStat(WEEK.plusDays(1), theirs, 0, 0, 90, 0, null, 0, 0);

            DashboardWeeklyDtos.DashboardWeeklyData data =
                    service.weekly(caller(pm, "PM", List.of(mine, theirs)), null, WEEK, null);

            assertThat(cardValue(data, "avg-progress"))
                    .as("600 over the 10 open that reported, not over all 100")
                    .isEqualTo(60.0);
        }
    }

    // ── resource-keyed ────────────────────────────────────────────────────

    @Nested
    @DisplayName("resource-keyed cards")
    class ResourceKeyed {

        @Test
        @DisplayName("a developer gets their own figures")
        void developerGetsOwnFigures() {
            resourceStat(TODAY, dev, 3, 10, 4, 12, 500L, 8);

            DashboardWeeklyDtos.DashboardWeeklyData data =
                    service.weekly(caller(dev, "DEVELOPER", List.of(mine)), null, WEEK, null);

            assertThat(cardValue(data, "delayed-vs-last-week")).isEqualTo(4);
            assertThat(cardValue(data, "avg-delay-days")).as("8 delay-days over 4 delayed").isEqualTo(2.0);
            assertThat(data.asOf()).as("read from the row itself, not left null").isNotNull();
        }

        @Test
        @DisplayName("due-this-week reads the Monday row, matching the project variant")
        void dueThisWeekReadsMonday() {
            resourceStat(WEEK, dev, 0, 10, 0, 12, 0, 0);
            resourceStat(TODAY, dev, 0, 10, 0, 99, 0, 0);

            DashboardWeeklyDtos.DashboardWeeklyData data =
                    service.weekly(caller(dev, "DEVELOPER", List.of(mine)), null, WEEK, null);

            assertThat(cardValue(data, "due-this-week")).isEqualTo(12);
        }

        /**
         * The limitation {@code refreshWeeklyStats} documents: this table
         * writes {@code 0} both for "not measured that day" and for "no
         * progress", so a past week cannot be told apart from an unworked one
         * and the card is withheld rather than guessed at.
         */
        @Test
        @DisplayName("own-work progress is offered for the current week only")
        void ownWorkProgressIsCurrentWeekOnly() {
            resourceStat(TODAY, dev, 0, 10, 0, 0, 500L, 0);
            resourceStat(PRIOR_WEEK.plusDays(1), dev, 0, 10, 0, 0, 400L, 0);

            DashboardWeeklyDtos.DashboardWeeklyData current =
                    service.weekly(caller(dev, "DEVELOPER", List.of(mine)), null, WEEK, null);
            assertThat(cardValue(current, "avg-progress")).isEqualTo(50.0);

            DashboardWeeklyDtos.DashboardWeeklyData past =
                    service.weekly(caller(dev, "DEVELOPER", List.of(mine)), null, PRIOR_WEEK, null);
            assertThat(past.cards()).extracting(DashboardWeeklyDtos.WeeklyCard::key)
                    .as("0 there means 'not measured' and 'no progress' indistinguishably")
                    .doesNotContain("avg-progress");
        }

        @Test
        @DisplayName("a colleague's figures do not move a developer's own, and assigneeId is ignored")
        void colleagueChangesAreInvisible() {
            resourceStat(TODAY, dev, 0, 10, 2, 0, 0, 4);
            long colleague = user("wk.mate", "DEVELOPER");
            resourceStat(TODAY, colleague, 0, 99, 99, 0, 0, 99);

            DashboardWeeklyDtos.DashboardWeeklyData data =
                    service.weekly(caller(dev, "DEVELOPER", List.of(mine)), null, WEEK, colleague);

            assertThat(cardValue(data, "delayed-vs-last-week"))
                    .as("own figures regardless of the requested assigneeId").isEqualTo(2);
        }
    }

    // ── read helpers ──────────────────────────────────────────────────────

    private static double cardValue(DashboardWeeklyDtos.DashboardWeeklyData data, String key) {
        return card(data, key).value();
    }

    private static DashboardWeeklyDtos.WeeklyCard card(DashboardWeeklyDtos.DashboardWeeklyData data, String key) {
        return data.cards().stream().filter(c -> c.key().equals(key)).findFirst()
                .orElseThrow(() -> new AssertionError("no card with key " + key
                        + " — present: " + data.cards().stream()
                        .map(DashboardWeeklyDtos.WeeklyCard::key).toList()));
    }
}
