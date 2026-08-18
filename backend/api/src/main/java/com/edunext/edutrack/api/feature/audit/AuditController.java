package com.edunext.edutrack.api.feature.audit;

import com.edunext.edutrack.api.feature.reports.export.ReportExporter;
import com.edunext.edutrack.common.pagination.CursorPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;

/**
 * A-071 · {@code GET /api/v1/audit-logs}. Blueprint §7.4 S-16, §13's
 * "{@code GET /audit-logs — Admin only}".
 *
 * <h2>One route, and the other three are the design</h2>
 *
 * <p>There is no {@code POST}, no {@code PATCH} and no {@code DELETE} in this
 * file, and there never will be. That absence is layer 2 of the four that make
 * the table append-only — the same layer {@code /history} and
 * {@code /effort-logs} rely on — and it is asserted rather than trusted:
 * {@code AppendOnlyRulesTest.noRouteOffersToEditTheAuditLog} fails the build if
 * a mutating verb is ever mapped under this path, from any controller. The
 * contract says the same thing in the same words: "Export only; there is no
 * edit or delete route, and there never will be."
 *
 * <p>Writes reach {@code audit_logs} through {@code AuditTrail.record} alone,
 * from {@code AuditInterceptor} and from the login path — never from an HTTP
 * request asking for a row to be written, which is a shape that would let a
 * caller forge one.
 *
 * <h2>403 rather than 404, which is the opposite of the ticket rule</h2>
 *
 * <p>Everywhere else in this product an out-of-scope id answers 404 so that
 * existence is not leaked. Here the contract declares {@code 403}, correctly:
 * this is a collection and not an id, and the existence of an audit log is not
 * a secret — blueprint §17 and §2 both name it in public documentation. A 404
 * would be a lie that gains nothing, and it would hide the one thing worth
 * seeing, because a refusal here is itself audited as
 * {@code ACCESS_DENIED} by {@link AuditInterceptor}.
 */
@RestController
@RequestMapping("/api/v1/audit-logs")
@Tag(name = "audit")
class AuditController {

    private final AuditService audit;
    private final AuditExportService exports;

    AuditController(AuditService audit, AuditExportService exports) {
        this.audit = audit;
        this.exports = exports;
    }

    /**
     * S-16's list, and — on {@code ?export=} — the same rows as a file.
     *
     * <p>The export branch sits after the filters have been read and uses the
     * same {@code Filters} object, so a file can never contain rows the screen
     * would not have shown. An export path assembling its own criteria would be
     * a second place for the filters to be applied, and the one nobody
     * re-checked would be the one that handed over more than it should. Same
     * argument {@code ReportController} makes, and it matters more here.
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('audit.view')")
    @Operation(operationId = "listAuditLogs", summary = "Audit log viewer — Admin only (S-16)")
    ResponseEntity<AuditDtos.ListResponse> list(
            @RequestParam(required = false) Long actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String export,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            @Parameter(hidden = true) HttpServletResponse response) throws IOException {

        AuditService.Filters filters =
                AuditService.Filters.of(actorId, action, entityType, from, to);

        if (export != null && !export.isBlank()) {
            ReportExporter.Format format = AuditExportService.formatOf(export)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Unsupported export format '" + export + "'. Use xlsx or csv."));
            List<AuditDtos.Entry> rows = audit.forExport(filters);
            exports.writeTo(response, format, rows, AuditExportService.describe(filters, rows.size()));
            // Null, not an empty body: the file has already been written onto
            // the response and returning anything here would append to it.
            return null;
        }

        CursorPage<AuditDtos.Entry> page = audit.page(filters, cursor, limit);
        return ResponseEntity.ok(new AuditDtos.ListResponse(page.data(), page.meta()));
    }
}
