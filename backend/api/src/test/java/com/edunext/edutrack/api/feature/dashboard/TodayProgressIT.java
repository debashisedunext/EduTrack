package com.edunext.edutrack.api.feature.dashboard;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.domain.masters.WorkingHoursService;
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

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dashboard Rework Dev 1, PR 6 · {@link TodayProgressService} against a real
 * database — the {@code DashboardScopeIT} pattern applied to {@code
 * GET /dashboard/today}: developer isolation, PM project narrowing, and the
 * FULL/OWN_WORK card arithmetic against hand-seeded rows.
 *
 * <p>Summary rows are inserted directly rather than produced by the worker,
 * matching {@code DashboardScopeIT}'s own reasoning: what is under test is
 * the read path and the role decision, and computing the rows first would
 * make a failure ambiguous between the two. PR 4's {@code StatsRefreshIT}
 * already proves the worker writes these columns correctly.
 *
 * <h2>Out-of-scope {@code projectId} — not a 404</h2>
 *
 * <p>The backlog entry for this task says "out-of-scope returns 404, not
 * 403", which is CLAUDE.md's row-scoping rule for a ticket-id lookup. This
 * endpoint has no id to 404 on — its one filter is {@code projectId} — and
 * {@link DashboardService} and {@link WidgetService} already settled the
 * identical question for the identical shape of request (A-077): a project
 * outside scope is refused in words ({@code unavailableReason}), because "no
 * rows" would otherwise render as seven cards reading zero, a false
 * measurement. {@link TodayProgressService} reuses that exact contract
 * rather than inventing a third answer, and {@link ProjectScope} asserts the
 * reuse rather than a 404.
 */
