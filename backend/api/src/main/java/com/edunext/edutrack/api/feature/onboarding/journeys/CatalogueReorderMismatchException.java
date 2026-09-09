package com.edunext.edutrack.api.feature.onboarding.journeys;

/** C-123 · {@code StepReorderMismatchException}'s own shape, for the catalogue-wide reorder. */
class CatalogueReorderMismatchException extends RuntimeException {

    CatalogueReorderMismatchException(String reason) {
        super("journey template order: " + reason);
    }
}
