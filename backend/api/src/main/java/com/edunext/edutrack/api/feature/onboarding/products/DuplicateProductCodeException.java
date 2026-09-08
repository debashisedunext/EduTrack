package com.edunext.edutrack.api.feature.onboarding.products;

/**
 * A-124 · a product code the catalogue already holds.
 *
 * <p>Carries the code so the handler can key the 409 on the field. The
 * alternative — letting the unique index refuse it — produces a message naming
 * a MySQL constraint, which is the mistake {@code createClient} was corrected
 * for one master over.
 */
class DuplicateProductCodeException extends RuntimeException {

    private final String code;

    DuplicateProductCodeException(String code) {
        super("A product with code '" + code + "' already exists");
        this.code = code;
    }

    String code() {
        return code;
    }
}
