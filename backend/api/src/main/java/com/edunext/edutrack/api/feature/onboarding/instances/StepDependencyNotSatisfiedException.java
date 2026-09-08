package com.edunext.edutrack.api.feature.onboarding.instances;

import com.edunext.edutrack.domain.onboarding.ObJourneyStepStatus;

/**
 * C-119 · 422 — {@link ObJourneyStepLifecycleService#start} refused because
 * {@code dependsOnStepId} has not finished. Plan §5.6's own line: "manual
 * start of a step whose dependency is incomplete is refused, naming the
 * blocking step" — so this carries the blocker's id and name rather than
 * only its status, on {@link NotStepOwnerException}'s own precedent for what
 * a caller needs to act on the refusal rather than just be told one occurred.
 *
 * <p>422, not 404: the caller is not missing anything, and the blocking
 * step is a fact about <em>this</em> step's own row, the same reasoning
 * {@link InvalidStepTransitionException} already applies to a status that
 * does not admit the requested move.
 */
class StepDependencyNotSatisfiedException extends RuntimeException {

    private final long blockingStepId;
    private final String blockingStepName;

    StepDependencyNotSatisfiedException(long stepId, long blockingStepId, String blockingStepName,
            ObJourneyStepStatus blockingStatus) {
        super("journey step " + stepId + " cannot start until step " + blockingStepId
                + " (\"" + blockingStepName + "\") is done — currently " + blockingStatus);
        this.blockingStepId = blockingStepId;
        this.blockingStepName = blockingStepName;
    }

    long blockingStepId() {
        return blockingStepId;
    }

    String blockingStepName() {
        return blockingStepName;
    }
}
