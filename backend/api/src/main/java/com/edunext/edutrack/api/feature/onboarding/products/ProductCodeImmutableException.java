package com.edunext.edutrack.api.feature.onboarding.products;

/**
 * A-124 · an edit that tried to change a product's code.
 *
 * <p>See {@code ObProductService}'s class note: the code is what every human
 * artefact refers to the product by, while the tables refer to it by id, so
 * renaming it renames the product in half the places it appears.
 */
class ProductCodeImmutableException extends RuntimeException {

    ProductCodeImmutableException(String current, String attempted) {
        super("A product's code cannot change. This product is '" + current
                + "' and the request sent '" + attempted + "'.");
    }
}
