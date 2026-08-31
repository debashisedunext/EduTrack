package com.edunext.edutrack.api.feature.dashboard;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Dashboard Rework Dev 1, PR 2 · the shapes {@code GET /dashboard/weekly}
 * answers with. Mirrors {@code DashboardWeeklyResponse} in the contract.
 *
 * <p>Stubbed here — {@code weekStart}/{@code weekEnd} are real ISO-week
 * arithmetic (both are required, non-null fields), {@code cards} is empty.
 * {@code WeeklyProgressService} (Dev 2's PR 12) fills the cards in against
 * this same shape, so PR 12 edits the service only, never this file or the
 * controller.
 */
final class DashboardWeeklyDtos {

    private DashboardWeeklyDtos() {
    }

    record DashboardWeeklyResponse(DashboardWeeklyData data) {
    }

    /**
     * @param weekStart the ISO Monday reported, echoed back so a deep link
     *                  and the picker cannot disagree.
     * @param weekEnd   the Sunday, inclusive.
     */
    record DashboardWeeklyData(
            Instant asOf,
            String unavailableReason,
            LocalDate weekStart,
            LocalDate weekEnd,
            List<WeeklyCard> cards) {
    }

    /**
     * @param value         a count except on {@code avg-progress} (a
     *                      percentage) and {@code avg-delay-days} (days) —
     *                      read {@code unit}, never assume.
     * @param secondaryValue the second figure a card carries where it has
     *                      one — {@code due-this-week} shows finished-so-far
     *                      beside the total.
     * @param deltaPct      change against the prior week, or <b>null when
     *                      that week has no data</b> — never zero, which
     *                      would claim it held steady.
     */
    record WeeklyCard(
            String key,
            String label,
            double value,
            String unit,
            Double secondaryValue,
            String secondaryLabel,
            Double deltaPct,
            String drillDown) {
    }
}
