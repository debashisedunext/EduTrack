package com.edunext.edutrack.api.feature.onboarding.clients;

import java.util.List;

/**
 * B-102 · {@code ob-product-no-template} — 409. A purchased product has no
 * active journey template, so there is nothing to instantiate.
 *
 * <h2>Checked before the create rather than caught out of it</h2>
 *
 * <p>{@code ObJourneyInstantiationService} raises its own exception for this,
 * and that exception is package-private to C-103's package — deliberately, and
 * not something to widen from here. Checking first is better than catching
 * anyway, for a reason that has nothing to do with visibility: a wizard
 * multi-select can carry three products, and instantiating them one at a time
 * would name the first unbuildable one and roll back, so a boarder fixes one
 * product per submission. This names <b>all</b> of them at once, which is the
 * same argument {@code ClientWriteService} makes for naming every missing
 * project id.
 *
 * <p>A template deactivated between this check and the instantiation is a race
 * the transaction settles by rolling the whole create back. It is not caught
 * and turned into this 409, because at that point the check <em>did</em> pass
 * and pretending otherwise would report a state that was never true.
 */
class ProductWithoutTemplateException extends RuntimeException {

    private final transient List<Long> productIds;

    ProductWithoutTemplateException(List<Long> productIds) {
        super("no published journey template exists for product"
                + (productIds.size() == 1 ? " " : "s ")
                + productIds.stream().map(String::valueOf).reduce((a, b) -> a + ", " + b).orElse("")
                + ". An onboarding admin publishes one on OB-07 before this product can be sold.");
        this.productIds = List.copyOf(productIds);
    }

    List<Long> productIds() {
        return productIds;
    }
}
