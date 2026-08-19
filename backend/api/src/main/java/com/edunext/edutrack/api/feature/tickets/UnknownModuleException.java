package com.edunext.edutrack.api.feature.tickets;

/**
 * C-067 · a {@code moduleId} that names nothing, or names a deactivated row.
 *
 * <p>Two messages rather than one, because the two are different facts for the
 * person reading them: "there is no module 47" is a bug in the caller, and
 * "Library is no longer offered" is a decision somebody made that the caller
 * should act on by choosing another.
 */
class UnknownModuleException extends RuntimeException {

    private UnknownModuleException(String message) {
        super(message);
    }

    static UnknownModuleException noSuchModule(Integer moduleId) {
        return new UnknownModuleException("No product module with id %d.".formatted(moduleId));
    }

    static UnknownModuleException deactivated(Integer moduleId) {
        return new UnknownModuleException(
                "Product module %d is no longer offered. Tickets already raised against it keep it."
                        .formatted(moduleId));
    }
}
