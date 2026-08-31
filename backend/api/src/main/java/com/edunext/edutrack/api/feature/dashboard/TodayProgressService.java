package com.edunext.edutrack.api.feature.dashboard;

import com.edunext.edutrack.api.security.CallerIdentity;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Dashboard Rework Dev 1, PR 2 · stub. {@code GET /dashboard/today}'s real
 * figures — the seven cards, the MIS grid, the near-delay maths — land in
 * PR 6, once PR 4's {@code daily_ticket_stats} counters exist to read.
 *
 * <p>What is real already: {@code variant}, decided the same way
 * {@link DashboardService} decides it, from {@link DashboardScope} rather
 * than from a second statement of the role rule. Everything else is
 * deliberately empty rather than fabricated — no card, drill-down or MIS
 * row here is one PR 6 has to contradict.
 */
@Service
class TodayProgressService {

    TodayProgressDtos.TodayProgressData today(CallerIdentity caller, Long projectId) {
        DashboardScope scope = DashboardScope.of(caller);
        String variant = scope.ownWorkOnly() ? "OWN_WORK" : "FULL";

        return new TodayProgressDtos.TodayProgressData(null, variant, null, List.of(), null, List.of());
    }
}
