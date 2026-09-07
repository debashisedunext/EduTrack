package com.edunext.edutrack.api.feature.onboarding.reports;

import java.util.List;

import static com.edunext.edutrack.api.feature.onboarding.reports.ObReportDtos.Chart.BAR;
import static com.edunext.edutrack.api.feature.onboarding.reports.ObReportDtos.Chart.DONUT;
import static com.edunext.edutrack.api.feature.onboarding.reports.ObReportDtos.Chart.FUNNEL;
import static com.edunext.edutrack.api.feature.onboarding.reports.ObReportDtos.Chart.LINE;
import static com.edunext.edutrack.api.feature.onboarding.reports.ObReportDtos.Chart.STACKED_BAR;
import static com.edunext.edutrack.api.feature.onboarding.reports.ObReportFilterKind.CLIENT;
import static com.edunext.edutrack.api.feature.onboarding.reports.ObReportFilterKind.DATE_RANGE;
import static com.edunext.edutrack.api.feature.onboarding.reports.ObReportFilterKind.OWNER;
import static com.edunext.edutrack.api.feature.onboarding.reports.ObReportFilterKind.PRODUCT;
import static com.edunext.edutrack.api.feature.onboarding.reports.ObReportFilterKind.RAG;

/**
 * B-122 · every onboarding report this deployment knows about, built or not.
 *
 * <h2>Twelve declared, six runnable</h2>
 *
 * <p>Plan §10 specifies twelve. This task builds six of them. The other six are
 * listed with {@code available: false} and a reason rather than omitted, which
 * is {@code listObReports}' own instruction and matters more here than on the
 * ticketing hub, because this catalogue is <em>known</em> to be arriving in
 * parts: hiding a report would make an undecided one indistinguishable from one
 * that does not exist, which is the state the decision is about.
 *
 * <p>The six that are not built split into two groups with genuinely different
 * reasons, and the reasons are on the cards rather than averaged into one
 * sentence:
 *
 * <ul>
 *   <li><b>Five are held pending a product decision.</b> Breach log, escalation
 *       log, owner workload, communication audit and CSAT summary are
 *       PHASE-2-BUILD-PLAN §3 #8's OB4b group — "all straightforward reads over
 *       data that will already exist", scheduled but not committed to phase 2.
 *       Nothing technical is missing.</li>
 *   <li><b>One is waiting on tables that do not exist.</b> Prerequisite aging
 *       reads {@code ob_client_prereq_tasks}, which is B-124/B-125's and is not
 *       in any applied migration — {@code V20260903_2045__ob_attachments.sql}
 *       says so on its own comment. This is a build-order fact, not a decision,
 *       and saying "held pending the reports decision" on it would be false.</li>
 * </ul>
 *
 * <p><b>🔴 A-118's mock declares {@code prereq-aging} available and this
 * declares it not.</b> The divergence is deliberate and is the mock's to
 * correct, not this catalogue's: the contract is served rather than hardcoded
 * precisely so a deployment can state what it can actually run, and a
 * descriptor claiming a report whose tables are absent would send every caller
 * to a 404 that reads as a bug. Flagged in the backlog and the mock updated to
 * match, rather than left as two servers disagreeing about the same key.
 *
 * <h2>Why the titles and descriptions are A-118's, verbatim</h2>
 *
 * <p>They are already in the contract's examples and in the mock, and a hub
 * whose card text changed depending on which server answered would make the
 * mock useless for reviewing the screen. Where this file differs from the mock
 * it is a fact about the deployment, never a wording preference.
 */
final class ObReportCatalogue {

    private ObReportCatalogue() {
    }

    /** PHASE-2-BUILD-PLAN §3 #8's OB4b group. One sentence, five cards. */
    private static final String HELD_AS_OB4B =
            "Held as OB4b pending the reports decision (PHASE-2-BUILD-PLAN §11.6).";

    /**
     * The one unbuilt report whose reason is a missing table rather than a
     * pending decision.
     *
     * <p>Names the tasks rather than the table: a user reading a greyed card
     * needs to know somebody is building it, and {@code ob_client_prereq_tasks}
     * tells them nothing they can act on.
     */
    private static final String AWAITING_PREREQUISITES =
            "The prerequisites master and its per-client tasks are not built yet "
                    + "(B-124, B-125), so there is nothing to age.";

