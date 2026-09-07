package com.edunext.edutrack.api.feature.onboarding.reports;

import com.edunext.edutrack.api.feature.reports.export.ReportExporter;
import com.edunext.edutrack.api.security.CallerIdentity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
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

import java.io.IOException;
import java.time.LocalDate;
import java.util.Set;

/**
 * B-122 · {@code /onboarding/reports} per {@code contracts/openapi.yaml} —
 * OB-10, the reports hub and its one parameterised runner.
 *
 * <h2>Auth: {@code isAuthenticated()}, with the real gate elsewhere</h2>
 *
 * <p>The interim position every {@code /onboarding/**} route in this codebase
 * holds, restated rather than inferred from the annotation.
 *
 * <p>Which onboarding roles may read a report is not a capability
 * {@code RolePermissions} can express: it is a <em>module</em> role living in
 * the {@code moduleRoles} claim, which {@code JwtAuthoritiesConverter} does not
 * turn into a Spring authority — {@code OnboardingScopeResolver}'s own note. So
 * the decision is made inside {@link ObReportScope} from
 * {@link CallerIdentity#moduleRole}, and a caller with no onboarding standing
 * reaches these routes and gets an empty report with an {@code appliedScope} of
 * "nothing", plus a catalogue whose {@code scopeNote} says so.
 *
 * <p>A caller with no {@code ONBOARDING} entitlement at all should never get
 * that far: A-111's {@code ModuleAccessGuard} answers 404 for them, on the
 * reasoning that a 403 would disclose that the module is deployed. That guard
 * is written and not yet wired into {@code SecurityConfig}, so today such a
 * caller reaches the handler and is scoped to nothing instead. Not a gap this
 * task introduces, and deliberately not one it papers over with a bespoke check
 * here — a second module gate inside a feature package is how the first one
 * comes to be relaxed without anybody noticing.
 */
@RestController
@RequestMapping("/api/v1/onboarding/reports")
@Tag(name = "onboarding")
@PreAuthorize("isAuthenticated()")
class ObReportController {

    /**
     * The two formats {@code runObReport} declares, and PDF is not one of them.
     *
     * <p>The shared engine can write a PDF and this route refuses to. A-118's
     * reasoning is on the contract: "the module's only PDF is B-116's archived
     * sign-off certificate, which is a legal record generated once and stored,
     * not a rendering of a filtered grid." Accepting {@code export=pdf} because
     * the engine happens to support it would put a second kind of onboarding
     * PDF into circulation, indistinguishable at a glance from the one that is
     * evidence.
     */
    private static final Set<ReportExporter.Format> ALLOWED_EXPORTS =
            Set.of(ReportExporter.Format.XLSX, ReportExporter.Format.CSV);

    private final ObReportService reports;
    private final ObReportExportService exports;

    ObReportController(ObReportService reports, ObReportExportService exports) {
        this.reports = reports;
        this.exports = exports;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listObReports", summary = "The onboarding report catalogue (OB-10)")
    ObReportDtos.ObReportCatalogueResponse catalogue(Authentication authentication) {
        return new ObReportDtos.ObReportCatalogueResponse(reports.catalogue(identity(authentication)));
    }

    /**
     * Run a report, as JSON or as a file.
     *
     * <p>404 for a key that is unknown <i>and</i> for one that is declared but
     * unbuilt — see {@link ObReportService#run}. The contract declares one
     * error response for this route, and from the caller's side a key that does
     * not resolve to a runnable report is a key that is not there.
     */
    @GetMapping(path = "/{reportKey}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "runObReport", summary = "Run an onboarding report (OB-10)")
    ResponseEntity<?> run(
            Authentication authentication,
            @PathVariable String reportKey,
            @RequestHeader(name = "If-None-Match", required = false) String ifNoneMatch,
            @RequestParam(required = false) String export,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) Long obClientId,
            @RequestParam(required = false) Long ownerUserId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String rag,
            HttpServletResponse response) throws IOException {

        ObReportService.Rendered rendered = reports
                .run(identity(authentication), reportKey, from, to, productId, obClientId,
                        ownerUserId, rag)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No report is served for '" + reportKey + "'."));

        if (export != null && !export.isBlank()) {
            ReportExporter.Format format = ReportExporter.Format.of(export)
                    .filter(ALLOWED_EXPORTS::contains)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Unsupported export format '" + export + "'. Use xlsx or csv."));

            /*
              Written onto the response rather than returned. This handler
              answers both a JSON body and a file, so its declared type is
              ResponseEntity<?> — and Spring picks the streaming handler from
              the *declared* type, which with the argument erased never matches.
              ReportController records the same version of this going wrong: it
              returned ResponseEntity<StreamingResponseBody> and produced 500
              "Failed to write request" for every format while JSON on the same
              route kept working.

              Deliberately not ETag-negotiated. A 304 for a download leaves the
              browser with nothing to save — the validator matches a body the
              client never kept, because a file went to the filesystem rather
              than to a cache.
            */
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
        return json.body(new ObReportDtos.ObReportResponse(rendered.report(), rendered.meta()));
    }

    /**
     * An authenticated request always has one; {@code @PreAuthorize} has
     * already refused the anonymous case.
     */
    private static CallerIdentity identity(Authentication authentication) {
        return CallerIdentity.of(authentication)
                .orElseThrow(() -> new IllegalStateException(
                        "an authenticated request reached onboarding reports with no CallerIdentity"));
    }

    /**
     * {@code If-None-Match} against one validator, element-wise, with {@code *}
     * matching anything per RFC 9110.
     *
     * <p><b>The fourth copy of this helper in the codebase</b>, after
     * {@code DashboardController}, {@code CalendarController} and
     * {@code ReportController} — whose own note says a shared one belongs in
     * {@code common/} "once a third appears", and which then left it local for
     * the same reason this does. {@code common/} is Stream A's directory and
     * the extraction touches three other features' call sites, so it wants its
     * own review rather than being a side effect of a reports task. Raised in
     * the backlog; a fourth copy is no longer an oversight but it is still not
     * this task's to fix unilaterally.
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
