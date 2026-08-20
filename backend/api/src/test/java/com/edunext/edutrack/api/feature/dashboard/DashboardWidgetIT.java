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
        jdbc.update("DELETE FROM client_daily_stats");
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
        @DisplayName("aging labels are the schema's edges")
        void agingLabelsMatchTheColumns() {
            WidgetDtos.Widget w = widget("PM", "aging-buckets", List.of(mine));

            assertThat(w.series().getFirst().points())
                    .extracting(WidgetDtos.Point::x)
                    .containsExactly("0–2 days", "3–7 days", "8–30 days", "31+ days");
        }

        /**
         * A-060 · these four were dead links until the reported-date window
         * existed, and no age filter was needed to revive them: the worker
         * derives every bucket from {@code DATEDIFF(day, date_reported)}, so an
         * age range *is* a reported-date range.
         *
         * <p>The inversion is what this pins. The older edge of a bucket is the
         * <em>earlier</em> date, so "3–7 days" reads from {@code D3 - 7} to
         * {@code D3 - 3}. Getting it backwards produces a link that looks
         * entirely reasonable and opens the opposite end of the distribution.
         */
        @Test
        @DisplayName("each bucket links to the reported-date window that produced it")
        void agingBucketsLinkByReportedDate() {
            WidgetDtos.Widget w = widget("PM", "aging-buckets", List.of(mine), D1, D3);
            List<WidgetDtos.Point> points = w.series().getFirst().points();

            assertThat(points.get(1).drillDown())
                    .as("3–7 days old on D3 means reported between D3-7 and D3-3")
                    .contains("reportedFrom=" + D3.minusDays(7))
                    .contains("reportedTo=" + D3.minusDays(3))
                    .contains("excludeClosed=true");

            // The oldest bucket is open-ended: everything reported before D3-31,
            // with no earlier bound to state.
            assertThat(points.get(3).drillDown())
                    .contains("reportedTo=" + D3.minusDays(31))
                    .doesNotContain("reportedFrom=");
        }

        /**
         * Anchored on the day the figures describe, not on the clock. The bar was
         * measured at the end of `to`; resolving its window against "today" would
         * open a list that no longer matches the bar that was clicked.
         */
        @Test
        @DisplayName("the age window is anchored to the requested day, not to now")
        void agingWindowAnchorsOnTheRequestedDay() {
            String viaD3 = widget("PM", "aging-buckets", List.of(mine), D1, D3)
                    .series().getFirst().points().getFirst().drillDown();
            String viaD2 = widget("PM", "aging-buckets", List.of(mine), D1, D2)
                    .series().getFirst().points().getFirst().drillDown();

            assertThat(viaD3).contains("reportedTo=" + D3);
            assertThat(viaD2).contains("reportedTo=" + D2);
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
        /**
         * A-062 · {@code aging-buckets} left this list, and that is the whole
         * visible outcome of the migration. The three that remain are refused
         * because the question does not apply to an assignee — not because a
         * column is missing — which is why they are not simply the next task's
         * schema change.
         */
        @Test
        @DisplayName("the three with no resource equivalent say so, rather than drawing an empty chart")
        void unanswerableWidgetsSayWhy() {
            for (String key : List.of("type-donut", "daily-stacked", "priority-bar")) {
                WidgetDtos.Widget w = widget("DEVELOPER", key, List.of(mine));

                assertThat(w.unavailableReason()).as(key).isNotNull();
                assertThat(w.series()).as(key).isEmpty();
            }
        }

        // ── A-062 · widget 12 for a delivery role ────────────────────────────

        /**
         * The bars are theirs, and the fixture makes borrowing visible: the
         * colleague's row carries an order of magnitude more in every bucket,
         * and the project table more again.
         */
        @Test
        @DisplayName("a delivery role's aging chart counts their own open work")
        void agingAnswersForADeliveryRole() {
            resourceAging(D3, me, 4, 3, 2, 1);
            resourceAging(D3, colleague, 40, 30, 20, 10);

            WidgetDtos.Widget w = widget("DEVELOPER", "aging-buckets", List.of(mine, theirs));

            assertThat(w.unavailableReason()).as("A-062 gave this a table to read").isNull();
            assertThat(w.series().getFirst().points()).extracting(WidgetDtos.Point::y)
                    .containsExactly(
                            new java.math.BigDecimal("4"), new java.math.BigDecimal("3"),
                            new java.math.BigDecimal("2"), new java.math.BigDecimal("1"));
        }

        /**
         * The same four labels as the project chart, because they are the same
         * four columns with the same edges. Two charts sharing labels and drill-
         * down links but not boundaries would produce figures that never
         * reconcile and no way to see why.
         */
        @Test
        @DisplayName("the resource bars use the project chart's bucket edges")
        void agingLabelsAreShared() {
            resourceAging(D3, me, 4, 3, 2, 1);

            assertThat(widget("DEVELOPER", "aging-buckets", List.of(mine))
                    .series().getFirst().points())
                    .extracting(WidgetDtos.Point::x)
                    .containsExactly(
                            widget("PM", "aging-buckets", List.of(mine)).series().getFirst()
                                    .points().stream().map(WidgetDtos.Point::x).toArray(String[]::new));
        }

        /**
         * Stock. Three days each holding four tickets aged 0–2 is four tickets,
         * not twelve — and four bars three times too tall look precisely like
         * four bars.
         */
        @Test
        @DisplayName("resource aging reads the latest day rather than summing")
        void resourceAgingIsNotSummed() {
            for (LocalDate d : List.of(D1, D2, D3)) {
                resourceAging(d, me, 4, 3, 2, 1);
            }

            assertThat(widget("DEVELOPER", "aging-buckets", List.of(mine))
                    .series().getFirst().points().getFirst().y())
                    .isEqualByComparingTo("4");
        }

        /**
         * A person earns a row only on days they held or did something, so their
         * latest summarised day can sit behind the window's end. The drill-downs
         * subtract the bucket edges from the day the row was <em>measured</em>;
         * anchoring on {@code to} would open a list about a different day under
         * the same bar.
         */
        @Test
        @DisplayName("the aging links are anchored on the measured day and name the assignee")
        void resourceAgingLinksUseTheMeasuredDay() {
            jdbc.update("DELETE FROM resource_daily_stats WHERE user_id = ? AND stat_date > ?", me, D2);
            resourceAging(D2, me, 4, 3, 2, 1);

            List<WidgetDtos.Point> points = widget("DEVELOPER", "aging-buckets", List.of(mine), D1, D3)
                    .series().getFirst().points();

            assertThat(points.get(1).drillDown())
                    .as("3–7 days old on D2 — the row's day, not D3")
                    .contains("reportedFrom=" + D2.minusDays(7))
                    .contains("reportedTo=" + D2.minusDays(3))
                    .contains("assigneeId=" + me)
                    .contains("excludeClosed=true");
            assertThat(points.get(3).drillDown())
                    .as("the oldest bucket is open-ended")
                    .doesNotContain("reportedFrom=");
        }

        /**
         * Nothing summarised for this person at all. An empty series renders as
         * "nothing to show for this filter and date range"; four zero-height
         * bars would claim they hold no open work, which is a measurement and
         * one nobody took.
         */
        @Test
        @DisplayName("a delivery role with no summarised day gets an empty series, not four zeroes")
        void resourceAgingWithNoRow() {
            jdbc.update("DELETE FROM resource_daily_stats WHERE user_id = ?", me);

            WidgetDtos.Widget w = widget("DEVELOPER", "aging-buckets", List.of(mine));

            assertThat(w.unavailableReason()).isNull();
            assertThat(w.series().getFirst().points()).isEmpty();
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
            // Was `sla-gauge` until A-057 implemented it, and this test failing
            // is how that landed — which is the useful behaviour: a key moving
            // from 404 to served should not pass silently. `stage-funnel` is
            // A-058's and needs Stream C's transitions, so it will outlive this.
            Optional<WidgetService.Rendered> rendered = service.widget(
                    caller(me, "ADMIN", List.of()), "stage-funnel", null, D1, D3);

            assertThat(rendered)
                    .as("A-058's; a role message would send somebody after a permission that would not help")
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

    // ── A-057 · widgets 13–15 ────────────────────────────────────────────────

    @Nested
    @DisplayName("widget 13 · calendar heatmap")
    class Heatmap {

        @Test
        @DisplayName("one cell per summarised day, counting tickets created")
        void oneCellPerDay() {
            WidgetDtos.Widget w = widget("PM", "calendar-heatmap", List.of(mine));

            assertThat(w.series()).hasSize(1);
            assertThat(w.series().getFirst().name()).isEqualTo("Tickets created");
            assertThat(w.series().getFirst().points()).hasSize(3);
            assertThat(w.series().getFirst().points())
                    .extracting(WidgetDtos.Point::y)
                    .allSatisfy(v -> assertThat(v).isEqualByComparingTo("2"));
        }

        /**
         * The one widget whose measure changes with the role, deliberately.
         * Intake is not a fact about an assignee, and the series name is what
         * carries the difference onto the screen.
         */
        @Test
        @DisplayName("a delivery role gets their own closures, named as such")
        void deliveryRoleGetsTheirOwnClosures() {
            WidgetDtos.Widget w = widget("DEVELOPER", "calendar-heatmap", List.of(mine));

            assertThat(w.unavailableReason()).isNull();
            assertThat(w.series().getFirst().name()).isEqualTo("Tickets you closed");
            assertThat(w.series().getFirst().points())
                    .extracting(WidgetDtos.Point::y)
                    .allSatisfy(v -> assertThat(v).as("seeded 1 closed per day for me")
                            .isEqualByComparingTo("1"));
        }

        /**
         * An unsummarised day and a zero-activity day are pixel-identical on a
         * heatmap, and only one of them is a claim about the team.
         */
        @Test
        @DisplayName("a day with no summary row is absent rather than an empty cell")
        void gapsAreAbsent() {
            WidgetDtos.Widget w = widget("PM", "calendar-heatmap", List.of(mine), D1, D8);

            assertThat(w.series().getFirst().points()).hasSize(3);
        }
    }

    @Nested
    @DisplayName("widget 14 · SLA compliance gauge")
    class SlaGauge {

        @Test
        @DisplayName("met and breached come back as counts, not as a percentage")
        void metAndBreachedAreCounts() {
            slaStat(D1, mine, 4, 3);
            slaStat(D2, mine, 4, 1);
            slaStat(D3, mine, 2, 2);

            WidgetDtos.Widget w = widget("PM", "sla-gauge", List.of(mine));

            assertThat(pointNamed(seriesNamed(w, "Met"), "Met")).isEqualByComparingTo("6");
            assertThat(pointNamed(seriesNamed(w, "Breached"), "Breached"))
                    .as("10 closed with a due date, 6 met")
                    .isEqualByComparingTo("4");
        }

        /**
         * Compliance is flow and sums across the window. Reading it off
         * `open_delayed` would be stock, and the gauge would rise every time
         * somebody closed an overdue ticket.
         */
        @Test
        @DisplayName("sums across the window rather than reading one day")
        void complianceSumsAcrossTheWindow() {
            slaStat(D1, mine, 1, 1);
            slaStat(D2, mine, 1, 0);
            slaStat(D3, mine, 1, 1);

            WidgetDtos.Widget w = widget("PM", "sla-gauge", List.of(mine));

            assertThat(pointNamed(seriesNamed(w, "Met"), "Met")).isEqualByComparingTo("2");
            assertThat(pointNamed(seriesNamed(w, "Breached"), "Breached")).isEqualByComparingTo("1");
        }

        /**
         * NULL means "not computed" and must not render as a needle at 0%,
         * which reads as total failure rather than as no measurement.
         */
        @Test
        @DisplayName("an uncomputed window is an empty series, never 0%")
        void uncomputedIsNotZeroPercent() {
            WidgetDtos.Widget w = widget("PM", "sla-gauge", List.of(mine));

            assertThat(w.unavailableReason()).isNull();
            assertThat(w.series()).hasSize(1);
            assertThat(w.series().getFirst().points()).isEmpty();
        }

        @Test
        @DisplayName("the breached half deep-links to the overdue list")
        void breachedDeepLinks() {
            slaStat(D1, mine, 2, 1);

            WidgetDtos.Widget w = widget("PM", "sla-gauge", List.of(mine));

            assertThat(seriesNamed(w, "Breached").points().getFirst().drillDown())
                    .contains("isDelayed=true");
        }

        @Test
        @DisplayName("a delivery role has no SLA outcome recorded and is told so")
        void unavailableToDeliveryRoles() {
            slaStat(D1, mine, 2, 1);

            assertThat(widget("DEVELOPER", "sla-gauge", List.of(mine)).unavailableReason())
                    .isNotNull();
        }
    }

    @Nested
    @DisplayName("widget 15 · project treemap")
    class Treemap {

        /**
         * The invisible failure on a treemap: every rectangle scales by the
         * same factor, so a window summed looks exactly like a single day —
         * identical proportions, identical layout, only the figures wrong.
         */
        @Test
        @DisplayName("reads the latest summarised day rather than summing")
        void stockIsNotSummed() {
            WidgetDtos.Widget w = widget("ADMIN", "project-treemap", List.of());

            assertThat(pointNamed(w, nameOfProject(mine)))
                    .as("10 open on each of three days is 10, not 30")
                    .isEqualByComparingTo("10");
            assertThat(pointNamed(w, nameOfProject(theirs))).isEqualByComparingTo("100");
        }

        @Test
        @DisplayName("a PM sees only their own projects")
        void scopedToTheCallersProjects() {
            WidgetDtos.Widget w = widget("PM", "project-treemap", List.of(mine));

            assertThat(w.series().getFirst().points())
                    .extracting(WidgetDtos.Point::x)
                    .containsExactly(nameOfProject(mine));
        }

        @Test
        @DisplayName("every tile deep-links to that project's open list")
        void tilesDeepLink() {
            WidgetDtos.Widget w = widget("ADMIN", "project-treemap", List.of());

            assertThat(w.series().getFirst().points()).allSatisfy(p ->
                    assertThat(p.drillDown()).startsWith("/tickets?projectId=")
                            .contains("excludeClosed=true"));
        }

        @Test
        @DisplayName("a delivery role has no project dimension and is told so")
        void unavailableToDeliveryRoles() {
            assertThat(widget("DEVELOPER", "project-treemap", List.of(mine)).unavailableReason())
                    .isNotNull();
        }
    }

    // ── widget 20 · client-wise volume ───────────────────────────────────────

    @Nested
    @DisplayName("widget 20 · client-wise volume")
    class ClientVolume {

        /**
         * <b>The one widget on this screen that is meant to sum over days</b>,
         * and the test that proves it does.
         *
         * <p>Every other categorical assertion in this class checks the
         * opposite — that stock reads the latest day rather than three times
         * it. Volume is intake, so three days of five raised is fifteen, and a
         * client-volume implementation copied from the treemap next door would
         * answer five. The fixture makes the two answers different numbers on
         * purpose.
         */
        @Test
        @DisplayName("volume sums the window, because intake is flow")
        void volumeIsSummedOverTheWindow() {
            long acme = client("Acme Industries");
            for (LocalDate d : List.of(D1, D2, D3)) {
                clientStat(d, mine, acme, 5, 0, 20);
            }

            WidgetDtos.Widget w = widget("ADMIN", "client-volume", List.of());

            assertThat(pointNamed(w, "Acme Industries")).isEqualByComparingTo("15");
        }

        /**
         * A client spans projects, and the bar is the sum over the projects
         * this caller can open a ticket from.
         *
         * <p>This is the assertion the table's third key column exists for. A
         * summary keyed by client alone could only have answered 12 to both
         * callers, and the PM's bar would have been four tickets they cannot
         * see, on a chart whose every other bar they can click into.
         */
        @Test
        @DisplayName("a client's bar is scoped to the projects the caller can see")
        void volumeIsScopedByProject() {
            long acme = client("Acme Industries");
            clientStat(D1, mine, acme, 8, 0, 8);
            clientStat(D1, theirs, acme, 4, 0, 4);

            assertThat(pointNamed(widget("ADMIN", "client-volume", List.of()), "Acme Industries"))
                    .isEqualByComparingTo("12");
            assertThat(pointNamed(widget("PM", "client-volume", List.of(mine)), "Acme Industries"))
                    .isEqualByComparingTo("8");
        }

        /** The ranking is the information: the question is who raises the most. */
        @Test
        @DisplayName("bars are ordered largest first")
        void barsAreOrderedLargestFirst() {
            clientStat(D1, mine, client("Small Co"), 2, 0, 2);
            clientStat(D1, mine, client("Large Co"), 30, 0, 30);
            clientStat(D1, mine, client("Middle Co"), 9, 0, 9);

            WidgetDtos.Widget w = widget("ADMIN", "client-volume", List.of());

            assertThat(w.series().getFirst().points()).extracting(WidgetDtos.Point::x)
                    .containsExactly("Large Co", "Middle Co", "Small Co");
        }

        /**
         * A-060's convention, and the reason it matters here: the bar counts
         * tickets <em>raised in the window</em>, so a link without the window
         * would open every ticket that client has ever raised under a heading
         * carrying this month's number.
         */
        @Test
        @DisplayName("every bar deep-links to that client's tickets, within the window")
        void barsDeepLinkWithTheReportedWindow() {
            long acme = client("Acme Industries");
            clientStat(D2, mine, acme, 3, 0, 3);

            WidgetDtos.Widget w = widget("ADMIN", "client-volume", List.of());

            assertThat(w.series().getFirst().points()).singleElement().satisfies(p ->
                    assertThat(p.drillDown())
                            .isEqualTo("/tickets?clientId=" + acme
                                    + "&reportedFrom=" + D1 + "&reportedTo=" + D3));
        }

        /**
         * The cap is a readability limit and must never become a silent one.
         *
         * <p>Thirteen named bars would be the failure this asserts against: the
         * tail is pooled into a thirteenth bar carrying its own count and the
         * sum of what it hides, so the bars still add up to every client-raised
         * ticket in the window.
         */
        @Test
        @DisplayName("beyond twelve clients the tail is pooled, not dropped")
        void theTailIsPooledRatherThanTruncated() {
            // Fourteen clients, descending, so the two smallest are the tail.
            for (int i = 1; i <= 14; i++) {
                clientStat(D1, mine, client("Client " + i), 100 - i, 0, 1);
            }

            WidgetDtos.Widget w = widget("ADMIN", "client-volume", List.of());
            List<WidgetDtos.Point> points = w.series().getFirst().points();

            assertThat(points).hasSize(13);
            assertThat(points.getLast().x()).isEqualTo("Other (2 clients)");
            // 87 + 86 — the two the cap displaced.
            assertThat(points.getLast().y()).isEqualByComparingTo("173");
            // Nothing was lost: the bars sum to every ticket raised.
            long raised = 0;
            for (int i = 1; i <= 14; i++) {
                raised += 100 - i;
            }
            assertThat(points.stream().mapToLong(p -> p.y().longValue()).sum()).isEqualTo(raised);
        }

        /**
         * No filter expresses "any of these two clients", and A-056's aging
         * buckets established that a null link is the honest answer — a segment
         * that opened a list contradicting itself is worse than one that does
         * not open.
         */
        @Test
        @DisplayName("the pooled bar has no drill-down, because no filter expresses it")
        void thePooledBarDoesNotLink() {
            for (int i = 1; i <= 14; i++) {
                clientStat(D1, mine, client("Client " + i), 100 - i, 0, 1);
            }

            List<WidgetDtos.Point> points =
                    widget("ADMIN", "client-volume", List.of()).series().getFirst().points();

            assertThat(points.getLast().drillDown()).isNull();
            assertThat(points.subList(0, 12)).allSatisfy(p ->
                    assertThat(p.drillDown()).isNotNull());
        }

        /**
         * A client that raised nothing in the window is absent rather than a
         * zero-length bar — a label with no bar beside it, which on a ranking
         * chart reads as a rendering fault.
         */
        @Test
        @DisplayName("a client with open tickets but nothing raised is absent")
        void clientsWithNothingRaisedAreAbsent() {
            long quiet = client("Quiet Client");
            long busy = client("Busy Client");
            // Twenty open, none raised in the window: real rows, zero volume.
            clientStat(D1, mine, quiet, 0, 1, 20);
            clientStat(D1, mine, busy, 4, 0, 4);

            WidgetDtos.Widget w = widget("ADMIN", "client-volume", List.of());

            assertThat(w.series().getFirst().points()).extracting(WidgetDtos.Point::x)
                    .containsExactly("Busy Client");
        }

        /**
         * Not an empty chart, which would claim this caller's clients raised
         * nothing.
         */
        @Test
        @DisplayName("a delivery role is told why, rather than shown an empty chart")
        void unavailableToDeliveryRoles() {
            clientStat(D1, mine, client("Acme Industries"), 5, 0, 5);

            WidgetDtos.Widget w = widget("DEVELOPER", "client-volume", List.of(mine));

            assertThat(w.unavailableReason()).isNotNull();
            assertThat(w.series()).isEmpty();
        }
    }

    // ── A-073 · the batch route ──────────────────────────────────────────────

    /**
     * Batching is a transport change. The instant a widget renders even
     * slightly differently in a batch than it does alone, this endpoint stops
     * being an optimisation and becomes a second implementation of the
     * dashboard — so the first test here compares the two directly rather than
     * asserting values a divergent copy could also satisfy.
     */
    @Nested
    @DisplayName("A-073 · several widgets in one request")
    class Batch {

        /**
         * The equivalence test, over the whole implemented set rather than a
         * sample — the failure it guards against is one widget taking a
         * different path through the switch, and a sample is exactly how that
         * one gets missed.
         */
        @Test
        @DisplayName("renders every widget identically to the single route")
        void batchMatchesTheSingleRoute() {
            List<String> keys = List.of("type-donut", "daily-stacked", "velocity", "resource-load",
                    "priority-bar", "aging-buckets", "calendar-heatmap", "sla-gauge",
                    "project-treemap", "client-volume");

            List<WidgetDtos.Widget> batched =
                    service.widgets(caller(me, "ADMIN", List.of()), keys, null, D1, D3).widgets();

            assertThat(batched).extracting(WidgetDtos.Widget::key).containsExactlyElementsOf(keys);
            for (String key : keys) {
                WidgetDtos.Widget alone = widget("ADMIN", key, null);
                WidgetDtos.Widget together = batched.stream()
                        .filter(w -> key.equals(w.key())).findFirst().orElseThrow();
                assertThat(together)
                        .as("widget '%s' must render the same in a batch as it does alone", key)
                        .isEqualTo(alone);
            }
        }

        /**
         * A blank dashboard is a worse answer than a missing tile. The single
         * route's 404 is right when the key is the whole request; here it would
         * take out the other nine.
         */
        @Test
        @DisplayName("drops a key nothing implements rather than failing the set")
        void unimplementedKeysAreDroppedNotFatal() {
            WidgetService.RenderedBatch batch = service.widgets(
                    caller(me, "ADMIN", List.of()),
                    List.of("type-donut", "stage-funnel", "velocity"), null, D1, D3);

            assertThat(batch.widgets()).extracting(WidgetDtos.Widget::key)
                    .containsExactly("type-donut", "velocity");
        }

        /**
         * The inconsistency the per-request version could produce and this one
         * cannot: served separately each tile read {@code computed_at} for
         * itself, so a refresh committing mid-paint could leave the screen
         * showing two moments side by side while presenting them as one.
         */
        @Test
        @DisplayName("every widget in a batch carries the same asOf")
        void oneAsOfForTheWholePaint() {
            List<WidgetDtos.Widget> batched = service.widgets(
                    caller(me, "ADMIN", List.of()),
                    List.of("type-donut", "daily-stacked", "priority-bar"), null, D1, D3).widgets();

            assertThat(batched).extracting(WidgetDtos.Widget::asOf).doesNotContainNull();
            assertThat(batched).extracting(WidgetDtos.Widget::asOf)
                    .containsOnly(batched.getFirst().asOf());
        }

        /** Asking twice for one key must not render it twice. */
        @Test
        @DisplayName("collapses duplicates and keeps the order asked for")
        void duplicatesCollapse() {
            List<WidgetDtos.Widget> batched = service.widgets(
                    caller(me, "ADMIN", List.of()),
                    List.of("velocity", "type-donut", "velocity"), null, D1, D3).widgets();

            assertThat(batched).extracting(WidgetDtos.Widget::key)
                    .containsExactly("velocity", "type-donut");
        }

        /**
         * The validator covers the keys SERVED, not the keys asked for.
         * Otherwise two genuinely different responses share an ETag whenever an
         * unimplemented key is dropped, and a client that changed its request
         * would be told nothing had changed.
         */
        @Test
        @DisplayName("the ETag follows the keys served, not the keys requested")
        void etagCoversTheKeysServed() {
            CallerIdentity admin = caller(me, "ADMIN", List.of());

            String one = service.widgets(admin, List.of("type-donut"), null, D1, D3).etag();
            String two = service.widgets(admin, List.of("type-donut", "velocity"), null, D1, D3).etag();
            String alsoOne = service.widgets(admin,
                    List.of("type-donut", "stage-funnel"), null, D1, D3).etag();

            assertThat(one).isNotNull().isNotEqualTo(two);
            // stage-funnel is dropped, so this served exactly what the first did.
            assertThat(alsoOne).isEqualTo(one);
        }

        /**
         * A delivery role has no summary table that answers the donut and says
         * so per widget. The batch must carry that sentence through rather than
         * refusing the request — a Developer still gets the widgets their table
         * can answer.
         */
        @Test
        @DisplayName("an unavailable widget explains itself without failing the batch")
        void unavailableWidgetsSurviveTheBatch() {
            List<WidgetDtos.Widget> batched = service.widgets(
                    caller(me, "DEVELOPER", List.of()),
                    List.of("type-donut", "velocity"), null, D1, D3).widgets();

            assertThat(batched).hasSize(2);
            assertThat(batched.getFirst().unavailableReason()).isNotNull();
            assertThat(batched.getLast().unavailableReason()).isNull();
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

    /**
     * A-057 gave these distinct <em>names</em>, not merely distinct codes. The
     * treemap is the first widget keyed by project name, and two projects both
     * called "Widget IT" would let an assertion about one of them pass against
     * the other — the quiet kind of green.
     */
    private long project(String code) {
        String unique = code + SEQ.incrementAndGet();
        jdbc.update("INSERT INTO projects (project_code, name, status) VALUES (?, ?, 'ACTIVE')",
                unique, "Widget IT " + unique);
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

    /**
     * A-057 · widget 14's two columns, set separately rather than as two more
     * positional arguments on the fifteen-parameter method above.
     *
     * <p>That method is already long enough that its first version had the
     * priority arguments transposed, and the assertions were written to match
     * the mistake. Two more `int`s in the same row is asking for the same bug.
     * Keeping these apart also makes "never computed" the default: a test that
     * does not call this leaves both NULL, which is the state the gauge has to
     * distinguish from a genuine zero.
     */
    private void slaStat(LocalDate day, long projectId, Integer slaClosed, Integer slaMet) {
        jdbc.update("""
                UPDATE daily_ticket_stats SET sla_closed = ?, sla_met = ?
                 WHERE stat_date = ? AND project_id = ?
                """, slaClosed, slaMet, day, projectId);
    }

    private String nameOfProject(long projectId) {
        return jdbc.queryForObject("SELECT name FROM projects WHERE id = ?", String.class, projectId);
    }

    /**
     * A-062 · the four aging counts, set on a row {@link #resourceStat} has
     * already written — an UPDATE so every existing case keeps seeding exactly
     * the row it always did.
     */
    private void resourceAging(LocalDate day, long userId, int a02, int a37, int a830, int a31) {
        jdbc.update("""
                UPDATE resource_daily_stats
                   SET assigned_aging_0_2 = ?, assigned_aging_3_7 = ?,
                       assigned_aging_8_30 = ?, assigned_aging_31_plus = ?
                 WHERE stat_date = ? AND user_id = ?
                """, a02, a37, a830, a31, day, userId);
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

    /**
     * A-059 · distinct names for the same reason A-057 gave projects them:
     * widget 20 is keyed by client name, and two clients both called
     * "Widget IT" would let an assertion about one pass against the other.
     * The names the tests pass in are already distinct; the code is what needs
     * the counter, being unique and only twenty characters.
     */
    private long client(String name) {
        jdbc.update("INSERT INTO clients (client_code, name) VALUES (?, ?)",
                "WC" + SEQ.incrementAndGet(), name);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    /**
     * One day of one client's activity on one project.
     *
     * <p>{@code created} and {@code openTotal} are deliberately given
     * independently rather than derived from one another: the whole widget
     * rests on volume being the flow column and not the stock one, so a fixture
     * that made them agree would let an implementation reading the wrong column
     * pass.
     */
    private void clientStat(LocalDate day, long projectId, long clientId,
                            int created, int closed, int openTotal) {
        jdbc.update("""
                INSERT INTO client_daily_stats (stat_date, project_id, client_id,
                                                created, closed, open_total, computed_at)
                VALUES (?, ?, ?, ?, ?, ?, '2026-08-12 06:00:00')
                """, day, projectId, clientId, created, closed, openTotal);
    }
}
