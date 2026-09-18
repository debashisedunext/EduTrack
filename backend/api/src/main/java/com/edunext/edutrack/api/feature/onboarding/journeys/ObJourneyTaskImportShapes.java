package com.edunext.edutrack.api.feature.onboarding.journeys;

import java.util.List;

/**
 * OB-07 · the validated, database-agnostic shape of a task-import file.
 *
 * <p>Shared by {@link ObJourneyTaskImportService} (which produces it from an
 * uploaded workbook) and {@link ObJourneyTemplateService#replaceTasksFromImport}
 * (which consumes it) so neither has to reach into the other's nested types —
 * this is the one thing both need to agree on.
 */
final class ObJourneyTaskImportShapes {
    private ObJourneyTaskImportShapes() {
    }

    record ImportedItem(String label, boolean mandatory) {
    }

    record ImportedDoc(String label, boolean required) {
    }

    /**
     * One row of the Tasks sheet, with the Task List and Document Checklist
     * rows that named it already attached.
     *
     * @param stageGroupName null for "Ungrouped"; otherwise must match an
     *                       existing stage group of the target template —
     *                       checked by {@link ObJourneyTaskImportService},
     *                       not here
     * @param dependsOnTaskName null for "runs in parallel"; otherwise the
     *                          {@code name} of a task earlier in the same file
     */
    record ImportedTask(String stageGroupName, String name, String description, int tatDays,
                        boolean requiresSignoff, String dependsOnTaskName,
                        List<ImportedItem> items, List<ImportedDoc> docs) {
    }

    record ImportRowError(String sheet, int rowNumber, String message) {
    }

    record TaskImportPreview(boolean valid, List<ImportRowError> errors, List<ImportedTask> tasks) {
        static TaskImportPreview invalid(List<ImportRowError> errors) {
            return new TaskImportPreview(false, List.copyOf(errors), List.of());
        }

        static TaskImportPreview valid(List<ImportedTask> tasks) {
            return new TaskImportPreview(true, List.of(), tasks);
        }
    }

    record TaskImportResult(int taskCount, int itemCount, int docCount) {
    }
}
