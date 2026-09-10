package com.edunext.edutrack.api.feature.onboarding.journeys;

/**
 * C-124 · the rename's own collision — a second service under one product
 * sharing a name with an existing one.
 *
 * <p>{@code createObJourneyTemplate} leaves this to
 * {@code uq_ob_journey_templates_version (product_id, name, version)} and lets
 * the constraint violation surface as {@code 409}. A rename cannot: it moves
 * <em>every</em> version of the chain at once, so the first row to collide
 * aborts the transaction partway with a message naming an index and a version
 * number rather than the service. Checked up front so the refusal reads as
 * "that name is taken under this product", which is what the admin can act on.
 */
class DuplicateModuleServiceNameException extends RuntimeException {

    DuplicateModuleServiceNameException(long productId, String name) {
        super("product " + productId + " already has a Module Service called \"" + name
                + "\" — two services a picker cannot tell apart");
    }
}
