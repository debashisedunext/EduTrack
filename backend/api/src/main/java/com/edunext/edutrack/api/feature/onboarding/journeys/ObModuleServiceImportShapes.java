package com.edunext.edutrack.api.feature.onboarding.journeys;

import java.util.List;

/**
 * OB-07 · the validated, database-agnostic shape of a Module Service import
 * file.
 *
 * <p>Shared by {@link ObModuleServiceImportService} (which produces it from an
 * uploaded workbook) and {@link ObJourneyTemplateService#replaceTasksFromImport}
 * (which consumes {@link TaskToWrite}), so neither reaches into the other's
 * nested types.
 *
 * <h2>Four columns, and nothing else</h2>
 *
 * <p>The file carries Module Service, Step, Task and an optional Checklist
 * label. Everything else a {@code ob_journey_template_steps} row needs — TAT,
 * owner, sign-off, dependency — is a fixed default applied by
 * {@link ObJourneyTemplateService}, not a field here. There is deliberately no
 * dependency in this shape: every imported task runs in parallel, and an
 * author who wants a chain sets it in the designer afterwards.
 */
final class ObModuleServiceImportShapes {
    private ObModuleServiceImportShapes() {
    }

    /**
     * What committing the file will do to one Module Service named in it.
     *
     * <p>Decided during validation, shown on the preview, and the reason a
     * published service is a row error rather than a silent no-op: an admin
     * re-uploading a corrected file needs to know which of their services are
     * about to be rewritten before anything is.
     */
    enum ServiceAction {
        /** No version of this service exists for the product yet. */
        CREATE,
        /** An unpublished draft exists; its whole task tree is replaced. */
        REPLACE
    }

    /** Checklist labels are plain strings — the file has no type or flag column. */
    record ImportedTask(String name, List<String> checklist) {
    }

    record ImportedStep(String name, List<ImportedTask> tasks) {
    }

    record ImportedService(String name, ServiceAction action, List<ImportedStep> steps) {
    }

    record ImportRowError(String sheet, int rowNumber, String message) {
    }

    record ModuleImportPreview(boolean valid, List<ImportRowError> errors, List<ImportedService> services) {
        static ModuleImportPreview invalid(List<ImportRowError> errors) {
            return new ModuleImportPreview(false, List.copyOf(errors), List.of());
        }

        static ModuleImportPreview valid(List<ImportedService> services) {
            return new ModuleImportPreview(true, List.of(), List.copyOf(services));
        }
    }

    record ModuleImportResult(int servicesCreated, int servicesReplaced, int stepCount,
                              int taskCount, int checklistCount) {
    }

    /**
     * One task with its stage group already resolved to an id — what
     * {@link ObJourneyTemplateService#replaceTasksFromImport} consumes.
     *
     * <p>The resolution (and the {@code ensureStageGroup} that may have had to
     * bind the group first) happens in {@link ObModuleServiceImportService},
     * so the template service is handed ids and never has to re-interpret a
     * name from a spreadsheet.
     */
    record TaskToWrite(long stageGroupId, String name, List<String> checklist) {
    }
}
