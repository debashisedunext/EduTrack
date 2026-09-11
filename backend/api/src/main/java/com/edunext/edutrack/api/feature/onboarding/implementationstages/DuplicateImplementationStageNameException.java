package com.edunext.edutrack.api.feature.onboarding.implementationstages;

/**
 * A stage name the master already holds.
 *
 * <p>Carries the name so the handler can key the 409 on the field.
 * {@code DuplicateProductCodeException} one package over records why the
 * unique index is not left to refuse it: the message would name a MySQL
 * constraint, which is not something a caller can act on.
 */
class DuplicateImplementationStageNameException extends RuntimeException {

    private final String name;

    DuplicateImplementationStageNameException(String name) {
        super("An implementation stage named '" + name + "' already exists");
        this.name = name;
    }

    String name() {
        return name;
    }
}
