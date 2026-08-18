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
    @DisplayName("seven reports run in total — the six plus A-063's date-wise")
    void totalAvailable() {
        // Pinned as a count so a report flipped on without a runner, or a runner
        // added without flipping the card, is caught here rather than by a 500.
        assertThat(ReportCatalogue.declared().stream().filter(ReportDtos.Descriptor::available).count())
                .isEqualTo(7);
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
