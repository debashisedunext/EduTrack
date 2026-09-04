package com.edunext.edutrack.api.feature.onboarding.instances;

/** No {@code ob_client_applications} row for this client and product — nothing to instantiate against. */
class ProductNotPurchasedException extends RuntimeException {

    ProductNotPurchasedException(long obClientId, long productId) {
        super("client " + obClientId + " has not purchased product " + productId);
    }
}
