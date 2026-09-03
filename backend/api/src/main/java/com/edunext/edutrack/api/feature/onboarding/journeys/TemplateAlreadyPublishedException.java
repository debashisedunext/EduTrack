package com.edunext.edutrack.api.feature.onboarding.journeys;

/** {@code publish} was called on a version that already has a {@code publishedAt}. */
class TemplateAlreadyPublishedException extends RuntimeException {

    TemplateAlreadyPublishedException(long templateId) {
        super("journey template " + templateId + " has already been published once and cannot "
                + "be published again — publishing always creates a new version");
    }
}
