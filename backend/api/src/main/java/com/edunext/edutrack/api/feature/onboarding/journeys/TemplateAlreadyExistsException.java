package com.edunext.edutrack.api.feature.onboarding.journeys;

/**
 * {@code createTemplate} is only "+ Create journey template" — a product's
 * very first version. A product with any template row already, draft or
 * published, is edited through {@link ObJourneyTemplateService#beginRevision},
 * not created again.
 */
class TemplateAlreadyExistsException extends RuntimeException {

    TemplateAlreadyExistsException(long productId) {
        super("product " + productId + " already has a journey template; revise the active "
                + "version instead of creating a new one");
    }
}
