package com.edunext.edutrack.api.feature.onboarding.journeys;

import java.util.List;

import com.edunext.edutrack.api.feature.onboarding.journeys.ObModuleServiceImportShapes.ImportRowError;
import com.edunext.edutrack.api.feature.onboarding.journeys.ObModuleServiceImportShapes.ImportedService;
import com.edunext.edutrack.api.feature.onboarding.journeys.ObModuleServiceImportShapes.ImportedStep;
import com.edunext.edutrack.api.feature.onboarding.journeys.ObModuleServiceImportShapes.ImportedTask;
import com.edunext.edutrack.api.feature.onboarding.journeys.ObModuleServiceImportShapes.ModuleImportPreview;
import com.edunext.edutrack.api.feature.onboarding.journeys.ObModuleServiceImportShapes.ModuleImportResult;

/**
 * Wire shapes for {@code /onboarding/module-service-import}.
 *
 * <p>Every record here is prefixed {@code ModuleImport}, and that is not
 * decoration: the OpenAPI conformance tests key a schema on a record's
 * <b>simple class name</b>, so a second {@code RowError} anywhere in the
 * application silently swaps two wire shapes. Renaming is the fix; an
 * annotation is not.
 */
final class ObModuleServiceImportDtos {
    private ObModuleServiceImportDtos() {
    }

    record ModuleImportRowError(String sheet, int rowNumber, String message) {
        static ModuleImportRowError of(ImportRowError e) {
            return new ModuleImportRowError(e.sheet(), e.rowNumber(), e.message());
        }
    }

    record ModuleImportTaskDto(String name, List<String> checklist) {
        static ModuleImportTaskDto of(ImportedTask t) {
            return new ModuleImportTaskDto(t.name(), t.checklist());
        }
    }

    record ModuleImportStepDto(String name, List<ModuleImportTaskDto> tasks) {
        static ModuleImportStepDto of(ImportedStep s) {
            return new ModuleImportStepDto(s.name(),
                    s.tasks().stream().map(ModuleImportTaskDto::of).toList());
        }
    }

    /**
     * @param action {@code CREATE} or {@code REPLACE} — what confirming will do
     *               to this service. The preview shows it per row so an admin
     *               sees which of their drafts is about to be rewritten before
     *               anything is.
     */
    record ModuleImportServiceDto(String name, String action, List<ModuleImportStepDto> steps) {
        static ModuleImportServiceDto of(ImportedService s) {
            return new ModuleImportServiceDto(s.name(), s.action().name(),
                    s.steps().stream().map(ModuleImportStepDto::of).toList());
        }
    }

    /** Envelope-wrapped as {@code { data }} per {@code CONVENTIONS.md} §2. */
    record ModuleImportPreviewData(boolean valid, List<ModuleImportRowError> errors,
                                   List<ModuleImportServiceDto> services) {
        static ModuleImportPreviewData of(ModuleImportPreview preview) {
            return new ModuleImportPreviewData(preview.valid(),
                    preview.errors().stream().map(ModuleImportRowError::of).toList(),
                    preview.services().stream().map(ModuleImportServiceDto::of).toList());
        }
    }

    record ModuleImportPreviewResponse(ModuleImportPreviewData data) {
        static ModuleImportPreviewResponse of(ModuleImportPreview preview) {
            return new ModuleImportPreviewResponse(ModuleImportPreviewData.of(preview));
        }
    }

    record ModuleImportResultData(int servicesCreated, int servicesReplaced, int stepCount,
                                  int taskCount, int checklistCount) {
        static ModuleImportResultData of(ModuleImportResult result) {
            return new ModuleImportResultData(result.servicesCreated(), result.servicesReplaced(),
                    result.stepCount(), result.taskCount(), result.checklistCount());
        }
    }

    record ModuleImportResultResponse(ModuleImportResultData data) {
        static ModuleImportResultResponse of(ModuleImportResult result) {
            return new ModuleImportResultResponse(ModuleImportResultData.of(result));
        }
    }
}
