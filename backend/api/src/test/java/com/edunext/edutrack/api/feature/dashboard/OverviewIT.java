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

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dashboard Rework Dev 2, PR 9 · {@link OverviewService} against a real
 * database — the {@code DashboardScopeIT}/{@code TodayProgressIT} pattern
 * applied to {@code GET /dashboard/overview}: developer isolation, PM
 * project narrowing, out-of-scope refused in words, and the flow/stock card
 * arithmetic against hand-seeded rows.
 *
 * <p>Summary rows are inserted directly rather than produced by the worker —
 * {@code TodayProgressIT}'s own reasoning applies verbatim: what is under
 * test is the read path and the role decision, not the refresh.
 */
@SpringBootTest
@Testcontainers
class OverviewIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_overview_it")
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
    OverviewRepository overviewRepo;

    @Autowired
    DashboardRepository dashboardRepo;

    @Autowired
    JdbcTemplate jdbc;

    // A window that ends on TODAY so "latest day in range" resolves without
    // ambiguity, mirroring TodayProgressIT's fixed-clock reasoning even
    // though this service takes from/to explicitly rather than a Clock for
    // "today" — the range still needs a stable end date to seed against.
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 1);
    private static final LocalDate FROM = TODAY.minusDays(6);
    private static final Clock CLOCK =
            Clock.fixed(TODAY.atTime(6, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    private OverviewService service;

    private long mine;
    private long theirs;
    private long dev;
    private long pm;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @BeforeEach
    void setUp() {
        service = new OverviewService(overviewRepo, dashboardRepo, CLOCK);

        jdbc.update("DELETE FROM daily_ticket_stats");
        jdbc.update("DELETE FROM resource_daily_stats");
        jdbc.update("DELETE FROM project_members");

        mine = project("OVA");
        theirs = project("OVB");
        dev = user("ov.dev", "DEVELOPER");
        pm = user("ov.pm", "PM");
        member(mine, dev);
        member(mine, pm);
    }

    private long project(String code) {
        jdbc.update("INSERT INTO projects (project_code, name, status) VALUES (?, ?, 'ACTIVE')",
                code + SEQ.incrementAndGet(), "Overview IT");
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long user(String name, String roleCode) {
        Long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code = ?", Long.class, roleCode);
        String u = name + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id, is_active)
                VALUES (?, ?, ?, 'x', ?, ?, 1)
                """, u, u, u + "@example.test", "Overview " + name, roleId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void member(long projectId, long userId) {
        jdbc.update("INSERT INTO project_members (project_id, user_id, is_active) VALUES (?, ?, 1)",
                projectId, userId);
    }

    private void projectStat(LocalDate day, long projectId, long created, long closed,
                             long nsTotal, long nsOverdue, long wipTotal, long wipDelayed,
                             long blockedOnHold, long blockedAwaitingInfo) {
        jdbc.update("""
                INSERT INTO daily_ticket_stats (
                    stat_date, project_id, created, closed,
                    ns_total, ns_overdue, wip_total, wip_delayed,
                    blocked_on_hold, blocked_awaiting_info, computed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '2026-09-01 06:00:00')
                """, day, projectId, created, closed, nsTotal, nsOverdue, wipTotal, wipDelayed,
                blockedOnHold, blockedAwaitingInfo);
    }

    private void resourceStat(LocalDate day, long userId, long closed,
                              long nsTotal, long nsOverdue, long wipTotal, long wipDelayed,
                              long blockedOnHold, long blockedAwaitingInfo) {
        jdbc.update("""
                INSERT INTO resource_daily_stats (
                    stat_date, user_id, closed,
                    ns_total, ns_overdue, wip_total, wip_delayed,
                    blocked_on_hold, blocked_awaiting_info, computed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, '2026-09-01 06:00:00')
                """, day, userId, closed, nsTotal, nsOverdue, wipTotal, wipDelayed,
                blockedOnHold, blockedAwaitingInfo);
    }

    private static CallerIdentity caller(long userId, String role, List<Long> projects) {
        return new CallerIdentity(userId, role, projects);
    }

    // ── project-keyed — Admin, PM, Support ────────────────────────────────

    @Nested
    @DisplayName("project-keyed cards")
    class ProjectKeyed {

        @Test
        @DisplayName("Total and Completed sum flow over the range; Pending and In Progress read the latest day's category stock")
        void cardsMatchSeededCounters() {
            projectStat(FROM, mine, 3, 1, 0, 0, 0, 0, 0, 0);
            projectStat(TODAY, mine, 4, 2, 5, 2, 8, 3, 1, 1);

            DashboardOverviewDtos.DashboardOverviewData data =
                    service.overview(caller(pm, "PM", List.of(mine)), null, FROM, TODAY, null);

            assertThat(data.unavailableReason()).isNull();
            assertThat(data.asOf()).isNotNull();
            assertThat(cardValue(data, "total")).as("3 + 4 created over the window").isEqualTo(7);
            assertThat(cardValue(data, "completed")).as("1 + 2 closed over the window").isEqualTo(3);
            assertThat(cardValue(data, "pending")).as("ns_total on the latest day only").isEqualTo(5);
            assertThat(cardValue(data, "in-progress")).as("wip_total 8 + on_hold 1 + awaiting_info 1")
                    .isEqualTo(10);
        }

        @Test
        @DisplayName("the donut's three buckets sum to its own total and match the cards' own drill-downs")
        void distributionMatchesCards() {
            projectStat(TODAY, mine, 4, 2, 5, 2, 8, 3, 1, 1);

            DashboardOverviewDtos.DashboardOverviewData data =
                    service.overview(caller(pm, "PM", List.of(mine)), null, FROM, TODAY, null);

            long sum = data.distribution().stream().mapToLong(DashboardOverviewDtos.DistributionSlice::value).sum();
            assertThat(sum).isEqualTo(cardValue(data, "pending") + cardValue(data, "in-progress")
                    + cardValue(data, "completed"));
            assertThat(slice(data, "TODO").drillDown()).isEqualTo(card(data, "pending").drillDown());
            assertThat(slice(data, "IN_PROGRESS").drillDown()).isEqualTo(card(data, "in-progress").drillDown());
            assertThat(slice(data, "DONE").drillDown()).isEqualTo(card(data, "completed").drillDown());
        }

        @Test
        @DisplayName("Top Assignees segments are disjoint, sum to the seeded open total, and overdue is not double-counted")
        void assigneeSegmentsAreDisjointAndOverdueTakesPrecedence() {
            projectStat(TODAY, mine, 0, 0, 0, 0, 0, 0, 0, 0);
            // ns_total 5 (2 overdue), wip_total 8 (3 delayed), blocked 1+1.
            resourceStat(TODAY, dev, 0, 5, 2, 8, 3, 1, 1);

            DashboardOverviewDtos.DashboardOverviewData data =
                    service.overview(caller(pm, "PM", List.of(mine)), null, FROM, TODAY, null);

            DashboardOverviewDtos.AssigneeOpenState row = data.assignees().get(0);
            assertThat(row.notStarted().value()).as("5 not-started - 2 already overdue").isEqualTo(3);
            assertThat(row.overdue().value()).as("2 overdue not-started + 3 delayed wip").isEqualTo(5);
            assertThat(row.inProgress().value()).as("8 wip + 1 on-hold + 1 awaiting - 3 already-overdue wip")
                    .isEqualTo(7);
            assertThat(row.notStarted().value() + row.overdue().value() + row.inProgress().value())
                    .as("disjoint segments sum to the person's whole open total")
                    .isEqualTo(5 + 8 + 1 + 1);
        }

        @Test
        @DisplayName("Top Assignees is capped at ten and sorted by open total, and scoped by project membership")
        void topAssigneesSortedAndCappedAndScoped() {
            projectStat(TODAY, mine, 0, 0, 0, 0, 0, 0, 0, 0);
            resourceStat(TODAY, dev, 0, 1, 0, 0, 0, 0, 0);

            long busier = user("ov.busy", "DEVELOPER");
            member(mine, busier);
            resourceStat(TODAY, busier, 0, 9, 0, 0, 0, 0, 0);

            long outsider = user("ov.out", "DEVELOPER");
            resourceStat(TODAY, outsider, 0, 99, 0, 0, 0, 0, 0);

            DashboardOverviewDtos.DashboardOverviewData data =
                    service.overview(caller(pm, "PM", List.of(mine)), null, FROM, TODAY, null);

            assertThat(data.assignees())
                    .extracting(DashboardOverviewDtos.AssigneeOpenState::userId)
                    .as("busiest first, and overview.outsider is not a member of any project this PM holds")
                    .containsExactly(busier, dev);
        }

        @Test
        @DisplayName("a PM sees only their own project")
        void pmSeesOwnProjectOnly() {
            projectStat(TODAY, mine, 5, 0, 0, 0, 0, 0, 0, 0);
            projectStat(TODAY, theirs, 40, 0, 0, 0, 0, 0, 0, 0);

            DashboardOverviewDtos.DashboardOverviewData data =
                    service.overview(caller(pm, "PM", List.of(mine)), null, FROM, TODAY, null);

            assertThat(cardValue(data, "total")).as("5 in my project, not 45").isEqualTo(5);
        }

        @Test
        @DisplayName("a PM asking for a project they do not hold is refused in words, not a 404")
        void outOfScopeProjectIsRefusedInWords() {
            projectStat(TODAY, theirs, 40, 0, 0, 0, 0, 0, 0, 0);

            DashboardOverviewDtos.DashboardOverviewData data =
                    service.overview(caller(pm, "PM", List.of(mine)), theirs, FROM, TODAY, null);

            assertThat(data.unavailableReason()).isEqualTo(WidgetService.NOT_YOUR_PROJECT);
            assertThat(data.cards()).isEmpty();
            assertThat(data.assignees()).isEmpty();
            assertThat(data.asOf()).isNull();
        }

        @Test
        @DisplayName("an admin is unrestricted")
        void adminSeesAcrossProjects() {
            projectStat(TODAY, mine, 3, 0, 0, 0, 0, 0, 0, 0);
            projectStat(TODAY, theirs, 4, 0, 0, 0, 0, 0, 0, 0);

            DashboardOverviewDtos.DashboardOverviewData data =
                    service.overview(caller(999L, "ADMIN", List.of()), null, FROM, TODAY, null);

            assertThat(cardValue(data, "total")).isEqualTo(7);
        }

        @Test
        @DisplayName("no summarised day in range reads zero rather than failing")
        void noSummarisedDayIsZeroNotAnError() {
            DashboardOverviewDtos.DashboardOverviewData data =
                    service.overview(caller(pm, "PM", List.of(mine)), null, FROM, TODAY, null);

            assertThat(data.asOf()).isNull();
            assertThat(cardValue(data, "total")).isEqualTo(0);
            assertThat(data.assignees()).isEmpty();
        }
    }

    // ── resource-keyed — a delivery role's own figures ───────────────────────

    @Nested
    @DisplayName("resource-keyed cards")
    class ResourceKeyed {

        @Test
        @DisplayName("a developer gets their own figures, Total reads zero, and no Top Assignees list")
        void developerGetsOwnFigures() {
            resourceStat(TODAY, dev, 2, 4, 1, 6, 1, 1, 1);

            DashboardOverviewDtos.DashboardOverviewData data =
                    service.overview(caller(dev, "DEVELOPER", List.of(mine)), null, FROM, TODAY, null);

            assertThat(cardValue(data, "total")).as("created is not attributable to an assignee").isEqualTo(0);
            assertThat(cardValue(data, "completed")).isEqualTo(2);
            assertThat(cardValue(data, "pending")).isEqualTo(4);
            assertThat(cardValue(data, "in-progress")).as("6 wip + 1 on-hold + 1 awaiting").isEqualTo(8);
            assertThat(data.assignees()).isEmpty();
        }

        @Test
        @DisplayName("a colleague's figures do not move a developer's own numbers")
        void colleagueChangesAreInvisible() {
            resourceStat(TODAY, dev, 0, 2, 0, 3, 0, 0, 0);
            long colleague = user("ov.mate", "DEVELOPER");
            resourceStat(TODAY, colleague, 0, 99, 0, 99, 0, 0, 0);

            DashboardOverviewDtos.DashboardOverviewData data =
                    service.overview(caller(dev, "DEVELOPER", List.of(mine)), null, FROM, TODAY, null);

            assertThat(cardValue(data, "pending")).isEqualTo(2);
            assertThat(cardValue(data, "in-progress")).isEqualTo(3);
        }

        @Test
        @DisplayName("?assigneeId= from a delivery role is ignored, not honoured")
        void assigneeIdIsIgnoredForADeliveryRole() {
            resourceStat(TODAY, dev, 0, 2, 0, 0, 0, 0, 0);
            long colleague = user("ov.mate2", "DEVELOPER");
            resourceStat(TODAY, colleague, 0, 77, 0, 0, 0, 0, 0);

            DashboardOverviewDtos.DashboardOverviewData data =
                    service.overview(caller(dev, "DEVELOPER", List.of(mine)), null, FROM, TODAY, colleague);

            assertThat(cardValue(data, "pending")).as("own figures regardless of the requested assigneeId")
                    .isEqualTo(2);
        }
    }

    // ── read helpers ──────────────────────────────────────────────────────────

    private static long cardValue(DashboardOverviewDtos.DashboardOverviewData data, String key) {
        return card(data, key).value();
    }

    private static DashboardOverviewDtos.OverviewCard card(DashboardOverviewDtos.DashboardOverviewData data,
                                                            String key) {
        return data.cards().stream().filter(c -> c.key().equals(key)).findFirst()
                .orElseThrow(() -> new AssertionError("no card with key " + key));
    }

    private static DashboardOverviewDtos.DistributionSlice slice(DashboardOverviewDtos.DashboardOverviewData data,
                                                                  String category) {
        return data.distribution().stream().filter(s -> s.category().equals(category)).findFirst()
                .orElseThrow(() -> new AssertionError("no distribution slice for " + category));
    }
}
