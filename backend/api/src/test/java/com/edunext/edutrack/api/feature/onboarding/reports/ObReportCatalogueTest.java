package com.edunext.edutrack.api.feature.onboarding.reports;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-122 · what the catalogue promises must be what the module can deliver.
 *
 * <p>Every assertion here is about a mismatch that would reach a user as a
 * broken screen rather than as an exception: a card with no explanation, a card
 * that 404s when opened, a control that changes nothing.
 */
class ObReportCatalogueTest {

    /** The twelve keys plan §10 specifies, as A-118 spells them on the wire. */
    private static final Set<String> DECLARED_KEYS = Set.of(
            "journey-funnel", "tat-compliance", "stuck-and-aging", "time-to-live",
            "sales-pipeline", "signoff-pending", "prereq-aging", "breach-log",
            "escalation-log", "owner-workload", "communication-audit", "csat-summary");

    @Test
    @DisplayName("plan §10's twelve reports are all declared, built or not")
    void everyReportInThePlanIsDeclared() {
        assertThat(keys()).containsExactlyInAnyOrderElementsOf(DECLARED_KEYS);
    }

    @Test
    void keysAreUnique() {
        assertThat(keys()).doesNotHaveDuplicates();
    }

    /**
     * The pairing, in both directions.
     *
     * <p>A greyed card with no reason is the state the whole
     * declared-but-unavailable approach exists to avoid — it makes "not built
     * yet" indistinguishable from "broken". A reason on an available card is
     * the mirror mistake: a sentence explaining why a working report cannot be
     * run, next to a link that runs it.
     */
    @Test
    @DisplayName("unavailableReason is present exactly when available is false")
    void anUnavailableCardAlwaysCarriesItsReason() {
        for (ObReportDtos.ObReportDescriptor descriptor : ObReportCatalogue.declared()) {
            if (descriptor.available()) {
                assertThat(descriptor.unavailableReason())
                        .as("%s is available and must explain nothing", descriptor.key())
                        .isNull();
            } else {
                assertThat(descriptor.unavailableReason())
                        .as("%s is unavailable and must say why", descriptor.key())
                        .isNotBlank();
            }
        }
    }

    @Test
    @DisplayName("the seven runners that exist are available and the five that do not are not")
    void theBuiltSetIsExactlyTheRunnersThatExist() {
        assertThat(available()).containsExactlyInAnyOrder(
                JourneyFunnelRunner.KEY, TatComplianceRunner.KEY, StuckAndAgingRunner.KEY,
                TimeToLiveRunner.KEY, SalesPipelineRunner.KEY, SignoffPendingRunner.KEY,
                CsatSummaryRunner.KEY);
    }

    /**
     * The one unbuilt report whose reason is not the OB4b decision.
     *
     * <p>Pinned because averaging the two would be the easy edit: prerequisite
     * aging is not waiting on a client decision, it is waiting on
     * {@code ob_client_prereq_tasks}, which B-124/B-125 create and no applied
     * migration contains. Telling a user it is "held pending the reports
     * decision" would send them to ask a question nobody can answer.
     */
    @Test
    void prerequisiteAgingSaysItIsWaitingOnTablesRatherThanOnADecision() {
        ObReportDtos.ObReportDescriptor descriptor = ObReportCatalogue.find("prereq-aging");

        assertThat(descriptor.available()).isFalse();
        assertThat(descriptor.unavailableReason())
                .contains("B-124", "B-125")
                .doesNotContain("PHASE-2-BUILD-PLAN");
    }

    @Test
    void theFourHeldAsOb4bAllSayWhichDecisionTheyAreWaitingOn() {
        List<String> held = ObReportCatalogue.declared().stream()
                .filter(d -> !d.available())
                .filter(d -> !"prereq-aging".equals(d.key()))
                .map(ObReportDtos.ObReportDescriptor::key)
                .toList();

        assertThat(held).containsExactlyInAnyOrder(
                "breach-log", "escalation-log", "owner-workload", "communication-audit");
        held.forEach(key -> assertThat(ObReportCatalogue.find(key).unavailableReason())
                .contains("OB4b", "§11.6"));
    }

    /**
     * B-119 · CSAT summary moved out of the OB4b group into the built set —
     * see {@code CsatSummaryRunner}'s own javadoc for why this one card and
     * not its four siblings.
     */
    @Test
    void csatSummaryIsBuiltNotHeld() {
        ObReportDtos.ObReportDescriptor descriptor = ObReportCatalogue.find("csat-summary");

        assertThat(descriptor.available()).isTrue();
        assertThat(descriptor.unavailableReason()).isNull();
    }

    /**
     * Every card declares at least one filter, and the date range is on all of
     * them.
     *
     * <p>A report with no controls at all is a report the viewer draws a filter
     * bar for and then leaves empty, which reads as a screen that failed to
     * load rather than as one with nothing to set.
     */
    @Test
    void everyReportHonoursAtLeastTheDateRange() {
        for (ObReportDtos.ObReportDescriptor descriptor : ObReportCatalogue.declared()) {
            assertThat(descriptor.filters())
                    .as("%s", descriptor.key())
                    .contains(ObReportFilterKind.DATE_RANGE);
        }
    }

    @Test
    void everyCardHasATitleAndAOneLineDescription() {
        for (ObReportDtos.ObReportDescriptor descriptor : ObReportCatalogue.declared()) {
            assertThat(descriptor.title()).as("%s title", descriptor.key()).isNotBlank();
            assertThat(descriptor.description())
                    .as("%s description", descriptor.key()).isNotBlank();
            assertThat(descriptor.category()).as("%s category", descriptor.key()).isNotNull();
        }
    }

    /**
     * {@code titleFor} falls back rather than failing.
     *
     * <p>It names an export's own heading, and a spreadsheet is still worth
     * producing when the descriptor behind it has gone.
     */
    @Test
    void theExportTitleFallsBackToTheKey() {
        assertThat(ObReportCatalogue.titleFor(JourneyFunnelRunner.KEY))
                .isEqualTo("Journey funnel by product");
        assertThat(ObReportCatalogue.titleFor("invented")).isEqualTo("INVENTED");
    }

    @Test
    void anUnknownKeyResolvesToNoDescriptor() {
        assertThat(ObReportCatalogue.find("invented")).isNull();
    }

    private static List<String> keys() {
        return ObReportCatalogue.declared().stream().map(ObReportDtos.ObReportDescriptor::key).toList();
    }

    private static Set<String> available() {
        return ObReportCatalogue.declared().stream()
                .filter(ObReportDtos.ObReportDescriptor::available)
                .map(ObReportDtos.ObReportDescriptor::key)
                .collect(Collectors.toSet());
    }
}
