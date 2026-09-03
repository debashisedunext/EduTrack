package com.edunext.edutrack.api.feature.onboarding.journeys;

/** No {@code ob_journey_template_step_items} row for the given id. */
class StepItemNotFoundException extends RuntimeException {

    StepItemNotFoundException(long itemId) {
        super("no journey template step item " + itemId);
    }
}
