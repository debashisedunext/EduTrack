package com.edunext.edutrack.api.feature.onboarding.journeys;

/** No {@code ob_journey_template_steps} row for the given id. */
class StepNotFoundException extends RuntimeException {

    StepNotFoundException(long stepId) {
        super("no journey template step " + stepId);
    }
}
