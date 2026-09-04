package com.edunext.edutrack.api.feature.onboarding.instances;

/**
 * 422 — plan §5.2/§5.3: "clocks dead until the gate opens." A step whose
 * journey is still {@code LOCKED} (the client's prerequisite gate has not
 * cleared) or still held by another journey ({@code held_by_journey_id},
 * C-123's own field) cannot be started, however its own dependency graph
 * would otherwise allow it — C-119's dependency check and this one are two
 * independent gates in front of the same {@code start} action.
 */
class JourneyNotOpenException extends RuntimeException {

    JourneyNotOpenException(long journeyId, boolean locked, Long heldByJourneyId) {
        super("journey " + journeyId + " is not open for step activity"
                + (locked ? " (gate LOCKED)" : "")
                + (heldByJourneyId != null ? " (held by journey " + heldByJourneyId + ")" : ""));
    }
}
