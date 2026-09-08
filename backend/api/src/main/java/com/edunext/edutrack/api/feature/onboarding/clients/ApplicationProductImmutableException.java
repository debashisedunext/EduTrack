package com.edunext.edutrack.api.feature.onboarding.clients;

/**
 * B-104 · the product on a purchase cannot be changed — 409
 * {@code ob-application-product-immutable}.
 *
 * <h2>The product is what identifies the purchase, not a field on it</h2>
 *
 * <p>{@code ob_journeys} carries a <b>composite</b> foreign key straight to
 * {@code (ob_client_id, product_id)} rather than an {@code application_id},
 * because the purchase is already unique on that pair — V20260903_1600 sets out
 * why at length. Two things follow, and neither is a policy this class invented:
 *
 * <ul>
 *   <li><b>MySQL would refuse it anyway.</b> Repointing {@code product_id}
 *       breaks {@code fk_ob_journeys_application} for the journey standing on
 *       it, and the caller would get a constraint name instead of a
 *       sentence.</li>
 *   <li><b>Succeeding would be worse than failing.</b> The journey's {@code
 *       template_id} is pinned to the template of the product that was bought,
 *       and it never changes — that is the whole point of pinning. A purchase
 *       that quietly became a different product would leave the client being
 *       onboarded through the old product's steps under the new product's name,
 *       and nothing in the module would report it.</li>
 * </ul>
 *
 * <p>So it is refused loudly rather than ignored — the same call the SPOC panel
 * makes for a consent basis sent beside a {@code false}. A {@code PATCH} body
 * naming a different product is a form that has come apart, and accepting the
 * request while silently keeping the old product is how somebody later concludes
 * the change was made.
 *
 * <p><b>What to do instead is one sentence and two requests</b>, which is why
 * this can be strict: add the new product, and the client is onboarded through
 * both. Removing the old one is not offered — see {@code ObApplicationService}'s
 * note on why B-104 has no {@code DELETE}.
 */
class ApplicationProductImmutableException extends RuntimeException {

    ApplicationProductImmutableException(String currentProductName) {
        super("This purchase is for " + currentProductName + " and cannot be repointed at another "
                + "product. The journey the client is being onboarded through is pinned to this "
                + "product's template version. Add the other product as its own purchase instead.");
    }
}
