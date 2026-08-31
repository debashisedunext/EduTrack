package com.edunext.edutrack.api.feature.dashboard;

import java.time.Instant;
import java.util.List;

/**
 * Dashboard Rework Dev 1 · the shapes {@code GET /dashboard/today} answers
 * with. Mirrors {@code TodayProgressResponse} in the contract.
 *
 * <p>PR 2 declares the whole shape and stubs it empty; PR 6 fills
 * {@code TodayProgressService} in. Nothing here changes again once a field
 * is added — the response record is exactly the contract's, so a later PR
 * edits the service, never this file or the controller.
 */
final class TodayProgressDtos {

    private TodayProgressDtos() {
    }

    record TodayProgressResponse(TodayProgressData data) {
    }

    /**
     * @param variant            {@code FULL} or {@code OWN_WORK} — decided
     *                           server-side by {@link DashboardScope}, never
     *                           by a query parameter.
     * @param unavailableReason  non-null only when {@code projectId} names a
     *                           project outside the caller's scope, matching
     *                           {@code DashboardService}'s own wording.
     * @param openIssues         null on the {@code OWN_WORK} variant.
     * @param resources          the MIS grid; empty on the {@code OWN_WORK}
     *                           variant, where a delivery role's own figures
     *                           are already in {@code cards}.
     */
    record TodayProgressData(
            Instant asOf,
            String variant,
            String unavailableReason,
            List<TodaySummaryCard> cards,
            OpenIssuesByRole openIssues,
            List<AssigneeMisRow> resources) {
    }

    /**
     * One of the seven Today cards. {@code total} is the roll-up figure the
     * card leads with; {@code figures} are the independently-clickable
     * sub-figures underneath it — whether they sum to {@code total} is per
     * card and is a fact about that card, not about this shape.
     */
    record TodaySummaryCard(String key, String label, DashboardDtos.Figure total, List<CardFigure> figures) {
    }

    record CardFigure(String key, String label, long value, String drillDown) {
    }

    /** Every not-closed ticket, split by the role currently holding it. Absent on the OWN_WORK variant. */
    record OpenIssuesByRole(DashboardDtos.Figure total, List<RoleFigure> roles) {
    }

    record RoleFigure(String role, String label, long value, String drillDown) {
    }

    /**
     * One resource's MIS row. Ten named columns rather than a key/value map,
     * for the same reason the contract itself gives: a map loses the
     * per-column type on the generated client, and every cell here is a
     * figure with its own drill-down keyed by assignee <em>and</em> metric.
     */
    record AssigneeMisRow(
            long userId,
            String displayName,
            DashboardDtos.Figure overdueStart,
            DashboardDtos.Figure dueToday,
            DashboardDtos.Figure notStarted,
            DashboardDtos.Figure wip,
            DashboardDtos.Figure updatedToday,
            DashboardDtos.Figure nearDelay,
            DashboardDtos.Figure delayed,
            DashboardDtos.Figure onTime,
            DashboardDtos.Figure finishedToday,
            DashboardDtos.Figure finishedLate) {
    }
}
