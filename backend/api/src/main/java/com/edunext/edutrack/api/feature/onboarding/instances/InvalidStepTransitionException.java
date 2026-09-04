package com.edunext.edutrack.api.feature.onboarding.instances;

import com.edunext.edutrack.domain.onboarding.ObJourneyStepStatus;

/**
 * 422 — the requested move is not one this state machine admits from the
 * step's current status. {@code ObJourneyStepStatus}'s own javadoc names the
 * full set this task owns (start, complete, block-with-reason,
 * waiting-on-client, resume); {@code SKIPPED} is C-107's transition, not
 * reachable through this class at all.
 */
class InvalidStepTransitionException extends RuntimeException {

    InvalidStepTransitionException(long stepId, String action, ObJourneyStepStatus current) {
        super("journey step " + stepId + " cannot " + action + " from status " + current);
    }
}
