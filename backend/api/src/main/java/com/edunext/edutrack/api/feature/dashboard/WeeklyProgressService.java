package com.edunext.edutrack.api.feature.dashboard;

import com.edunext.edutrack.api.security.CallerIdentity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

/**
 * Dashboard Rework Dev 1, PR 2 · stub. {@code GET /dashboard/weekly}'s real
 * figures — the four cards against the prior week, the five accordion
 * sections — are Dev 2's PR 12, once PR 11's weekly counters exist to read.
 *
 * <p>{@code weekStart}/{@code weekEnd} are real already, not stubbed: both
 * are required, non-null fields in the contract, and echoing the requested
 * (or defaulted) Monday back is what lets a deep link and the week picker
 * agree — it needs no counter to answer. <b>Not yet enforced here: the
 * contract's "a date that is not a Monday is refused" 400.</b> That is
 * request validation belonging to the endpoint's real behaviour, left for
 * PR 12 alongside the cards it would otherwise disagree with.
 */
@Service
class WeeklyProgressService {

    private final Clock clock;

    @Autowired
    WeeklyProgressService() {
        this(Clock.systemUTC());
    }

    /** Test seam — see {@code DashboardService}'s own {@code Clock} for why one is supplied rather than injected. */
    WeeklyProgressService(Clock clock) {
        this.clock = clock;
    }

    DashboardWeeklyDtos.DashboardWeeklyData weekly(CallerIdentity caller, Long projectId, LocalDate weekStart,
                                                    Long assigneeId) {
        LocalDate start = weekStart != null ? weekStart : currentWeekMonday();
        LocalDate end = start.plusDays(6);

        return new DashboardWeeklyDtos.DashboardWeeklyData(null, null, start, end, List.of());
    }

    private LocalDate currentWeekMonday() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }
}
