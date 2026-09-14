package com.edunext.edutrack.api.feature.onboarding.instances;

/**
 * 422 — plan §5.5: a step whose journey is held behind another of the same
 * client's journeys ({@code held_by_journey_id}, C-123's own field) cannot be
 * started, however its own dependency graph would otherwise allow it —
 * C-119's dependency check and this one are two independent gates in front of
 * the same {@code start} action.
 *
 * <p><b>The prerequisite gate is no longer one of the two.</b> It used to be:
 * plan §5.2/§5.3's "clocks dead until the gate opens" refused a start on a
 * {@code LOCKED} journey as well, which meant one unverified document stopped
 * every implementation task for that client. The checklist is advisory now,
 * so a locked gate is reported and not enforced — see
 * {@code ObJourneyStepLifecycleService#start}. The problem type
 * {@code journey-not-open} is unchanged and still means exactly what it says;
 * it simply has one cause rather than two, and that cause is the one a reader
 * genuinely cannot act on today.
 */
class JourneyNotOpenException extends RuntimeException {

    JourneyNotOpenException(long journeyId, long heldByJourneyId) {
        super("journey " + journeyId + " is not open for step activity"
                + " (held by journey " + heldByJourneyId + ")");
    }
}
