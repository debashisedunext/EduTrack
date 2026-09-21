package com.edunext.edutrack.api.feature.onboarding.journeys;

import java.util.List;

import com.edunext.edutrack.api.feature.onboarding.journeys.ObModuleServiceImportShapes.ImportRowError;

/**
 * The file re-uploaded to confirm a Module Service import failed the same
 * checks {@code preview} already ran once — the Implementation Stage master,
 * the product's services, or the file itself changed in between.
 *
 * <p>Carries the row errors so the {@code 422} reads exactly like a failed
 * preview and the dialog can render it with the same component, rather than
 * degrading to a generic error toast at the last step.
 */
class ModuleImportValidationException extends RuntimeException {

    private final List<ImportRowError> errors;

    ModuleImportValidationException(List<ImportRowError> errors) {
        super(errors.size() + " row(s) failed validation");
        this.errors = List.copyOf(errors);
    }

    List<ImportRowError> errors() {
        return errors;
    }
}
