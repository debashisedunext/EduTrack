package com.edunext.edutrack.api.feature.onboarding.instances;

/** The client already carries a live journey for this product — {@code uq_ob_journeys_client_product}. */
class JourneyAlreadyExistsException extends RuntimeException {

    JourneyAlreadyExistsException(long obClientId, long productId) {
        super("client " + obClientId + " already has a live journey for product " + productId);
    }
}
