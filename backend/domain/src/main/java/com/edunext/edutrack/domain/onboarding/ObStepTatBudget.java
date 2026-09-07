package com.edunext.edutrack.domain.onboarding;

import com.edunext.edutrack.domain.masters.WorkingCalendarRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * C-105/C-114 · {@code tatDays} working days, expressed as hours of the
 * org's own working day length. Extracted rather than left duplicated: C-105
 * ({@code ObJourneyStepLifecycleService#computeDueAt}, where a step's TAT
 * budget lands) and C-114 ({@link ObJourneyStepRagService}, the same
 * budget's denominator for {@code tatConsumedPercent}) both need the exact
 * same number, and the two disagreeing about what one working day is worth
 * is precisely the failure {@code WorkingHoursService}'s own javadoc warns
 * the whole team about — this is the one piece of that maths neither of its
 * two public methods happens to answer directly, so it lives beside them
 * rather than inside either caller.
 */
public final class ObStepTatBudget {

    private ObStepTatBudget() {
    }

    public static BigDecimal hours(WorkingCalendarRepository workingCalendars, int tatDays) {
        long workDayMinutes = workingCalendars.getCalendar().workDayLength().toMinutes();
        return BigDecimal.valueOf(tatDays)
                .multiply(BigDecimal.valueOf(workDayMinutes))
                .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
    }
}