@SpringBootTest
@Testcontainers
class TodayProgressIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_today_it")
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
    TodayStatsRepository stats;

    @Autowired
    WorkingHoursService workingHours;

    @Autowired
    JdbcTemplate jdbc;

    // A Tuesday, so nextWorkingDay(TODAY) is an unambiguous Wednesday — no
    // weekend or holiday edge for the near-delay test to reason about.
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 1);
    private static final Clock CLOCK =
            Clock.fixed(TODAY.atTime(6, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    private TodayProgressService service;

    private long mine;
    private long theirs;
    private long dev;
    private long pm;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @BeforeEach
    void setUp() {
        service = new TodayProgressService(stats, workingHours, CLOCK);

        jdbc.update("DELETE FROM daily_ticket_stats");
        jdbc.update("DELETE FROM resource_daily_stats");
        jdbc.update("DELETE FROM project_members");

        mine = project("TPA");
        theirs = project("TPB");
        dev = user("today.dev", "DEVELOPER");
        pm = user("today.pm", "PM");
        member(mine, dev);
        member(mine, pm);
    }

    private long project(String code) {
        jdbc.update("INSERT INTO projects (project_code, name, status) VALUES (?, ?, 'ACTIVE')",
                code + SEQ.incrementAndGet(), "Today IT");
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long user(String name, String roleCode) {
        Long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code = ?", Long.class, roleCode);
        String u = name + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id, is_active)
                VALUES (?, ?, ?, 'x', 'Today IT', ?, 1)
                """, u, u, u + "@example.test", roleId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void member(long projectId, long userId) {
        jdbc.update("INSERT INTO project_members (project_id, user_id, is_active) VALUES (?, ?, 1)",
                projectId, userId);
    }

    private void projectStat(LocalDate day, long projectId, long nsTotal, long nsOverdue, long nsDueToday,
                             long wipTotal, long wipUpdatedToday, long wipNearDelay, long wipDelayed,
                             long blockedOnHold, long blockedAwaitingInfo, long pendingReview,
                             long openTotal, String openByRoleJson) {
        jdbc.update("""
                INSERT INTO daily_ticket_stats (
                    stat_date, project_id, open_total,
                    ns_total, ns_overdue, ns_due_today, wip_total, wip_updated_today, wip_near_delay, wip_delayed,
                    blocked_on_hold, blocked_awaiting_info, pending_review, open_by_role, computed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON), '2026-09-01 06:00:00')
                """, day, projectId, openTotal, nsTotal, nsOverdue, nsDueToday, wipTotal, wipUpdatedToday,
                wipNearDelay, wipDelayed, blockedOnHold, blockedAwaitingInfo, pendingReview, openByRoleJson);
    }

    private void resourceStat(LocalDate day, long userId, long nsTotal, long nsOverdue, long nsDueToday,
                              long wipTotal, long wipUpdatedToday, long wipNearDelay, long wipDelayed,
                              long blockedOnHold, long blockedAwaitingInfo, long pendingReview,
                              long finishedEarly, long finishedOnTime, long finishedLate) {
        jdbc.update("""
                INSERT INTO resource_daily_stats (
                    stat_date, user_id,
                    ns_total, ns_overdue, ns_due_today, wip_total, wip_updated_today, wip_near_delay, wip_delayed,
                    finished_early, finished_on_time, finished_late,
                    blocked_on_hold, blocked_awaiting_info, pending_review, computed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '2026-09-01 06:00:00')
                """, day, userId, nsTotal, nsOverdue, nsDueToday, wipTotal, wipUpdatedToday, wipNearDelay,
                wipDelayed, finishedEarly, finishedOnTime, finishedLate, blockedOnHold, blockedAwaitingInfo,
                pendingReview);
    }

    private static CallerIdentity caller(long userId, String role, List<Long> projects) {
        return new CallerIdentity(userId, role, projects);
    }

    // ── FULL variant — Admin, PM, Support ────────────────────────────────────

    @Nested
    @DisplayName("the FULL variant")
    class FullVariant {

        @Test
        @DisplayName("card totals match the seeded counters, including the whole-plate sum")
        void cardsMatchSeededCounters() {
            projectStat(TODAY, mine, 5, 2, 1, 8, 3, 1, 2, 1, 1, 4, 20, null);

            TodayProgressDtos.TodayProgressData data = service.today(caller(pm, "PM", List.of(mine)), null);

            assertThat(data.variant()).isEqualTo("FULL");
            assertThat(data.unavailableReason()).isNull();
            assertThat(data.asOf()).isNotNull();

            assertThat(cardTotal(data, "todays-work")).as("ns_total 5 + wip_total 8").isEqualTo(13);
            assertThat(cardTotal(data, "not-started")).isEqualTo(5);
            assertThat(cardTotal(data, "wip")).isEqualTo(8);
            assertThat(cardTotal(data, "wip-breakdown")).isEqualTo(8);
            assertThat(cardTotal(data, "blocked")).as("on_hold 1 + awaiting_info 1").isEqualTo(2);
            assertThat(cardTotal(data, "pending-review")).isEqualTo(4);

            assertThat(figureValue(data, "not-started", "overdueStart")).isEqualTo(2);
            assertThat(figureValue(data, "not-started", "dueToday")).isEqualTo(1);
            assertThat(figureValue(data, "wip-breakdown", "nearDelay")).isEqualTo(1);
            assertThat(figureValue(data, "wip-breakdown", "delayed")).isEqualTo(2);
            assertThat(figureValue(data, "wip-breakdown", "onTime")).as("8 wip - 1 near delay - 2 delayed")
                    .isEqualTo(5);
            assertThat(figureValue(data, "wip", "notUpdated")).as("8 wip - 3 updated today").isEqualTo(5);
        }

        @Test
        @DisplayName("open issues total and role chips reconcile, and only UNASSIGNED gets a real drill-down")
        void openIssuesRoleChips() {
            projectStat(TODAY, mine, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 5, "{\"DEVELOPER\": 3, \"UNASSIGNED\": 2}");

            TodayProgressDtos.TodayProgressData data = service.today(caller(pm, "PM", List.of(mine)), null);

            assertThat(data.openIssues().total().value()).isEqualTo(5);
            assertThat(roleValue(data, "DEVELOPER")).isEqualTo(3);
            assertThat(roleValue(data, "QA")).as("absent from the JSON means zero").isEqualTo(0);
            assertThat(roleValue(data, "UNASSIGNED")).isEqualTo(2);

            assertThat(roleDrillDown(data, "UNASSIGNED"))
                    .as("the one role the ticket list can filter on exactly")
                    .contains("unassigned=true");
            assertThat(roleDrillDown(data, "DEVELOPER"))
                    .as("no role-based filter exists on GET /tickets")
                    .isNull();
        }

        @Test
        @DisplayName("the MIS grid is scoped by project membership, not a project column resource_daily_stats lacks")
        void misGridScopedByMembership() {
            projectStat(TODAY, mine, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null);
            resourceStat(TODAY, dev, 2, 1, 0, 3, 1, 0, 1, 0, 0, 0, 0, 0, 0);

            long outsider = user("today.outsider", "DEVELOPER");
            resourceStat(TODAY, outsider, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9);

            TodayProgressDtos.TodayProgressData data = service.today(caller(pm, "PM", List.of(mine)), null);

            assertThat(data.resources())
                    .extracting(TodayProgressDtos.AssigneeMisRow::userId)
                    .as("today.dev is on `mine`; today.outsider is not a member of any project this PM holds")
                    .containsExactly(dev);
        }

        @Test
        @DisplayName("the near-delay drill-down window comes from the real working calendar")
        void nearDelayUsesTheRealWorkingCalendar() {
            projectStat(TODAY, mine, 0, 0, 0, 5, 0, 1, 0, 0, 0, 0, 0, null);

            TodayProgressDtos.TodayProgressData data = service.today(caller(pm, "PM", List.of(mine)), null);

            LocalDate expected = workingHours.nextWorkingDay(TODAY);
            assertThat(figureDrillDown(data, "wip-breakdown", "nearDelay"))
                    .contains("dueTo=" + expected);
        }
    }

    // ── OWN_WORK variant — Developer, QA, Deployment ─────────────────────────

    @Nested
    @DisplayName("the OWN_WORK variant")
    class OwnWorkVariant {

        @Test
        @DisplayName("a developer gets their own figures, no MIS grid, no open issues card")
        void developerGetsOwnFigures() {
            resourceStat(TODAY, dev, 4, 1, 1, 6, 2, 1, 1, 1, 1, 3, 1, 1, 1);

            TodayProgressDtos.TodayProgressData data = service.today(caller(dev, "DEVELOPER", List.of(mine)), null);

            assertThat(data.variant()).isEqualTo("OWN_WORK");
            assertThat(data.openIssues()).isNull();
            assertThat(data.resources()).isEmpty();
            assertThat(cardTotal(data, "not-started")).isEqualTo(4);
            assertThat(cardTotal(data, "wip")).isEqualTo(6);
        }

        @Test
        @DisplayName("every drill-down on the own-work variant names the caller")
        void everyLinkCarriesAssigneeId() {
            resourceStat(TODAY, dev, 4, 1, 1, 6, 2, 1, 1, 1, 1, 3, 1, 1, 1);

            TodayProgressDtos.TodayProgressData data = service.today(caller(dev, "DEVELOPER", List.of(mine)), null);

            data.cards().forEach(card -> card.figures().forEach(f -> {
                if (f.drillDown() != null) {
                    assertThat(f.drillDown()).contains("assigneeId=" + dev);
                }
            }));
        }

        @Test
        @DisplayName("a colleague's figures do not move a developer's own numbers")
        void colleagueChangesAreInvisible() {
            resourceStat(TODAY, dev, 2, 0, 0, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0);
            long colleague = user("today.colleague", "DEVELOPER");
            resourceStat(TODAY, colleague, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99);

            TodayProgressDtos.TodayProgressData data = service.today(caller(dev, "DEVELOPER", List.of(mine)), null);

            assertThat(cardTotal(data, "not-started")).isEqualTo(2);
            assertThat(cardTotal(data, "wip")).isEqualTo(3);
        }

        @Test
        @DisplayName("a person with nothing summarised today reads zero rather than failing")
        void noSummarisedDayIsZeroNotAnError() {
            TodayProgressDtos.TodayProgressData data = service.today(caller(dev, "DEVELOPER", List.of(mine)), null);

            assertThat(data.variant()).isEqualTo("OWN_WORK");
            assertThat(data.asOf()).isNull();
            assertThat(cardTotal(data, "not-started")).isEqualTo(0);
        }
    }

    // ── project scope ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("project scope")
    class ProjectScope {

        @Test
        @DisplayName("a PM sees only their own project")
        void pmSeesOwnProjectOnly() {
            projectStat(TODAY, mine, 5, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null);
            projectStat(TODAY, theirs, 40, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null);

            TodayProgressDtos.TodayProgressData data = service.today(caller(pm, "PM", List.of(mine)), null);

            assertThat(cardTotal(data, "not-started")).as("5 in my project, not 45").isEqualTo(5);
        }

        @Test
        @DisplayName("a PM asking for a project they do not hold is refused in words, not a 404")
        void outOfScopeProjectIsRefusedInWords() {
            projectStat(TODAY, theirs, 40, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null);

            TodayProgressDtos.TodayProgressData data = service.today(caller(pm, "PM", List.of(mine)), theirs);

            assertThat(data.unavailableReason()).isEqualTo(WidgetService.NOT_YOUR_PROJECT);
            assertThat(data.cards()).isEmpty();
            assertThat(data.asOf()).isNull();
        }

        @Test
        @DisplayName("an admin is unrestricted")
        void adminSeesAcrossProjects() {
            projectStat(TODAY, mine, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null);
            projectStat(TODAY, theirs, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null);

            TodayProgressDtos.TodayProgressData data = service.today(caller(999L, "ADMIN", List.of()), null);

            assertThat(cardTotal(data, "not-started")).isEqualTo(7);
        }
    }

    // ── read helpers ──────────────────────────────────────────────────────────

    private static long cardTotal(TodayProgressDtos.TodayProgressData data, String cardKey) {
        return card(data, cardKey).total().value();
    }

    private static long figureValue(TodayProgressDtos.TodayProgressData data, String cardKey, String figureKey) {
        return figure(data, cardKey, figureKey).value();
    }

    private static String figureDrillDown(TodayProgressDtos.TodayProgressData data, String cardKey,
                                          String figureKey) {
        return figure(data, cardKey, figureKey).drillDown();
    }

    private static TodayProgressDtos.TodaySummaryCard card(TodayProgressDtos.TodayProgressData data,
                                                           String cardKey) {
        return data.cards().stream().filter(c -> c.key().equals(cardKey)).findFirst()
                .orElseThrow(() -> new AssertionError("no card with key " + cardKey));
    }

    private static TodayProgressDtos.CardFigure figure(TodayProgressDtos.TodayProgressData data, String cardKey,
                                                        String figureKey) {
        return card(data, cardKey).figures().stream().filter(f -> f.key().equals(figureKey)).findFirst()
                .orElseThrow(() -> new AssertionError("no figure " + figureKey + " on card " + cardKey));
    }

    private static long roleValue(TodayProgressDtos.TodayProgressData data, String role) {
        return role(data, role).value();
    }

    private static String roleDrillDown(TodayProgressDtos.TodayProgressData data, String role) {
        return role(data, role).drillDown();
    }

    private static TodayProgressDtos.RoleFigure role(TodayProgressDtos.TodayProgressData data, String role) {
        return data.openIssues().roles().stream().filter(r -> r.role().equals(role)).findFirst()
                .orElseThrow(() -> new AssertionError("no role figure for " + role));
    }
}
