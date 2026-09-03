package com.edunext.edutrack.api.feature.onboarding.journeys;

/** No {@code ob_journey_template_step_docs} row for the given id. */
class StepDocNotFoundException extends RuntimeException {

    StepDocNotFoundException(long docId) {
        super("no journey template step document " + docId);
    }
}
