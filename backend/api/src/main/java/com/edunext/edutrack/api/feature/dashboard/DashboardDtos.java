package com.edunext.edutrack.api.feature.dashboard;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A-054 · the shapes {@code GET /dashboard/summary} answers with.
 *
 * <p>Mirrors {@code DashboardSummaryResponse} in the contract.
 */
final class DashboardDtos {

    private DashboardDtos() {
    }

    record SummaryResponse(Summary data) {
    }

    /**
     * @param asOf when the summary tables were last recomputed, from
     *             {@code computed_at}. Surfaced because these rows are up to
     *             five minutes old by design (A-051) and a dashboard that hides
     *             its own staleness invites somebody to trust a number that
     *             moved four minutes ago. The contract requires it. <b>Null when
     *             {@code unavailableReason} is set</b> — nothing was read, so
     *             there is no recompute time to report, and a timestamp beside a
     *             refusal would suggest figures were fetched and came back
     *             empty.
     * @param unavailableReason A-077 · non-null when the caller asked for a
     *             project outside their scope, with the reason in plain words
     *             and {@code cards} empty. <b>Not an empty card set on its own</b>,
     *             and not a 404: six cards reading 0 is a measurement rather
     *             than an absence, and 404 would contradict the project master,
     *             which is deliberately not row-scoped so that a project's name
     *             is readable by anybody who can see a ticket naming it.
     *             <p>Mirrors {@code WidgetDtos.Widget.unavailableReason} in both
     *             shape and wording, so the KPI row and the charts under it
     *             cannot give two different answers about the same project.
     */
    record Summary(Instant asOf, List<Card> cards, String unavailableReason) {
    }

    /**
     * One KPI card — widgets 1–6 of §S-05.
     *
     * @param deltaPct  null until A-055. The comparison is against the
     *                  preceding window of equal length, which needs the
     *                  window arithmetic A-055 introduces; returning zero
     *                  meanwhile would render as "no change" and be a lie.
     * @param sparkline empty until A-055, for the same reason — an empty array
     *                  renders as no sparkline, a zero-filled one renders as a
     *                  flat line, and a flat line is a claim.
     * @param drillDown the pre-filtered list this card opens. §S-05's rule is
     *                  that every card deep-links, because "a number nobody can
     *                  click is a number nobody trusts". Built server-side so
     *                  the filter that produced the figure and the filter the
     *                  list applies cannot drift.
     */
    record Card(
            String key,
            String label,
            BigDecimal value,
            BigDecimal deltaPct,
            List<BigDecimal> sparkline,
            String drillDown) {
    }

    /**
     * Dashboard Rework Dev 1, PR 2 · the one-figure shape the Today, Overview
     * and Weekly tabs all build their cards from — {@code DashboardFigure} in
     * the contract. Shared here rather than declared per tab, since
     * {@code TodayProgressDtos}, {@code DashboardOverviewDtos} and
     * {@code DashboardWeeklyDtos} each use it several times over and a figure
     * is the same two things everywhere: a count and the list it opens.
     */
    record Figure(long value, String drillDown) {
    }
}
