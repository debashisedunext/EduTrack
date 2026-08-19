package com.edunext.edutrack.api.feature.reports;

import com.edunext.edutrack.api.feature.reports.export.ReportExporter;
import com.edunext.edutrack.api.security.CallerIdentity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;

/**
 * A-063 · S-27's two routes — the catalogue behind the card grid, and the
 * parameterised runner behind the viewer.
 *
 * <p>Both reachable by every role, like the dashboard beside them. §2 gives all
 * six a reports section; what differs is the rows, which {@link ReportScope}
 * decides. A capability denial here would take the section away from a
 * Developer entirely, when what §2 grants them is their own performance.
 *
 * <p>Export ({@code ?export=}) and scheduling are declared in the contract and
 * are <b>not implemented here</b> — they are A-064 and A-065. The parameter is
 * accepted by the contract and ignored by this controller, which is visible in
 * the OpenAPI and deliberate: adding the query parameter later would be a
 * contract change, whereas leaving it declared and unhandled is a gap those
 * tasks close.
 */
@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "reports")
class ReportController {

    private final ReportService reports;
    private final ReportExportService exports;

    ReportController(ReportService reports, ReportExportService exports) {
        this.reports = reports;
        this.exports = exports;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "listReports", summary = "The report catalogue (S-27)")
    ReportDtos.CatalogueResponse catalogue(Authentication caller) {
        return new ReportDtos.CatalogueResponse(reports.catalogue(identity(caller)));
    }

    /**
     * <p>404 for a key that is unknown <i>and</i> for one that is declared but
     * unbuilt — see {@link ReportService#run}. The contract declares exactly one
     * error response for this route, and from the caller's side a key that does
     * not resolve to a runnable report is a key that is not there.
     */
    @GetMapping(path = "/{reportKey}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "runReport", summary = "Run a report (S-27)")
    ResponseEntity<?> run(
            Authentication caller,
            @PathVariable String reportKey,
            @RequestHeader(name = "If-None-Match", required = false) String ifNoneMatch,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long resourceId,
            // B-060 · the three the contract has declared since D-001/A-066 and
            // this handler did not accept. Which report honours which is the
            // catalogue's statement, not this method's — a runner reads the
            // ones its descriptor declares and ignores the rest.
            @RequestParam(required = false) Long clientId,
            @RequestParam(required = false) Long taskTypeId,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String export,
            jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {

        ReportService.Rendered rendered = reports
                .run(identity(caller), reportKey, from, to, projectId, resourceId,
                        new ReportFilters(clientId, taskTypeId, level))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No report is served for '" + reportKey + "'."));

        /*
          A-064 · the same rendered report, written as a file.

          Note where this branch sits: *after* the runner has produced the rows,
          on the identical call a JSON request makes. An export path that
          assembled its own query would be a second place for ReportScope to be
          applied, and the one nobody re-checked would be the one that leaked.
          Here it is not possible to skip it — there is only one run().

          Deliberately not ETag-negotiated. A 304 for a download leaves the
          browser with nothing to save: the validator matches a response body
          the client never kept, because a file was handed to the filesystem
          rather than to a cache. Sending the bytes is the only useful answer.
        */
        if (export != null && !export.isBlank()) {
            ReportExporter.Format format = ReportExporter.Format.of(export)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Unsupported export format '" + export + "'. Use xlsx, csv or pdf."));

            // Written onto the response rather than returned. This handler has
            // to answer both a JSON body and a file, so its declared type is
            // ResponseEntity<?> — and Spring picks the streaming handler from
            // the *declared* type, which with the argument erased never matches.
            // The first version returned ResponseEntity<StreamingResponseBody>
            // and produced 500 "Failed to write request" for all three formats
            // while JSON on the same route kept working. See ReportExportService.
            exports.writeTo(response, format, reportKey, rendered);
            return null;
        }

        if (rendered.etag() != null && matches(ifNoneMatch, rendered.etag())) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(rendered.etag()).build();
        }

        ResponseEntity.BodyBuilder json = ResponseEntity.ok();
        if (rendered.etag() != null) {
            json = json.eTag(rendered.etag());
        }
        return json.body(new ReportDtos.ReportResponse(rendered.report(), rendered.meta()));
    }

    private static CallerIdentity identity(Authentication caller) {
        return CallerIdentity.of(caller)
                .orElseThrow(() -> new IllegalStateException(
                        "an authenticated request reached reports with no CallerIdentity"));
    }

    /**
     * {@code *} matches anything per RFC 9110, and a comma-separated list is
     * matched element-wise.
     *
     * <p>The third near-identical copy of this helper — {@code DashboardController}
     * has it for {@code If-None-Match} and {@code CalendarController} for
     * {@code If-Match}. Dashboard's own comment said a shared one belongs in
     * {@code common/} "once a third appears". This is the third, and extracting
     * it is a change to {@code common/} touching two other features' call sites,
     * so it is named here as owed rather than done as a side effect of a
     * reports task.
     */
    private static boolean matches(String ifNoneMatch, String current) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        for (String candidate : ifNoneMatch.split(",")) {
            String trimmed = candidate.trim();
            if ("*".equals(trimmed)) {
                return true;
            }
            if (trimmed.replace("W/", "").replace("\"", "").equals(current)) {
                return true;
            }
        }
        return false;
    }
}
