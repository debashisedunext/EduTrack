package com.edunext.edutrack.api.feature.onboarding.instances;

/**
 * The client already carries a live journey for <b>every</b> Module Service
 * of this product — {@code uq_ob_journeys_client_service}.
 *
 * <p>Since a product publishes several services at once, a product-level
 * "already boarded" is only true when there is nothing left to instantiate.
 * A product whose catalogue has grown a service since this client was
 * boarded instantiates the new one and does not raise this.
 */
class JourneyAlreadyExistsException extends RuntimeException {

    JourneyAlreadyExistsException(long obClientId, long productId) {
        super("client " + obClientId + " already has a live journey for every module service "
                + "of product " + productId);
    }
}
