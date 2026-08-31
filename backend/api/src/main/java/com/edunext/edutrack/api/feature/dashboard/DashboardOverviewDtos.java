package com.edunext.edutrack.api.feature.dashboard;

import java.time.Instant;
import java.util.List;

/**
 * Dashboard Rework Dev 1, PR 2 · the shapes {@code GET /dashboard/overview}
 * answers with. Mirrors {@code DashboardOverviewResponse} in the contract.
 *
 * <p>Stubbed empty here; {@code OverviewService} (Dev 2's PR 9) fills it in.
 * The response record is exactly the contract's, so PR 9 edits the service
 * only, never this file or the controller.
 */
final class DashboardOverviewDtos {

    private DashboardOverviewDtos() {
    }

    record DashboardOverviewResponse(DashboardOverviewData data) {
    }

    record DashboardOverviewData(
            Instant asOf,
            String unavailableReason,
            List<OverviewCard> cards,
            List<AssigneeOpenState> assignees,
            List<DistributionSlice> distribution) {
    }

    /** Total / Pending (category TODO) / In Progress / Completed (category DONE), for the selected range. */
    record OverviewCard(String key, String label, long value, String drillDown) {
    }

    /**
     * One of the ten busiest assignees, by open total. Reports open state
     * <em>now</em> — in progress, overdue, not started — never what they
     * completed inside the range, which is a different question the cards
     * above already answer.
     */
    record AssigneeOpenState(
            long userId,
            String displayName,
            DashboardDtos.Figure inProgress,
            DashboardDtos.Figure overdue,
            DashboardDtos.Figure notStarted) {
    }

    /** One arc of the half-donut. {@code pct} is served, not computed client-side, so the legend cannot round differently. */
    record DistributionSlice(String category, String label, long value, double pct, String drillDown) {
    }
}
