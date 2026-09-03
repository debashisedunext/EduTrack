package com.edunext.edutrack.api.feature.onboarding.journeys;

/**
 * A published template with zero steps could never activate a journey —
 * refused at {@code publish} rather than left to surface later as a client
 * boarded into nothing.
 */
class TemplateHasNoStepsException extends RuntimeException {

    TemplateHasNoStepsException(long templateId) {
        super("journey template " + templateId + " has no steps and cannot be published");
    }
}
