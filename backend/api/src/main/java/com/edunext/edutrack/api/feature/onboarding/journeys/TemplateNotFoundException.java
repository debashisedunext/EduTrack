package com.edunext.edutrack.api.feature.onboarding.journeys;

/** No {@code ob_journey_templates} row for the given id. */
class TemplateNotFoundException extends RuntimeException {

    TemplateNotFoundException(long templateId) {
        super("no journey template " + templateId);
    }
}
