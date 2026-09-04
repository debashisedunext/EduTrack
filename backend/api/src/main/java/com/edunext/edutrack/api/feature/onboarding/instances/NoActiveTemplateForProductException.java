package com.edunext.edutrack.api.feature.onboarding.instances;

/** The product has no published {@code ob_journey_templates} version to instantiate from. */
class NoActiveTemplateForProductException extends RuntimeException {

    NoActiveTemplateForProductException(long productId) {
        super("product " + productId + " has no active journey template");
    }
}
