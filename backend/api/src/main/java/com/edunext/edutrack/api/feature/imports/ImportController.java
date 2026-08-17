package com.edunext.edutrack.api.feature.imports;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

/**
 * B-031 · S-34's first endpoint, and the first route in this package.
 *
 * <p>B-030 built the engine and deliberately shipped no controller: each
 * endpoint belongs to the step that introduces it, and an unreachable route is
 * indistinguishable from a working one until somebody calls it. Steps 2 to 5
 * (B-032…B-035) add their handlers here.
 *
 * <h2>Permissions</h2>
 *
 * <p><b>{@code master.write} — Admin alone</b>, like the three routes that will
 * join it on this path. §7.4 heads the module "Master data module (Admin only)"
 * and S-34 is inside it; the wizard's remaining steps write the client master in
 * bulk, and a download button on a screen no other role can open is not a
 * capability worth granting separately.
 *
 * <p>The counter-argument is worth recording because it is a good one: this
 * template contains <em>no organisation data at all</em> — column headings, the
 * enum values already public through the client list's own filters, and a
 * fictional example row. Nothing about the tenant is in it, so a wider rule
 * would leak nothing. It is refused anyway on the ground that the file's only
 * use is a screen Admin alone can reach, and a route whose permission is looser
 * than its screen is how a screen quietly acquires a second entrance.
 *
 * <p><b>403, not 404, for a role without the capability</b> — CLAUDE.md's
 * no-existence-leak rule is about rows, and a blank template is not a row.
 * Recorded in {@code check-conventions.py}'s {@code ROWLESS_403} with that
 * reason, the same way B-025 records the client writes.
 *
 * <p><b>404 for an unregistered schema</b>, from {@link ImportExceptionHandler}.
 * {@code /imports/users/template} is a contract-declared path that answers 404
 * today, because B-038's registration does not exist yet — the response is
 * honest rather than a placeholder, and it will start working the day that
 * {@code @Component} lands, with nothing here to change.
 *
 * <p>The {@code /api/v1} prefix is spelled out. Nothing declares it globally.
 */
@RestController
@RequestMapping("/api/v1/imports")
@Tag(name = "imports")
class ImportController {

    /** Matches the contract's response media type, not {@code application/octet-stream}. */
    private static final String XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final ImportSchemaRegistry registry;
    private final ImportTemplateWriter templates;
    private final ImportUploadService uploads;

    ImportController(ImportSchemaRegistry registry,
                     ImportTemplateWriter templates,
                     ImportUploadService uploads) {
        this.registry = registry;
        this.templates = templates;
        this.uploads = uploads;
    }

    /**
     * Blueprint §4B.3 step 1 — the pre-formatted workbook.
     *
     * <p>Generated per request rather than built once and cached. A template is
     * a function of the registration, and the registration is a class: caching
     * would mean a column added in a schema and a template still handing out the
     * old headers until somebody remembered to evict it. The cost is a few
     * kilobytes of XML on a button nobody presses twice in a minute.
     *
     * <p>Written straight to the response stream — a {@code byte[]} would hold
     * the whole workbook twice for no benefit, and the writer already streams.
     */
    @GetMapping(path = "/{schema}/template", produces = XLSX)
    @PreAuthorize("hasAuthority('master.write')")
    @Operation(operationId = "downloadImportTemplate",
            summary = "Download the .xlsx import template for a schema (S-34)")
    void template(@PathVariable String schema, HttpServletResponse response) throws IOException {
        ImportSchemaDefinition definition = registry.resolve(schema);

        response.setStatus(HttpStatus.OK.value());
        response.setContentType(XLSX);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + ImportTemplateWriter.filename(definition) + "\"");

        templates.write(definition, response.getOutputStream());
        response.flushBuffer();
    }

    /**
     * Blueprint §4B.3 step 2 — upload and parse.
     *
     * <p>Nothing is written. The file is read, one sheet of it is staged, and the
     * response describes what was found so B-033 can map it. A user who closes
     * the tab here has changed nothing; the staged copy expires on its own.
     *
     * <p><b>{@code sheet} is how the multi-sheet selector works.</b> The response
     * lists every sheet in the workbook and names the one that was read; choosing
     * a different one re-posts the same file naming it. The alternative — a
     * "re-read what you staged" route — was considered and rejected: it means
     * holding the original bytes of every open upload for the staging TTL, on a
     * store whose own javadoc calls itself a stopgap, to save a transfer of a
     * file the browser is still holding and the user has just asked to re-read.
     *
     * <p><b>{@code replaces} is the housekeeping that makes that affordable.</b>
     * Each upload takes a staging slot and there are twenty of them; without it,
     * flicking through the sheets of a workbook would consume them one per look.
     *
     * <p>{@code @RequestPart} rather than {@code @RequestParam} for the file, so
     * a request that is not multipart at all fails as a bad request rather than
     * arriving with a null file and being reported as an empty one.
     *
     * <p><b>One seam worth knowing about.</b> §4B.3's 5 MB limit is enforced
     * here and answers a problem document naming it. A file over
     * {@code spring.servlet.multipart.max-file-size} — 10 MB, set for §4B.4's
     * attachments and shared by every upload in the application — never reaches
     * this method at all: the container refuses it while resolving the multipart
     * body, <em>before</em> a handler has been chosen. A
     * {@code MaxUploadSizeExceededException} handler on
     * {@link ImportExceptionHandler} would therefore never fire, because
     * controller-scoped advice is matched against a handler that does not exist
     * yet — it was written, found to be unreachable, and removed rather than left
     * in looking like coverage. What answers instead is Spring's own
     * problem-details handler, enabled globally by A-020, so the caller still
     * gets RFC 9457 — with the framework's wording rather than ours. Between the
     * two, the only files that land there are over 10 MB, and the step-2 screen
     * refuses anything over 5 MB before sending it.
     */
    @PostMapping(path = "/{schema}/upload",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('master.write')")
    @Operation(operationId = "uploadImportFile",
            summary = "Upload and parse a spreadsheet for import (S-34 step 2)")
    ImportDtos.UploadResponse upload(
            @PathVariable String schema,
            @RequestPart("file") MultipartFile file,
            @RequestParam(name = "sheet", required = false) String sheet,
            @RequestParam(name = "replaces", required = false) UUID replaces) throws IOException {

        return new ImportDtos.UploadResponse(uploads.upload(
                schema,
                safeFileName(file.getOriginalFilename()),
                file.getSize(),
                file.getBytes(),
                sheet,
                replaces));
    }

    /**
     * The submitted name, reduced to its last segment.
     *
     * <p>Internet Explorer sent whole paths and some upload libraries still do,
     * so {@code getOriginalFilename()} is caller-controlled text that may contain
     * separators. Nothing here opens a file by this name — it is echoed to the
     * screen and used to pick a reader — but a name is a string that tends to
     * reach a file system eventually, and reducing it at the boundary costs one
     * line now rather than an audit later.
     *
     * <p>Cut by hand rather than through {@code Paths.get(…).getFileName()},
     * which throws on characters that are legal in an upload and illegal in a
     * Windows path (a quote, an asterisk) — a 500 on a file somebody is entitled
     * to import.
     */
    private static String safeFileName(String submitted) {
        if (submitted == null || submitted.isBlank()) {
            return "upload";
        }
        String name = submitted.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).trim();
        return name.isBlank() ? "upload" : name;
    }
}
