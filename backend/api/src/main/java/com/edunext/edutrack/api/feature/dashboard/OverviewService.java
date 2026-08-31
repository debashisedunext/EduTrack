package com.edunext.edutrack.api.feature.dashboard;

import com.edunext.edutrack.api.security.CallerIdentity;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * Dashboard Rework Dev 1, PR 2 · stub. {@code GET /dashboard/overview}'s real
 * figures — the four range cards, the Top Assignees bars, the status-split
 * donut — are Dev 2's PR 9, once PR 4's counters exist to read.
 *
 * <p>Everything here is deliberately empty rather than fabricated, so PR 9
 * edits this class only — never {@link DashboardController} or {@link
 * DashboardOverviewDtos}, both already shaped to the contract.
 */
@Service
class OverviewService {

    DashboardOverviewDtos.DashboardOverviewData overview(CallerIdentity caller, Long projectId, LocalDate from,
                                                          LocalDate to, Long assigneeId) {
        return new DashboardOverviewDtos.DashboardOverviewData(null, null, List.of(), List.of(), List.of());
    }
}
