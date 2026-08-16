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

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-056 · widgets 7–12, against real MySQL.
 *
 * <h2>What is actually under test</h2>
 *
 * <p>Three things that are invisible in a rendered chart and wrong in ways
 * nobody reports:
 *
 * <ul>
 *   <li><b>Stock is not summed.</b> Three days of the same open tickets must
 *       read as one day's figure, not three. On a donut this is undetectable —
 *       the proportions stay exactly right — and on a bar chart four bars three
 *       times too tall look precisely like four bars.</li>
 *   <li><b>The role decision.</b> Every assertion here about a Developer
 *       mirrors one {@code DashboardScopeIT} makes about the cards. If the two
 *       disagree, one screen is showing somebody rows the other refuses
 *       them.</li>
 *   <li><b>Widget 10's three segments partition the load.</b> The migration
 *       defines {@code assigned_in_progress} disjointly from
 *       {@code assigned_delayed} precisely so a stacked bar's length is the
 *       person's real load; a test that only checked each segment separately
 *       would pass against a bar that double-counts.</li>
 * </ul>
 *
 * <p>Summary rows are inserted directly rather than produced by A-051's worker,
 * for {@code DashboardScopeIT}'s reason: what is under test is the read path
 * and the role decision, and computing the rows first would make a failure
 * ambiguous between the two.
 *
 * <p>⚠️ A 37th Testcontainers MySQL in this module, following the established
 * per-class pattern. The suite starts one container per IT class and that is
 * the dominant cost of the integration gate — a shared instance is the fix and
 * is a change to all 37, not something to begin unilaterally inside A-056.
 */
