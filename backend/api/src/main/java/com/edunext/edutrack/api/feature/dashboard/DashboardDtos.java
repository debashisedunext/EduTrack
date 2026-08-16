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
     *             moved four minutes ago. The contract requires it.
     */
    record Summary(Instant asOf, List<Card> cards) {
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
}
