package com.edunext.edutrack.api.feature.onboarding.journeys;

import java.util.List;

import com.edunext.edutrack.api.feature.onboarding.journeys.ObJourneyTaskImportShapes.ImportRowError;

/**
 * The file re-uploaded to confirm a task import failed the same checks
 * {@code preview} already ran once — the stage groups or the file itself
 * changed in between. Carries the row errors so the {@code 422} response
 * reads exactly like a failed preview.
 */
class TaskImportValidationException extends RuntimeException {

    private final List<ImportRowError> errors;

    TaskImportValidationException(List<ImportRowError> errors) {
        super(errors.size() + " row(s) failed validation");
        this.errors = errors;
    }

    List<ImportRowError> errors() {
        return errors;
    }
}
