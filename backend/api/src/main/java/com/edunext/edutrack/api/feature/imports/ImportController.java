package com.edunext.edutrack.api.feature.imports;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

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

    ImportController(ImportSchemaRegistry registry, ImportTemplateWriter templates) {
        this.registry = registry;
        this.templates = templates;
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
}
