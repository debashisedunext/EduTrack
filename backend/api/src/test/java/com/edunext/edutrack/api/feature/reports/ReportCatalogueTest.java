package com.edunext.edutrack.api.feature.reports;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-063 · the catalogue is a contract with three consumers — the hub's cards,
 * the viewer's filter bar, and A-065's stored schedules — so the ways it can be
 * malformed are worth pinning rather than discovering.
 */
@DisplayName("the report catalogue")
class ReportCatalogueTest {

    @Test
    @DisplayName("declares all eighteen reports of §7.8, so the hub is not silently short")
    void hasEighteen() {
        // §7.8's table plus S-28's Resource 360 is the module's full scope;
        // A-066 to A-068 enumerate exactly eighteen between them. A count is a
        // crude assertion and the right one here: the failure it catches is a
        // report quietly dropped during a merge, which nothing else would show.
        assertThat(ReportCatalogue.declared()).hasSize(18);
    }

    @Test
    @DisplayName("keys are unique — two cards resolving to one report would run the wrong thing")
    void keysAreUnique() {
        List<String> keys = ReportCatalogue.declared().stream().map(ReportDtos.Descriptor::key).toList();
        assertThat(keys).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("keys are kebab-case, because they are URLs and get bookmarked")
    void keysAreUrlSafe() {
        // They also end up in A-065's stored schedule rows, so a rename breaks
        // a saved link and a recurring email at the same time.
        assertThat(ReportCatalogue.declared()).allSatisfy(d ->
                assertThat(d.key()).matches("[a-z0-9]+(-[a-z0-9]+)*"));
    }

    @Test
    @DisplayName("every report has a title and a one-line description for its card")
    void cardsAreRenderable() {
        assertThat(ReportCatalogue.declared()).allSatisfy(d -> {
            assertThat(d.title()).isNotBlank();
            assertThat(d.description()).isNotBlank();
            assertThat(d.category()).isNotNull();
        });
    }

    /**
     * The pairing, in both directions.
     *
     * <p>An unavailable card with no reason is a greyed-out card that says
     * nothing — the exact state the decision to list unbuilt reports at all was
     * meant to avoid. An available card carrying a reason is the subtler bug:
     * A-066 flips {@code available} to true and leaves the sentence behind, and
     * the hub shows a working report with an explanation of why it does not
     * work.
     */
    @Test
    @DisplayName("a reason is present exactly when the report is unavailable")
    void availabilityAndReasonAgree() {
        assertThat(ReportCatalogue.declared()).allSatisfy(d -> {
            if (d.available()) {
                assertThat(d.unavailableReason())
                        .as("%s is available and still carries an unavailable reason", d.key())
                        .isNull();
            } else {
                assertThat(d.unavailableReason())
                        .as("%s is unavailable with nothing to tell the user", d.key())
                        .isNotBlank();
            }
        });
    }

    @Test
    @DisplayName("every report declares at least one filter, or its viewer has no controls")
    void filtersAreDeclared() {
        assertThat(ReportCatalogue.declared()).allSatisfy(d ->
                assertThat(d.filters()).as("%s declares no filters", d.key()).isNotEmpty());
    }

    @Test
    @DisplayName("chart is a value the viewer can draw, or null for a table-only report")
    void chartsAreKnown() {
        Set<String> drawable = Set.of("line", "bar", "stacked-bar", "donut");
        assertThat(ReportCatalogue.declared()).allSatisfy(d ->
                assertThat(d.chart() == null || drawable.contains(d.chart()))
                        .as("%s asks for a chart type the viewer has no case for: %s", d.key(), d.chart())
                        .isTrue());
    }

    @Test
    @DisplayName("exactly the seven reports with a runner behind them are offered")
    void availableSetIsPinned() {
        // An exact set rather than a count, so both halves of the mistake are
        // caught: a card flipped on with no runner (a 500 in front of a user)
        // and a runner added without flipping the card (a report that exists
        // and is unreachable). A-063 shipped one; A-066 adds six.
        List<String> available = ReportCatalogue.declared().stream()
                .filter(ReportDtos.Descriptor::available)
                .map(ReportDtos.Descriptor::key)
                .collect(Collectors.toList());

        assertThat(available).containsExactlyInAnyOrder(
                DateWiseReportRunner.KEY,
                ResourceScorecardRunner.KEY,
                ResourceVelocityRunner.KEY,
                EffortSummaryRunner.KEY,
                SlaBreachRunner.KEY,
                TaskTypeAnalysisRunner.KEY,
                ReopenAnalysisRunner.KEY);
    }

    /**
     * The defect this pins, and why every earlier test passed while it was live.
     *
     * <p>{@code date-wise} reads {@code daily_ticket_stats}, keyed by project.
     * Narrowed to a Developer it answers "what your projects did", while the
     * catalogue's {@code scopeNote} above it read <i>"these reports cover your
     * own work only"</i> — a false sentence about the rows beneath it. Three of
     * its five columns cannot be made true per person at all: a ticket is
     * created by a reporter and reopened by a manager, and net backlog is a
     * project's stock.
     *
     * <p>The tests missed it because they asserted the note and the rows
     * separately and never that the two agreed. It was found by calling the
     * endpoint as a real Developer.
     */
    @Test
    @DisplayName("a delivery role is told a project-keyed report cannot answer them")
    void projectKeyedReportsAreWithheldFromDeliveryRoles() {
        ReportScope ownWork = new ReportScope(true, 8L, List.of(1L));

        // project-health reads daily_ticket_stats and has no per-person form.
        // Unbuilt today, so it keeps "not built yet" — the point asserted here is
        // that it is not offered as runnable.
        assertThat(ReportCatalogue.find("project-health", ownWork).orElseThrow().available()).isFalse();
    }

    /**
     * The second half of the same fix, and the more important half.
     *
     * <p>Withholding {@code date-wise} from a delivery role was honest and
     * useless: it left a Developer with eighteen greyed cards when their own
     * table records what they closed, the effort they logged and what they hold.
     * The report answers them from {@code resource_daily_stats} instead, with
     * the columns that are true per person.
     */
    @Test
    @DisplayName("date-wise answers a delivery role from their own table, without a Project filter")
    void dateWiseAnswersOwnWork() {
        ReportScope ownWork = new ReportScope(true, 8L, List.of(1L));

        ReportDtos.Descriptor dateWise = ReportCatalogue.find(DateWiseReportRunner.KEY, ownWork).orElseThrow();

        assertThat(dateWise.available()).isTrue();
        assertThat(dateWise.unavailableReason()).isNull();
        assertThat(dateWise.description()).contains("What you closed each day");

        // resource_daily_stats is keyed (stat_date, user_id) with no project
        // column, so a Project control is one the runner could not honour.
        assertThat(dateWise.filters()).doesNotContain(ReportFilterKind.PROJECT);
        assertThat(dateWise.filters()).contains(ReportFilterKind.DATE_RANGE);
    }

    @Test
    @DisplayName("an unbuilt report keeps 'not built yet' rather than being relabelled")
    void unbuiltKeepsItsOwnReason() {
        // "Not kept per person" says a report will never be theirs; "not built
        // yet" says it has not been written. Replacing the second with the first
        // would mislead a Developer about something they only have to wait for.
        ReportScope ownWork = new ReportScope(true, 8L, List.of(1L));

        ReportDtos.Descriptor scorecard = ReportCatalogue.find("resource-contribution", ownWork).orElseThrow();

        assertThat(scorecard.available()).isFalse();
        assertThat(scorecard.unavailableReason()).contains("not built yet");
    }

    @Test
    @DisplayName("a PM keeps every report, because a project-keyed table answers them correctly")
    void projectScopedRolesKeepEverything() {
        ReportScope projectScoped = new ReportScope(false, 2L, List.of(1L));

        assertThat(ReportCatalogue.find(DateWiseReportRunner.KEY, projectScoped).orElseThrow().available())
                .isTrue();
    }

    @Test
    @DisplayName("an unknown key resolves to nothing, which is the 404")
    void unknownKeyIsEmpty() {
        assertThat(ReportCatalogue.declared().stream().noneMatch(d -> d.key().equals("no-such-report"))).isTrue();
        assertThat(ReportCatalogue.declared().stream().anyMatch(d -> d.key().equals(DateWiseReportRunner.KEY))).isTrue();
    }
}
