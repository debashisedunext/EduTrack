package com.edunext.edutrack.api.feature.reports;

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

    ReportController(ReportService reports) {
        this.reports = reports;
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
    ResponseEntity<ReportDtos.ReportResponse> run(
            Authentication caller,
            @PathVariable String reportKey,
            @RequestHeader(name = "If-None-Match", required = false) String ifNoneMatch,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long resourceId) {

        ReportService.Rendered rendered = reports
                .run(identity(caller), reportKey, from, to, projectId, resourceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No report is served for '" + reportKey + "'."));

        if (rendered.etag() != null && matches(ifNoneMatch, rendered.etag())) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(rendered.etag()).build();
        }

        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (rendered.etag() != null) {
            response = response.eTag(rendered.etag());
        }
        return response.body(new ReportDtos.ReportResponse(rendered.report(), rendered.meta()));
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
