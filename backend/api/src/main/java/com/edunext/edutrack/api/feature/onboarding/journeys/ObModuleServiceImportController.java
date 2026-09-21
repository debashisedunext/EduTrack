package com.edunext.edutrack.api.feature.onboarding.journeys;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * OB-07 · {@code /onboarding/module-service-import} — bulk-author a product's
 * Module Services, their Steps, Tasks and Checklists from one four-column
 * spreadsheet, instead of the designer's add-one-at-a-time flow.
 *
 * <h2>Why its own root, not {@code /journey-templates/...}</h2>
 *
 * <p>The import is not scoped to one Module Service — a file names as many as
 * it likes and creates the ones that do not exist yet — so there is no
 * {@code templateId} to put in the path. Hanging it under
 * {@code /journey-templates/module-service-import} would also sit a literal
 * segment beside {@code /journey-templates/&#123;templateId&#125;}, whose
 * variable is a {@code long}: Spring resolves that in the literal's favour,
 * but only because of a precedence rule a reader has to know. A sibling root
 * needs no such rule.
 *
 * <h2>Why not the generic Import Wizard</h2>
 *
 * <p>{@code feature/imports}' engine upserts one flat entity per row against a
 * single natural key — right for Clients and Resources. A Module Service is a
 * four-level tree whose lower levels have no business key at all (nobody
 * assigns a code to a checklist line), and creating a task correctly has to go
 * through {@link ObJourneyTemplateService} for sequencing, the draft-only
 * guard and stage-group membership. See {@link ObModuleServiceImportService}.
 *
 * <p>Auth: {@code authenticated()} at the class level, matching every other
 * controller in this package; the per-role rule that actually decides this is
 * {@code ObModuleRoleRules}, which holds these three routes to OB Admin as it
 * does every other journey-template write.
 */
@RestController
@RequestMapping("/api/v1/onboarding/module-service-import")
@Tag(name = "onboarding-journeys")
@PreAuthorize("isAuthenticated()")
class ObModuleServiceImportController {

    private final ObModuleServiceImportService service;

    ObModuleServiceImportController(ObModuleServiceImportService service) {
        this.service = service;
    }

    @GetMapping(value = "/template",
            produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    @Operation(operationId = "downloadObModuleServiceImportTemplate",
            summary = "Download the Module Service / Step / Task / Checklist template (OB-07)",
            description = """
                    One sheet of four columns, plus Instructions. The Step dropdown and the \
                    Instructions sheet's Step list are drawn live from the active \
                    Implementation Stages, so the file can never offer a Step the import \
                    would then reject. No product is needed to download it — the columns are \
                    the same for every product, and the product is chosen when the file is \
                    uploaded.""")
    ResponseEntity<byte[]> downloadTemplate() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            service.writeTemplate(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"module-service-import-template.xlsx\"")
                .body(out.toByteArray());
    }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "previewObModuleServiceImport",
            summary = "Validate a Module Service import file without writing anything (OB-07)",
            description = """
                    Every row in the file, checked against the same rules `POST \
                    module-service-import` commits with. Writes nothing regardless of the \
                    outcome: `valid: false` with the row errors, or `valid: true` with the \
                    tree the file describes and, per Module Service, whether confirming will \
                    CREATE a draft or REPLACE an existing one — for the confirm screen to \
                    render before anything is saved.""")
    ObModuleServiceImportDtos.ModuleImportPreviewResponse preview(
            @RequestParam("productId") long productId,
            @RequestParam("file") MultipartFile file) {
        try {
            return ObModuleServiceImportDtos.ModuleImportPreviewResponse.of(
                    service.preview(productId, file.getInputStream()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "commitObModuleServiceImport",
            summary = "Create or replace this product's Module Services from the file (OB-07)",
            description = """
                    `422` with the same row-error shape `preview` returns if the file no \
                    longer validates — re-run `preview` rather than guessing what changed. \
                    A Module Service the file names and the product does not have is created \
                    as a new draft; one that exists as a draft has its entire Step / Task / \
                    Checklist tree replaced; one whose latest version is published is \
                    refused, and the caller begins a revision on it first. Every service in \
                    the file lands in one transaction, so nothing is left half-applied, and \
                    nothing is published.""")
    ObModuleServiceImportDtos.ModuleImportResultResponse commit(
            Authentication caller,
            @RequestParam("productId") long productId,
            @RequestParam("file") MultipartFile file) {
        try {
            return ObModuleServiceImportDtos.ModuleImportResultResponse.of(
                    service.commit(productId, CallerIdentityAccess.requireUserId(caller),
                            file.getInputStream()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
