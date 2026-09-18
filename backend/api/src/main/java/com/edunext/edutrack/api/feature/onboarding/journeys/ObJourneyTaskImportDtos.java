package com.edunext.edutrack.api.feature.onboarding.journeys;

import java.util.List;

import com.edunext.edutrack.api.feature.onboarding.journeys.ObJourneyTaskImportShapes.ImportRowError;
import com.edunext.edutrack.api.feature.onboarding.journeys.ObJourneyTaskImportShapes.ImportedDoc;
import com.edunext.edutrack.api.feature.onboarding.journeys.ObJourneyTaskImportShapes.ImportedItem;
import com.edunext.edutrack.api.feature.onboarding.journeys.ObJourneyTaskImportShapes.ImportedTask;
import com.edunext.edutrack.api.feature.onboarding.journeys.ObJourneyTaskImportShapes.TaskImportPreview;
import com.edunext.edutrack.api.feature.onboarding.journeys.ObJourneyTaskImportShapes.TaskImportResult;

/** Wire shapes for {@code /onboarding/journey-templates/{templateId}/task-import}. */
final class ObJourneyTaskImportDtos {
    private ObJourneyTaskImportDtos() {
    }

    record RowError(String sheet, int rowNumber, String message) {
        static RowError of(ImportRowError e) {
            return new RowError(e.sheet(), e.rowNumber(), e.message());
        }
    }

    record ImportedItemDto(String label, boolean mandatory) {
        static ImportedItemDto of(ImportedItem i) {
            return new ImportedItemDto(i.label(), i.mandatory());
        }
    }

    record ImportedDocDto(String label, boolean required) {
        static ImportedDocDto of(ImportedDoc d) {
            return new ImportedDocDto(d.label(), d.required());
        }
    }

    record ImportedTaskDto(String stageGroupName, String name, String description, int tatDays,
                           boolean requiresSignoff, String dependsOnTaskName,
                           List<ImportedItemDto> items, List<ImportedDocDto> docs) {
        static ImportedTaskDto of(ImportedTask t) {
            return new ImportedTaskDto(t.stageGroupName(), t.name(), t.description(), t.tatDays(),
                    t.requiresSignoff(), t.dependsOnTaskName(),
                    t.items().stream().map(ImportedItemDto::of).toList(),
                    t.docs().stream().map(ImportedDocDto::of).toList());
        }
    }

    /** Envelope-wrapped as {@code { data }} per {@code CONVENTIONS.md} §2 — see {@link TaskImportPreviewResponse}. */
    record TaskImportPreviewData(boolean valid, List<RowError> errors, List<ImportedTaskDto> tasks) {
        static TaskImportPreviewData of(TaskImportPreview preview) {
            return new TaskImportPreviewData(preview.valid(),
                    preview.errors().stream().map(RowError::of).toList(),
                    preview.tasks().stream().map(ImportedTaskDto::of).toList());
        }
    }

    record TaskImportPreviewResponse(TaskImportPreviewData data) {
        static TaskImportPreviewResponse of(TaskImportPreview preview) {
            return new TaskImportPreviewResponse(TaskImportPreviewData.of(preview));
        }
    }

    record TaskImportResultData(int taskCount, int itemCount, int docCount) {
        static TaskImportResultData of(TaskImportResult result) {
            return new TaskImportResultData(result.taskCount(), result.itemCount(), result.docCount());
        }
    }

    record TaskImportResultResponse(TaskImportResultData data) {
        static TaskImportResultResponse of(TaskImportResult result) {
            return new TaskImportResultResponse(TaskImportResultData.of(result));
        }
    }
}
