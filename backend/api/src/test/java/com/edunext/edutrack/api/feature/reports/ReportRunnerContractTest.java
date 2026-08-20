package com.edunext.edutrack.api.feature.reports;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-066 · the rules every runner has to keep, asserted once rather than six
 * times.
 *
 * <p>Six reports written in one sitting is six chances for a column to be typed
 * as a string because it happened to render, or for a report to be marked
 * available with no runner behind it. The database-backed behaviour is
 * {@code ReportsIT}'s; this is the part that needs no container.
 */
@DisplayName("report runner contract")
class ReportRunnerContractTest {

    /** Every key A-066 turns on, and the runner constant that must match it. */
    static Stream<org.junit.jupiter.params.provider.Arguments> builtKeys() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("resource-scorecard", ResourceScorecardRunner.KEY),
                org.junit.jupiter.params.provider.Arguments.of("resource-velocity", ResourceVelocityRunner.KEY),
                org.junit.jupiter.params.provider.Arguments.of("effort-summary", EffortSummaryRunner.KEY),
                org.junit.jupiter.params.provider.Arguments.of("sla-breach", SlaBreachRunner.KEY),
                org.junit.jupiter.params.provider.Arguments.of("task-type-analysis", TaskTypeAnalysisRunner.KEY),
                org.junit.jupiter.params.provider.Arguments.of("reopen-analysis", ReopenAnalysisRunner.KEY));
    }

    @ParameterizedTest(name = "{0} is declared available")
    @MethodSource("builtKeys")
    @DisplayName("each of §7.8's first six reports is now offered")
    void sixAreAvailable(String catalogueKey, String runnerKey) {
        assertThat(runnerKey).isEqualTo(catalogueKey);

        ReportDtos.Descriptor d = ReportCatalogue.declared().stream()
                .filter(x -> x.key().equals(catalogueKey))
                .findFirst()
                .orElseThrow();

        assertThat(d.available()).as("%s should be available", catalogueKey).isTrue();
        assertThat(d.unavailableReason()).isNull();
    }

    @Test
    @DisplayName("fourteen reports run — A-063 one, A-066 six, A-067 five, B-060 one, A-070 one")
    void totalAvailable() {
        // Pinned as a count so a report flipped on without a runner, or a runner
        // added without flipping the card, is caught here rather than by a 500.
        assertThat(ReportCatalogue.declared().stream().filter(ReportDtos.Descriptor::available).count())
                .isEqualTo(14);
    }

    /**
     * B-060 · the client report, and the two things about it worth pinning
     * without a database.
     */
    @Test
    @DisplayName("the client report is offered, and declares the Client filter it is built around")
    void clientReportIsOffered() {
        assertThat(ClientReportRunner.KEY).isEqualTo("client-report");

        ReportDtos.Descriptor d = ReportCatalogue.declared().stream()
                .filter(x -> x.key().equals(ClientReportRunner.KEY))
                .findFirst()
                .orElseThrow();

        assertThat(d.available()).isTrue();
        assertThat(d.unavailableReason()).isNull();
        // Without CLIENT the viewer draws no client control and the report is
        // every client at once — which is not the screen §7.8 describes.
        assertThat(d.filters()).contains(ReportFilterKind.CLIENT, ReportFilterKind.DATE_RANGE);
    }

    /**
     * The card says what is missing before somebody opens it looking for it.
     *
     * <p>§7.8 promises five figures per client and the schema records four:
     * there is no CSAT column anywhere, and blueprint §17 item 19 puts the
     * closure rating that would feed one in phase 2–3. A card that promised all
     * five and delivered four would be discovered by a person hunting an absent
     * column, which is the state the catalogue's {@code unavailableReason}
     * machinery exists one level up to avoid.
     */
    @Test
    @DisplayName("the client report's own description says satisfaction is not in it")
    void satisfactionIsDeclaredAbsent() {
        ReportDtos.Descriptor d = ReportCatalogue.declared().stream()
                .filter(x -> x.key().equals(ClientReportRunner.KEY))
                .findFirst()
                .orElseThrow();

        assertThat(d.description()).containsIgnoringCase("satisfaction is not included");
    }

    /**
     * A link kind and the row key it reads are useless apart.
     *
     * <p>{@code linkTo} with no {@code linkIdKey} gives the renderer a
     * destination and no id, and it draws an anchor to {@code
     * /clients/undefined}; {@code linkIdKey} with no {@code linkTo} is a key
     * nothing reads. A dead link is harder to notice than a missing one, so the
     * pairing is asserted here rather than left to a screenshot.
     */
    @Test
    @DisplayName("a column's link kind and its id key are present together or not at all")
    void linkFieldsArePaired() {
        ReportDtos.Column plain = new ReportDtos.Column("closed", "Closed", ReportDtos.ColumnType.NUMBER);
        assertThat(plain.linkTo()).isNull();
        assertThat(plain.linkIdKey()).isNull();

        ReportDtos.Column linked = ReportDtos.Column.linking(
                "client", "Client", ReportDtos.ColumnType.STRING, ReportEntityKind.CLIENT, "clientId");
        assertThat(linked.linkTo()).isEqualTo(ReportEntityKind.CLIENT);
        assertThat(linked.linkIdKey()).isEqualTo("clientId");
        // The id key is not the column's own: the cell shows a name, the link
        // needs an id, and the id is carried in the row without a column.
        assertThat(linked.linkIdKey()).isNotEqualTo(linked.key());
    }

    /**
     * B-060 · the filter seam, asserted where it is cheapest.
     *
     * <p>{@code ReportFilters.NONE} is what every runner sees when the caller
     * named nothing, and each field reaching SQL as null is what makes
     * {@code (:clientId IS NULL OR ...)} mean "every client" rather than "no
     * client at all". A zero or an empty string here would silently return an
     * empty report on the unfiltered case, which reads as no data.
     */
    @Test
    @DisplayName("an unfiltered run carries three nulls, not three empty values")
    void noneIsAllNulls() {
        assertThat(ReportFilters.NONE.clientId()).isNull();
        assertThat(ReportFilters.NONE.taskTypeId()).isNull();
        assertThat(ReportFilters.NONE.level()).isNull();
    }

    /** A-067's five, and the runner constant each must match. */
    static Stream<org.junit.jupiter.params.provider.Arguments> a067Keys() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("project-health", ProjectHealthRunner.KEY),
                org.junit.jupiter.params.provider.Arguments.of("aging", AgingReportRunner.KEY),
                org.junit.jupiter.params.provider.Arguments.of("workload-capacity", WorkloadCapacityRunner.KEY),
                org.junit.jupiter.params.provider.Arguments.of("stage-funnel", StageFunnelRunner.KEY),
                org.junit.jupiter.params.provider.Arguments.of("stage-cycle-time", StageCycleTimeRunner.KEY));
    }

    @ParameterizedTest(name = "{0} is declared available")
    @MethodSource("a067Keys")
    @DisplayName("each of §7.8's reports 8–12 is now offered")
    void a067AreAvailable(String catalogueKey, String runnerKey) {
        assertThat(runnerKey).isEqualTo(catalogueKey);

        ReportDtos.Descriptor d = ReportCatalogue.declared().stream()
                .filter(x -> x.key().equals(catalogueKey))
                .findFirst()
                .orElseThrow();

        assertThat(d.available()).as("%s should be available", catalogueKey).isTrue();
        assertThat(d.unavailableReason()).isNull();
    }

    /**
     * Workload reads resource_daily_stats, which is keyed (stat_date, user_id)
     * and has no project column — A-051 recorded that and it still holds. So the
     * descriptor offers no Project control, for velocity's reason: a filter the
     * runner cannot honour is worse than one that is absent.
     */
    @Test
    @DisplayName("workload offers no Project filter, because its table has no project column")
    void workloadHasNoProjectFilter() {
        ReportDtos.Descriptor d = ReportCatalogue.declared().stream()
                .filter(x -> x.key().equals(WorkloadCapacityRunner.KEY))
                .findFirst()
                .orElseThrow();

        assertThat(d.filters()).doesNotContain(ReportFilterKind.PROJECT);
    }

    /**
     * Project health and aging read daily_ticket_stats, keyed by project. They
     * cannot express "assigned to me" however they are filtered, so a delivery
     * role is told so rather than shown their projects' figures under a note
     * saying the rows are their own — the defect A-063 shipped and fixed.
     */
    @Test
    @DisplayName("the project-keyed reports stay withheld from a delivery role")
    void projectKeyedStayWithheld() {
        ReportScope ownWork = new ReportScope(true, 8L, List.of(1L));

        assertThat(ReportCatalogue.find(ProjectHealthRunner.KEY, ownWork).orElseThrow().available())
                .isFalse();
        assertThat(ReportCatalogue.find(AgingReportRunner.KEY, ownWork).orElseThrow().available())
                .isFalse();
    }

    @Test
    @DisplayName("the stage reports do answer a delivery role, because they filter by assignee")
    void stageReportsAnswerDeliveryRoles() {
        ReportScope ownWork = new ReportScope(true, 8L, List.of(1L));

        assertThat(ReportCatalogue.find(StageFunnelRunner.KEY, ownWork).orElseThrow().available())
                .isTrue();
        assertThat(ReportCatalogue.find(StageCycleTimeRunner.KEY, ownWork).orElseThrow().available())
                .isTrue();
    }

    /**
     * The filter a runner cannot honour must not be drawn.
     *
     * <p>Velocity reads {@code resource_daily_stats}, which is keyed
     * {@code (stat_date, user_id)} and has no project column — A-051 recorded
     * that and A-056 met it too. A PM's scope is applied by membership, but a
     * specific project cannot be. So the descriptor does not offer the control:
     * a filter that silently does nothing is the failure the per-report filter
     * list exists to prevent.
     */
    @Test
    @DisplayName("velocity offers no Project filter, because its table has no project column")
    void velocityHasNoProjectFilter() {
        ReportDtos.Descriptor velocity = ReportCatalogue.declared().stream()
                .filter(d -> d.key().equals(ResourceVelocityRunner.KEY))
                .findFirst()
                .orElseThrow();

        assertThat(velocity.filters()).doesNotContain(ReportFilterKind.PROJECT);
        assertThat(velocity.filters()).contains(ReportFilterKind.DATE_RANGE, ReportFilterKind.RESOURCE);
    }

    /**
     * A-063 withheld the ticket-backed reports from delivery roles because they
     * read a project-keyed table. Five of them now query {@code tickets} with
     * `assigned_to = me`, so they answer a Developer correctly and are no longer
     * withheld.
     */
    @Test
    @DisplayName("a delivery role can now run the reports that scope by assignee")
    void deliveryRolesGetTheTicketBackedReports() {
        ReportScope ownWork = new ReportScope(true, 8L, List.of(1L));

        for (String key : List.of("resource-scorecard", "effort-summary", "sla-breach",
                "task-type-analysis", "reopen-analysis", "resource-velocity")) {
            assertThat(ReportCatalogue.find(key, ownWork).orElseThrow().available())
                    .as("%s should answer a delivery role", key)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("a project-keyed report is still withheld from a delivery role")
    void projectKeyedStillWithheld() {
        // project-health has no per-person form and is unbuilt, so it keeps its
        // own reason rather than being relabelled.
        ReportScope ownWork = new ReportScope(true, 8L, List.of(1L));

        assertThat(ReportCatalogue.find("project-health", ownWork).orElseThrow().available()).isFalse();
    }

    /**
     * B-061 · §7.8 ends the scorecard's column list with "Trend arrows", and
     * the column carrying one was typed {@code NUMBER}.
     *
     * <p>Asserted on the type rather than on the rendering, because the type is
     * what the client switches on and what {@code ?export=} reads. A cell
     * showing an arrow because a renderer matched {@code key === "trend"} would
     * pass a screenshot and break the moment a second report grew one.
     */
    @Test
    @DisplayName("the scorecard's trend column is typed as a trend, not as a number")
    void trendIsItsOwnType() {
        ReportDtos.Column trend = new ReportDtos.Column("trend", "Closed vs previous",
                ReportDtos.ColumnType.TREND);

        assertThat(trend.type()).isEqualTo(ReportDtos.ColumnType.TREND);
        // The contract spells the enum lower-case and the generated client
        // types the union from it, so a mismatch here is a compile error one
        // repository over rather than a runtime surprise.
        assertThat(ReportDtos.ColumnType.TREND.wire()).isEqualTo("trend");
    }

    /**
     * The workload chart asserted a partition its own columns never made.
     *
     * <p>Stacking says the series add up to something. An open ticket is also
     * counted under critical and under delayed, so the bar's height was already
     * double-counting before B-061 added a percentage and two counts that would
     * have stacked on top of it. Side-by-side bars make no claim about a sum.
     *
     * <p>This does not make the chart good — eight series on one axis is still
     * hard to read — and that is recorded rather than fixed here, because the
     * chart belongs to A-067.
     */
    @Test
    @DisplayName("workload plots side by side, because its columns do not partition a total")
    void workloadDoesNotStack() {
        ReportDtos.Descriptor d = ReportCatalogue.declared().stream()
                .filter(x -> x.key().equals(WorkloadCapacityRunner.KEY))
                .findFirst()
                .orElseThrow();

        assertThat(d.chart()).isEqualTo("bar");
    }

    /**
     * B-061 · the allocation total is a floor, and the card says so before
     * somebody opens it looking for a total.
     *
     * <p>{@code allocation_pct} is nullable and means "not stated" — B-017 chose
     * that over the contract's {@code default: 100} — so the sum covers only the
     * memberships that stated a figure. Naming the limit on the card is the same
     * call {@code client-report} made about the figure it does not have.
     */
    @Test
    @DisplayName("workload's own description says the allocation total counts only stated figures")
    void allocationLimitIsDeclared() {
        ReportDtos.Descriptor d = ReportCatalogue.declared().stream()
                .filter(x -> x.key().equals(WorkloadCapacityRunner.KEY))
                .findFirst()
                .orElseThrow();

        assertThat(d.description()).containsIgnoringCase("only projects where one was stated");
    }

    /**
     * B-061 · the five reports that declare a Resource control, listed here so
     * that a sixth cannot be added without somebody meeting the case
     * {@code ReportRunnersIT.ResourceFilter} makes.
     *
     * <p>Between A-066 and B-061 every one of these drew the control and applied
     * nothing, because the runner re-derived the subject as {@code
     * scope.resourceSubject(null)} instead of reading the one it was handed.
     * The behaviour needs a database; the declaration does not, and this is the
     * cheap half.
     */
    @Test
    @DisplayName("six reports declare a Resource filter, and each has a runner that reads it")
    void resourceFilterIsDeclaredBySix() {
        List<String> declaring = ReportCatalogue.declared().stream()
                .filter(ReportDtos.Descriptor::available)
                .filter(d -> d.filters().contains(ReportFilterKind.RESOURCE))
                .map(ReportDtos.Descriptor::key)
                .toList();

        assertThat(declaring).containsExactlyInAnyOrder(
                ResourceScorecardRunner.KEY,
                ResourceVelocityRunner.KEY,
                EffortSummaryRunner.KEY,
                ReopenAnalysisRunner.KEY,
                WorkloadCapacityRunner.KEY,
                // A-070 · declares it as a filter and not as a grouping column —
                // an escalation is often something that happened to a ticket
                // while nobody was looking, so a default view with names
                // against it would read as a blame list.
                CriticalOriginRunner.KEY);
    }

    @Test
    @DisplayName("a rate with no denominator is null, never 0%")
    void ratesWithNoDenominator() {
        // 0% is a measurement: "nobody closed anything on time". Null is the
        // truth: there was nothing to be on time for. The table renders null as
        // an em dash, so the two are distinguishable on the page.
        assertThat(ResourceScorecardRunner.percent(0, 0)).isNull();
        assertThat(ResourceScorecardRunner.percent(3, 4)).isEqualTo(new BigDecimal("75.0"));
    }

    @Test
    @DisplayName("a null average stays null rather than becoming zero")
    void nullAveragesSurvive() {
        // AVG() over no rows is SQL NULL. Rendering it as 0 would claim tickets
        // of that type were resolved instantly.
        assertThat(ResourceScorecardRunner.round(null)).isNull();
        assertThat(ResourceScorecardRunner.round(new BigDecimal("12.34"))).isEqualTo(new BigDecimal("12.3"));
    }
}
