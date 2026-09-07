package com.edunext.edutrack.domain.onboarding;

import com.edunext.edutrack.domain.masters.WorkingCalendarRepository;
import com.edunext.edutrack.domain.masters.WorkingHoursService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * C-114 · turns one step's C-105 fields ({@code due_at}, {@code tatDays},
 * the clock-event trail) into the {@code tatConsumedPercent} {@link
 * ObRagCalculator#forStep} needs, and calls it. This is the wiring {@link
 * ObRagCalculator}'s own javadoc named as still missing when the state
 * machine alone was built — the percentage input now exists, so this class
 * is what plugs it in.
 *
 * <p><b>The reference instant is not always "now".</b> A running clock
 * ({@code IN_PROGRESS}/{@code BLOCKED}, per plan §5.7 — internal
 * {@code BLOCKED} does not pause) is read live. A step {@code
 * WAITING_ON_CLIENT} is frozen at the moment it paused, on the same
 * precedent {@code recomputeDueAtOnResume} already reads: {@code due_at}
 * has not moved since the pause, so consuming "now" instead would count
 * client-attributed wait time as if it were TAT the step used, exactly the
 * dispute plan §1.1 item 1 exists to prevent. A {@code DONE} step is frozen
 * at {@link ObJourneyStep#getFinishedAt()}, so a service that finished
 * having breached stays Red rather than drifting to Green as calendar time
 * moves on after it — see {@link ObRagCalculator}'s own javadoc for why
 * that is the intended reading of a closed step.
 *
 * <p>{@code PENDING} and {@code SKIPPED} are refused before any of this
 * runs: {@link ObRagCalculator#forStep} already returns {@code null} for
 * both, and neither has a meaningful {@code due_at} to read — {@code
 * PENDING}'s is {@code null} by construction, and nothing about a skip
 * changes what {@code due_at} the step happened to have when it was
 * bypassed.
 */
@Service
public class ObJourneyStepRagService {

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private final WorkingHoursService workingHours;
    private final WorkingCalendarRepository workingCalendars;
    private final ObStepClockEventRepository clockEvents;

    public ObJourneyStepRagService(WorkingHoursService workingHours,
            WorkingCalendarRepository workingCalendars, ObStepClockEventRepository clockEvents) {
        this.workingHours = workingHours;
        this.workingCalendars = workingCalendars;
        this.clockEvents = clockEvents;
    }

    /** {@link #ragFor(ObJourneyStep, int)} at {@link ObRagCalculator#DEFAULT_AMBER_THRESHOLD_PERCENT}. */
    public ObRag ragFor(ObJourneyStep step) {
        return ragFor(step, ObRagCalculator.DEFAULT_AMBER_THRESHOLD_PERCENT);
    }

    public ObRag ragFor(ObJourneyStep step, int amberThresholdPercent) {
        ObJourneyStepStatus status = step.getStatus();
        if (status == ObJourneyStepStatus.PENDING || status == ObJourneyStepStatus.SKIPPED) {
            return null;
        }
        double percent = tatConsumedPercent(step, referenceTimeFor(step, status));
        return ObRagCalculator.forStep(percent, status, amberThresholdPercent);
    }

    private Instant referenceTimeFor(ObJourneyStep step, ObJourneyStepStatus status) {
        return switch (status) {
            case WAITING_ON_CLIENT -> lastPauseOccurredAt(step.getId());
            case DONE -> step.getFinishedAt();
            case IN_PROGRESS, BLOCKED -> Instant.now();
            case PENDING, SKIPPED -> throw new IllegalStateException(
                    "unreachable — ragFor already returns null for " + status);
        };
    }

    /** Same read {@code ObJourneyStepLifecycleService#recomputeDueAtOnResume} makes to close a pause out. */
    private Instant lastPauseOccurredAt(long stepId) {
        return clockEvents
                .findFirstByStepIdAndEventTypeOrderByOccurredAtDescIdDesc(stepId, ObStepClockEventType.PAUSED)
                .orElseThrow(() -> new IllegalStateException(
                        "step " + stepId + " is WAITING_ON_CLIENT with no PAUSED clock event on record"))
                .getOccurredAt();
    }

    /**
     * {@code (budget - hoursRemaining) / budget * 100}. {@code
     * workingHoursBetween} floors at zero for a {@code referenceTime} at or
     * past {@code due_at} ({@code WorkingHoursService}'s own "start >= end is
     * zero, not negative" rule), so a step well past breach reads as exactly
     * 100%, never more — {@link ObRagCalculator} only needs "at least 100"
     * to call it Red, so the floor costs nothing here.
     */
    private double tatConsumedPercent(ObJourneyStep step, Instant referenceTime) {
        BigDecimal budget = ObStepTatBudget.hours(workingCalendars, step.getTatDays());
        BigDecimal hoursRemaining = workingHours.workingHoursBetween(referenceTime, step.getDueAt());
        BigDecimal hoursConsumed = budget.subtract(hoursRemaining);
        return hoursConsumed.divide(budget, 6, RoundingMode.HALF_UP)
                .multiply(ONE_HUNDRED)
                .doubleValue();
    }
}
