package com.edunext.edutrack.api.feature.onboarding.instances;

/** No {@code ob_journey_steps} row for this id. */
class JourneyStepNotFoundException extends RuntimeException {

    JourneyStepNotFoundException(long stepId) {
        super("journey step " + stepId + " does not exist");
    }
}