@SpringBootTest
@Testcontainers
class DashboardWidgetIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("edutrack_widget_it")
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
    WidgetService service;

    @Autowired
    JdbcTemplate jdbc;

    // A Monday, so the ISO-week grouping in velocityByWeek has a boundary the
    // assertions can name. D1..D3 are Mon/Tue/Wed of one week; D8 is the
    // Monday of the next.
    private static final LocalDate D1 = LocalDate.of(2026, 8, 10);
    private static final LocalDate D2 = LocalDate.of(2026, 8, 11);
    private static final LocalDate D3 = LocalDate.of(2026, 8, 12);
    private static final LocalDate D8 = LocalDate.of(2026, 8, 17);

    private long mine;
    private long theirs;
    private long me;
    private long colleague;
    private long bugType;
    private long featureType;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM daily_ticket_stats");
        jdbc.update("DELETE FROM resource_daily_stats");
        jdbc.update("DELETE FROM project_members");

        mine = project("WDGA");
        theirs = project("WDGB");
        me = user("wdg.me");
        colleague = user("wdg.them");

        bugType = taskType("WDG_BUG", "Bug");
        featureType = taskType("WDG_FEA", "Feature");

        member(mine, me);
        member(theirs, colleague);

        // The same stock on all three days. Anything that sums rather than
        // reading the latest day will read three times these figures.
        for (LocalDate d : List.of(D1, D2, D3)) {
            projectStat(d, mine, 2, 1, 0, 10, 1, 2, 3, 4, 5, 3, 1, 1,
                    "{\"" + bugType + "\": 6, \"" + featureType + "\": 4}");
            projectStat(d, theirs, 40, 20, 3, 100, 25, 25, 25, 25, 50, 20, 20, 10, null);
        }

        for (LocalDate d : List.of(D1, D2, D3)) {
            // 9 open = 4 waiting + 3 in progress + 2 delayed
            resourceStat(d, me, 1, 9, 2, 2, 3);
            resourceStat(d, colleague, 15, 60, 25, 12, 20);
        }
    }

    // ── widget 7 · the donut ─────────────────────────────────────────────────

    @Nested
    @DisplayName("widget 7 · task type donut")
    class TypeDonut {

        /**
         * The failure this test exists for. Three days each holding 6 open bugs
         * is 6 open bugs, not 18 — and a donut summed over the window would
         * draw exactly the right proportions while every absolute figure was
         * three times too large.
         */
        @Test
        @DisplayName("reads the latest summarised day rather than summing the window")
        void stockIsNotSummed() {
            WidgetDtos.Widget w = widget("ADMIN", "type-donut", null);

            assertThat(pointNamed(w, "Bug")).isEqualByComparingTo("6");
            assertThat(pointNamed(w, "Feature")).isEqualByComparingTo("4");
        }

        @Test
        @DisplayName("names come from task_types, so a rename does not rewrite history")
        void slicesAreNamedNotNumbered() {
            WidgetDtos.Widget w = widget("ADMIN", "type-donut", null);

            assertThat(w.series()).hasSize(1);
            assertThat(w.series().getFirst().points())
                    .extracting(WidgetDtos.Point::x)
                    .containsExactly("Bug", "Feature");
        }

        @Test
        @DisplayName("every slice deep-links to the list it counts")
        void everySliceDeepLinks() {
            WidgetDtos.Widget w = widget("ADMIN", "type-donut", null);

            assertThat(w.series().getFirst().points()).allSatisfy(p ->
                    assertThat(p.drillDown())
                            .as("§S-05: every chart segment deep-links")
                            .startsWith("/tickets?taskTypeId=")
                            .contains("excludeClosed=true"));
        }

        /**
         * A project whose {@code type_counts} is still NULL contributes nothing
         * rather than throwing or reading as zero across the board — NULL means
         * "not computed", which the migration is explicit about.
         */
        @Test
        @DisplayName("a project with no computed breakdown is absent, not zero")
        void nullTypeCountsAreSkipped() {
            WidgetDtos.Widget w = widget("PM", "type-donut", List.of(theirs));

            assertThat(w.series().getFirst().points()).isEmpty();
            assertThat(w.unavailableReason()).isNull();
        }
    }

    // ── widget 8 · the stacked area ──────────────────────────────────────────

    @Nested
    @DisplayName("widget 8 · daily stacked area")
    class DailyStacked {

        @Test
        @DisplayName("three series, one point per summarised day")
        void threeSeriesOverTheWindow() {
            WidgetDtos.Widget w = widget("PM", "daily-stacked", List.of(mine));

            assertThat(w.series()).extracting(WidgetDtos.Series::name)
                    .containsExactly("Created", "Closed", "Reopened");
            assertThat(w.series()).allSatisfy(s ->
                    assertThat(s.points()).as("three days seeded").hasSize(3));
        }

        /**
         * Flow sums where stock does not, and the seed makes the two
         * distinguishable: 2 created on each of three days is 6 over the window,
         * where 10 open on each of three days is 10.
         */
        @Test
        @DisplayName("flow is per day and does not carry the stock figures")
        void flowIsPerDay() {
            WidgetDtos.Widget w = widget("PM", "daily-stacked", List.of(mine));

            assertThat(seriesNamed(w, "Created").points())
                    .extracting(WidgetDtos.Point::y)
                    .allSatisfy(v -> assertThat(v).isEqualByComparingTo("2"));
        }

        /**
         * A gap is absent, not zero. Plotting an unsummarised day as zero draws
         * a cliff to the axis and back on every weekend, and a stacked area's
         * whole job is shape.
         */
        @Test
        @DisplayName("a day with no summary row is absent rather than plotted as zero")
        void gapsAreAbsentNotZero() {
            WidgetDtos.Widget w = widget("PM", "daily-stacked", List.of(mine), D1, D8);

            assertThat(seriesNamed(w, "Created").points())
                    .as("D4 to D8 were never summarised")
                    .hasSize(3);
        }

        @Test
        @DisplayName("the closed series drills down on the closed-date window the list implements")
        void closedDrillDownUsesARealParameter() {
            WidgetDtos.Widget w = widget("PM", "daily-stacked", List.of(mine));

            assertThat(seriesNamed(w, "Closed").points().getFirst().drillDown())
                    .contains("closedFrom=" + D1, "closedTo=" + D1, "status=CLOSED");
        }
    }

    // ── widgets 9 & 10 · the resource pair ───────────────────────────────────

    @Nested
    @DisplayName("widgets 9 and 10 · the resource-keyed pair")
    class ResourceWidgets {

        @Test
        @DisplayName("velocity groups by ISO week, one series per resource")
        void velocityIsWeekly() {
            WidgetDtos.Widget w = widget("ADMIN", "velocity", null);

            // D1..D3 are one ISO week, so each resource has a single point
            // carrying the week's total rather than three daily ones.
            assertThat(w.series()).hasSize(2);
            assertThat(w.series()).allSatisfy(s -> assertThat(s.points()).hasSize(1));
            assertThat(seriesNamed(w, nameOf(me)).points().getFirst().y())
                    .as("1 closed on each of three days in the same week")
                    .isEqualByComparingTo("3");
        }

        @Test
        @DisplayName("a PM sees only resources in their projects")
        void velocityIsScopedByMembership() {
            WidgetDtos.Widget w = widget("PM", "velocity", List.of(mine));

            assertThat(w.series()).extracting(WidgetDtos.Series::name)
                    .containsExactly(nameOf(me));
        }

        /**
         * The claim a stacked bar makes. Widget 10's three segments must sum to
         * the person's open load — a ticket that is both delayed and being
         * worked drawn in two segments would show a resource holding eleven
         * tickets when they hold nine, and nobody reports that.
         */
        @Test
        @DisplayName("the three segments partition the open load, never double-count")
        void segmentsPartitionTheLoad() {
            WidgetDtos.Widget w = widget("ADMIN", "resource-load", null);

            java.math.BigDecimal open = pointNamed(seriesNamed(w, "Open"), nameOf(me));
            java.math.BigDecimal inProgress = pointNamed(seriesNamed(w, "In progress"), nameOf(me));
            java.math.BigDecimal delayed = pointNamed(seriesNamed(w, "Delayed"), nameOf(me));

            assertThat(open).isEqualByComparingTo("4");
            assertThat(inProgress).isEqualByComparingTo("3");
            assertThat(delayed).isEqualByComparingTo("2");
            assertThat(open.add(inProgress).add(delayed))
                    .as("assigned_open was 9; the bar's length is the person's real load")
                    .isEqualByComparingTo("9");
        }

        @Test
        @DisplayName("each segment deep-links to the slice of the list it represents")
        void segmentsDeepLink() {
            WidgetDtos.Widget w = widget("ADMIN", "resource-load", null);

            assertThat(seriesNamed(w, "Delayed").points().getFirst().drillDown())
                    .contains("assigneeId=", "isDelayed=true", "excludeClosed=true");
            assertThat(seriesNamed(w, "In progress").points().getFirst().drillDown())
                    .contains("status=IN_PROGRESS");
        }
    }

    // ── widgets 11 & 12 · the stock breakdowns ───────────────────────────────

    @Nested
    @DisplayName("widgets 11 and 12 · priority and aging")
    class StockBreakdowns {

        @Test
        @DisplayName("priority bars keep severity order regardless of value")
        void priorityKeepsItsOrder() {
            WidgetDtos.Widget w = widget("PM", "priority-bar", List.of(mine));

            assertThat(w.series().getFirst().points())
                    .extracting(WidgetDtos.Point::x)
                    .containsExactly("Low", "Medium", "High", "Critical");
        }

        @Test
        @DisplayName("priority is stock — the latest day, not the window's sum")
        void priorityIsNotSummed() {
            WidgetDtos.Widget w = widget("PM", "priority-bar", List.of(mine));

            // 4 low / 3 medium / 2 high / 1 critical on each of three days —
            // summing to the seeded open_total of 10, so a breakdown that drifts
            // from the card above it is visible here too.
            assertThat(w.series().getFirst().points()).extracting(WidgetDtos.Point::y)
                    .containsExactly(
                            new java.math.BigDecimal("4"), new java.math.BigDecimal("3"),
                            new java.math.BigDecimal("2"), new java.math.BigDecimal("1"));
        }

        /**
         * The labels follow the columns A-050 stored, not §S-05's prose. A chart
         * whose axis disagrees with the number it draws is worse than one whose
         * buckets disagree with a document.
         */
        @Test
        @DisplayName("aging labels are the schema's edges, and carry no drill-down")
        void agingLabelsMatchTheColumns() {
            WidgetDtos.Widget w = widget("PM", "aging-buckets", List.of(mine));

            assertThat(w.series().getFirst().points())
                    .extracting(WidgetDtos.Point::x)
                    .containsExactly("0–2 days", "3–7 days", "8–30 days", "31+ days");
            assertThat(w.series().getFirst().points())
                    .as("the ticket list has no age filter; a link that contradicts the segment is worse than none")
                    .allSatisfy(p -> assertThat(p.drillDown()).isNull());
        }
    }

    // ── the role decision ────────────────────────────────────────────────────

    @Nested
    @DisplayName("role decides which widgets can be answered at all")
    class RoleAwareness {

        /**
         * Mirrors {@code DashboardScopeIT}: a Developer works inside a project
         * holding 100 open tickets and 60 assigned to a colleague. Nothing they
         * see may be computed from either.
         */
        @Test
        @DisplayName("a delivery role's resource widgets show only their own line")
        void deliveryRolesSeeOnlyThemselves() {
            WidgetDtos.Widget velocity = widget("DEVELOPER", "velocity", List.of(mine, theirs));
            WidgetDtos.Widget load = widget("DEVELOPER", "resource-load", List.of(mine, theirs));

            assertThat(velocity.series()).extracting(WidgetDtos.Series::name)
                    .containsExactly(nameOf(me));
            assertThat(load.series()).allSatisfy(s ->
                    assertThat(s.points()).hasSize(1)
                            .allSatisfy(p -> assertThat(p.x()).isEqualTo(nameOf(me))));
        }

        /**
         * Not an empty series and not a 404. An empty series renders as "no
         * tickets matched", which is a claim about the data and is false.
         */
        @Test
        @DisplayName("the four with no resource equivalent say so, rather than drawing an empty chart")
        void unanswerableWidgetsSayWhy() {
            for (String key : List.of("type-donut", "daily-stacked", "priority-bar", "aging-buckets")) {
                WidgetDtos.Widget w = widget("DEVELOPER", key, List.of(mine));

                assertThat(w.unavailableReason()).as(key).isNotNull();
                assertThat(w.series()).as(key).isEmpty();
            }
        }

        @Test
        @DisplayName("QA and Deployment are treated as Developer is")
        void allThreeDeliveryRoles() {
            for (String role : List.of("DEVELOPER", "QA", "DEPLOYMENT")) {
                assertThat(widget(role, "priority-bar", List.of(mine)).unavailableReason())
                        .as(role).isNotNull();
            }
        }

        @Test
        @DisplayName("Admin reads unrestricted where a PM reads their projects only")
        void adminIsUnrestricted() {
            WidgetDtos.Widget admin = widget("ADMIN", "priority-bar", List.of());
            WidgetDtos.Widget pm = widget("PM", "priority-bar", List.of(mine));

            // Admin sums both projects' latest day: 4 low here, 25 there.
            assertThat(admin.series().getFirst().points().getFirst().y()).isEqualByComparingTo("29");
            assertThat(pm.series().getFirst().points().getFirst().y()).isEqualByComparingTo("4");
        }
    }

    // ── the contract's other promises ────────────────────────────────────────

    @Nested
    @DisplayName("the endpoint's own contract")
    class Contract {

        @Test
        @DisplayName("a key the contract declares but nothing implements is absent, not unavailable")
        void unimplementedKeysAre404() {
            Optional<WidgetService.Rendered> rendered = service.widget(
                    caller(me, "ADMIN", List.of()), "sla-gauge", null, D1, D3);

            assertThat(rendered)
                    .as("A-057's; a role message would send somebody after a permission that would not help")
                    .isEmpty();
        }

        /**
         * The validator is a function of the summary tables' own
         * {@code computed_at}: if the worker has not recomputed, the answer
         * provably has not moved.
         */
        @Test
        @DisplayName("the ETag is stable while the tables are, and changes when they are recomputed")
        void etagTracksComputedAt() {
            String before = rendered("ADMIN", "priority-bar", List.of()).etag();
            assertThat(before).isNotNull();
            assertThat(rendered("ADMIN", "priority-bar", List.of()).etag()).isEqualTo(before);

            jdbc.update("UPDATE daily_ticket_stats SET computed_at = '2026-08-12 07:30:00'");

            assertThat(rendered("ADMIN", "priority-bar", List.of()).etag()).isNotEqualTo(before);
        }

        /**
         * Two callers ask the same URL with different scopes. Sharing a
         * validator would let an intermediary — or a browser cache after a role
         * change — hand one of them the other's chart.
         */
        @Test
        @DisplayName("scope is inside the validator, so two callers never share one")
        void etagIsScopedToTheCaller() {
            assertThat(rendered("PM", "priority-bar", List.of(mine)).etag())
                    .isNotEqualTo(rendered("PM", "priority-bar", List.of(theirs)).etag());
        }

        @Test
        @DisplayName("an unavailable widget carries no validator, so a 304 cannot outlive a role change")
        void unavailableWidgetsHaveNoEtag() {
            assertThat(rendered("DEVELOPER", "priority-bar", List.of(mine)).etag()).isNull();
        }

        @Test
        @DisplayName("every answered widget states when its figures were computed")
        void everyWidgetCarriesAsOf() {
            for (String key : List.of("type-donut", "daily-stacked", "velocity",
                    "resource-load", "priority-bar", "aging-buckets")) {
                assertThat(widget("ADMIN", key, List.of()).asOf())
                        .as("%s — these rows are up to five minutes old by design", key)
                        .isNotNull();
            }
        }
    }

    // ── fixture ──────────────────────────────────────────────────────────────

    private WidgetDtos.Widget widget(String role, String key, List<Long> projects) {
        return widget(role, key, projects, D1, D3);
    }

    private WidgetDtos.Widget widget(String role, String key, List<Long> projects,
                                     LocalDate from, LocalDate to) {
        return service.widget(caller(me, role, projects == null ? List.of() : projects), key, null, from, to)
                .orElseThrow(() -> new AssertionError("no widget served for " + key))
                .widget();
    }

    private WidgetService.Rendered rendered(String role, String key, List<Long> projects) {
        return service.widget(caller(me, role, projects), key, null, D1, D3).orElseThrow();
    }

    private static CallerIdentity caller(long userId, String role, List<Long> projects) {
        return new CallerIdentity(userId, role, projects);
    }

    private static WidgetDtos.Series seriesNamed(WidgetDtos.Widget w, String name) {
        return w.series().stream().filter(s -> s.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no series named " + name + " in " + w.key()));
    }

    private static java.math.BigDecimal pointNamed(WidgetDtos.Widget w, String x) {
        return pointNamed(w.series().getFirst(), x);
    }

    private static java.math.BigDecimal pointNamed(WidgetDtos.Series s, String x) {
        return s.points().stream().filter(p -> p.x().equals(x)).findFirst()
                .orElseThrow(() -> new AssertionError("no point at " + x)).y();
    }

    private String nameOf(long userId) {
        return jdbc.queryForObject("SELECT full_name FROM users WHERE id = ?", String.class, userId);
    }

    /** A plain counter — {@code project_code} is unique and VARCHAR(10). See {@code DashboardScopeIT}. */
    private static final AtomicInteger SEQ = new AtomicInteger();

    private long project(String code) {
        jdbc.update("INSERT INTO projects (project_code, name, status) VALUES (?, ?, 'ACTIVE')",
                code + SEQ.incrementAndGet(), "Widget IT");
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long user(String name) {
        Long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE code = 'DEVELOPER'", Long.class);
        String u = name + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO users (emp_code, username, email, password_hash, full_name, role_id, is_active)
                VALUES (?, ?, ?, 'x', ?, ?, 1)
                """, u, u, u + "@example.test", "Widget " + u, roleId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long taskType(String code, String name) {
        String c = code + SEQ.incrementAndGet();
        jdbc.update("INSERT INTO task_types (code, name, seq, is_active) VALUES (?, ?, ?, 1)",
                c, name, SEQ.get());
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void member(long projectId, long userId) {
        jdbc.update("INSERT INTO project_members (project_id, user_id, is_active) VALUES (?, ?, 1)",
                projectId, userId);
    }

    private void projectStat(LocalDate day, long projectId, int created, int closed, int reopened,
                             int openTotal, int openCritical, int openHigh, int openMedium, int openLow,
                             int openDelayed, int aging02, int aging37, int aging830, String typeCounts) {
        jdbc.update("""
                INSERT INTO daily_ticket_stats (stat_date, project_id, created, closed, reopened,
                                                open_total, open_critical, open_high, open_medium, open_low,
                                                open_delayed, open_reopened,
                                                aging_0_2, aging_3_7, aging_8_30, aging_31_plus,
                                                type_counts, computed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, 0, ?, '2026-08-12 06:00:00')
                """, day, projectId, created, closed, reopened, openTotal, openCritical, openHigh,
                openMedium, openLow, openDelayed, aging02, aging37, aging830, typeCounts);
    }

    private void resourceStat(LocalDate day, long userId, int closed, int assignedOpen,
                              int assignedCritical, int assignedDelayed, int assignedInProgress) {
        jdbc.update("""
                INSERT INTO resource_daily_stats (stat_date, user_id, closed, effort_hours,
                                                  assigned_open, assigned_critical, assigned_delayed,
                                                  assigned_in_progress, computed_at)
                VALUES (?, ?, ?, 0.00, ?, ?, ?, ?, '2026-08-12 06:00:00')
                """, day, userId, closed, assignedOpen, assignedCritical, assignedDelayed, assignedInProgress);
    }
}
