package com.edunext.edutrack.api.feature.onboarding.clients;

/**
 * B-104 · this client has already bought this product — 409
 * {@code ob-application-duplicate-product}.
 *
 * <h2>Buying more seats is an edit, not a second purchase</h2>
 *
 * <p>{@code uq_ob_client_applications (ob_client_id, product_id)} says so and
 * the migration says why: "a second row would mean a second journey for one
 * product, and the client page would render the same accordion twice".
 * {@code ObClientWriteService.validateForCreate} already refuses two rows for
 * one product <em>within</em> one wizard submission, in the same words; this is
 * the same rule for the purchase added a month later, where the first row is in
 * the database rather than in the same request.
 *
 * <p>It is also not merely cosmetic. {@code uq_ob_journeys_client_product} is
 * "one live journey per client per product", so the second purchase's journey
 * would be refused by <em>that</em> index after this one had already been
 * written — leaving a purchase with no journey behind it, which is the one state
 * {@code ObClientChildWriteRepository} names as having to be noticed and
 * repaired by hand.
 *
 * <p>{@code forceable: false}. There is a real thing the caller wants — more
 * seats, a renewed licence — and it is the {@code PATCH} one URL along, which is
 * what the message points at.
 */
class DuplicateApplicationProductException extends RuntimeException {

    private final transient long existingApplicationId;

    DuplicateApplicationProductException(String productName, long existingApplicationId) {
        super(productName + " is already purchased by this client. Buying more seats, changing "
                + "the licence type or renewing the licence window is an edit to that purchase — "
                + "a second row would mean a second journey for one product.");
        this.existingApplicationId = existingApplicationId;
    }

    /** So the panel can open the row that is already there rather than only refusing the new one. */
    long existingApplicationId() {
        return existingApplicationId;
    }
}