    private static final List<ObReportDtos.ObReportDescriptor> DECLARED = List.of(

            // ── built ───────────────────────────────────────────────────────
            ObReportDtos.ObReportDescriptor.built(
                    JourneyFunnelRunner.KEY,
                    "Journey funnel by product",
                    "Where journeys sit, per product.",
                    ObReportCategory.DELIVERY, FUNNEL,
                    List.of(DATE_RANGE, PRODUCT)),

            ObReportDtos.ObReportDescriptor.built(
                    TatComplianceRunner.KEY,
                    "TAT compliance by service and owner",
                    "On-time delivery against pinned TATs.",
                    ObReportCategory.QUALITY, BAR,
                    List.of(DATE_RANGE, PRODUCT, OWNER)),

            ObReportDtos.ObReportDescriptor.built(
                    StuckAndAgingRunner.KEY,
                    "Stuck & aging",
                    "Block reasons and client-attributed waits.",
                    ObReportCategory.CLIENT, BAR,
                    List.of(DATE_RANGE, PRODUCT, RAG)),

            ObReportDtos.ObReportDescriptor.built(
                    TimeToLiveRunner.KEY,
                    "Time to live, per product",
                    "How long boarding actually takes.",
                    ObReportCategory.DELIVERY, LINE,
                    List.of(DATE_RANGE, PRODUCT)),

            ObReportDtos.ObReportDescriptor.built(
                    SalesPipelineRunner.KEY,
                    "Sales pipeline",
                    "Boarded clients by sales person.",
                    ObReportCategory.PIPELINE, BAR,
                    List.of(DATE_RANGE)),

            ObReportDtos.ObReportDescriptor.built(
                    SignoffPendingRunner.KEY,
                    "Sign-offs pending",
                    "Requested and unanswered, oldest first.",
                    ObReportCategory.CLIENT, null,
                    List.of(DATE_RANGE, CLIENT)),

            // ── declared, not runnable here ─────────────────────────────────
            ObReportDtos.ObReportDescriptor.held(
                    "prereq-aging",
                    "Prerequisite aging",
                    "Client-attributed time before the gate.",
                    ObReportCategory.CLIENT, BAR,
                    List.of(DATE_RANGE, CLIENT), AWAITING_PREREQUISITES),

            ObReportDtos.ObReportDescriptor.held(
                    "breach-log",
                    "Breach log",
                    "Every TAT breach, with its ladder.",
                    ObReportCategory.QUALITY, null,
                    List.of(DATE_RANGE, PRODUCT, OWNER), HELD_AS_OB4B),

            ObReportDtos.ObReportDescriptor.held(
                    "escalation-log",
                    "Escalation log",
                    "Internal and client escalations side by side.",
                    ObReportCategory.QUALITY, null,
                    List.of(DATE_RANGE, CLIENT), HELD_AS_OB4B),

            ObReportDtos.ObReportDescriptor.held(
                    "owner-workload",
                    "Owner workload",
                    "Open services per implementor over time.",
                    ObReportCategory.DELIVERY, STACKED_BAR,
                    List.of(DATE_RANGE, OWNER), HELD_AS_OB4B),

            ObReportDtos.ObReportDescriptor.held(
                    "communication-audit",
                    "Communication audit per client",
                    "Every recorded conversation, chronologically.",
                    ObReportCategory.CLIENT, null,
                    List.of(DATE_RANGE, CLIENT), HELD_AS_OB4B),

            ObReportDtos.ObReportDescriptor.held(
                    "csat-summary",
                    "CSAT summary",
                    "Go-live survey scores by product.",
                    ObReportCategory.QUALITY, DONUT,
                    List.of(DATE_RANGE, PRODUCT), HELD_AS_OB4B));

    /** Every descriptor, in the order the hub groups them. */
    static List<ObReportDtos.ObReportDescriptor> declared() {
        return DECLARED;
    }

    /** The descriptor for a key, or null when the catalogue does not name one. */
    static ObReportDtos.ObReportDescriptor find(String key) {
        return DECLARED.stream()
                .filter(d -> d.key().equals(key))
                .findFirst()
                .orElse(null);
    }

    /**
     * The human title for an export's own heading, falling back to the key.
     *
     * <p>The file is still worth producing if the descriptor has gone — a
     * spreadsheet headed {@code TAT-COMPLIANCE} is worse than one headed "TAT
     * compliance by service and owner" and much better than none.
     */
    static String titleFor(String key) {
        ObReportDtos.ObReportDescriptor descriptor = find(key);
        return descriptor == null ? key.toUpperCase(java.util.Locale.ROOT) : descriptor.title();
    }
}
