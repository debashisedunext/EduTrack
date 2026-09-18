package com.edunext.edutrack.api.feature.onboarding.journeys;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * OB-07 · {@code /onboarding/journey-templates/{templateId}/task-import} —
 * bulk-author a draft Module Service's Tasks, Task List and Document
 * Checklist from a spreadsheet, instead of the designer's add-one-at-a-time
 * flow. See {@link ObJourneyTaskImportService} for why this is a dedicated
 * flow rather than a {@code feature/imports} schema registration: that engine
 * upserts one flat row per entity against a single natural key, and a Module
 * Service's task tree is a three-level hierarchy with no business key to
 * upsert on.
 *
 * <p>Auth: {@code authenticated()} only, matching every other controller in
 * this package — see {@link ObJourneyTemplateController}'s class javadoc for
 * why nothing stronger exists yet.
 */
@RestController
@RequestMapping("/api/v1/onboarding/journey-templates/{templateId}/task-import")
@Tag(name = "onboarding-journeys")
@PreAuthorize("isAuthenticated()")
class ObJourneyTaskImportController {

    private final ObJourneyTaskImportService service;

    ObJourneyTaskImportController(ObJourneyTaskImportService service) {
        this.service = service;
    }

    @GetMapping(value = "/template",
            produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    @Operation(operationId = "downloadObJourneyTaskImportTemplate",
            summary = "Download the Tasks / Task List / Document Checklist template (OB-07)",
            description = """
                    Three sheets — Tasks, Task List, Document Checklist — plus Instructions. \
                    The Stage dropdown and the Instructions sheet's stage list are drawn live \
                    from this template's own stage groups, so the file always names stages \
                    the import will actually accept.""")
    ResponseEntity<byte[]> downloadTemplate(@PathVariable long templateId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            service.writeTemplate(templateId, out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"module-service-tasks-template.xlsx\"")
                .body(out.toByteArray());
    }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "previewObJourneyTaskImport",
            summary = "Validate a task-import file without writing anything (OB-07)",
            description = """
                    Every row in the workbook, checked against the same rules `POST \
                    task-import` commits with. Writes nothing regardless of the outcome: \
                    `valid: false` with the row errors, or `valid: true` with the task tree \
                    the file describes, for the confirm screen to render before anything is \
                    saved.""")
    ObJourneyTaskImportDtos.TaskImportPreviewResponse preview(
            @PathVariable long templateId, @RequestParam("file") MultipartFile file) {
        try {
            return ObJourneyTaskImportDtos.TaskImportPreviewResponse.of(
                    service.preview(templateId, file.getInputStream()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "commitObJourneyTaskImport",
            summary = "Replace this draft's entire task tree with the file's contents (OB-07)",
            description = """
                    `422` with the same row-error shape `preview` returns if the file no \
                    longer validates — re-run `preview` rather than guessing what changed. \
                    `409` if the template has ever been published: only a draft's task tree \
                    can be replaced. Every existing task, Task List entry and Document \
                    Checklist entry on this draft is removed and re-created from the file, in \
                    one transaction, so nothing is left half-applied.""")
    ObJourneyTaskImportDtos.TaskImportResultResponse commit(
            @PathVariable long templateId, @RequestParam("file") MultipartFile file) {
        try {
            return ObJourneyTaskImportDtos.TaskImportResultResponse.of(
                    service.commit(templateId, file.getInputStream()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
