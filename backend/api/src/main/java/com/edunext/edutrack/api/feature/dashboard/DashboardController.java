package com.edunext.edutrack.api.feature.dashboard;

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
 * A-054 · {@code GET /dashboard/summary} — S-05's shell and its three filters.
 *
 * <p>Project, date range and resource, exactly the controls §S-05 draws across
 * the top. All three are optional; the default window is the last 30 days,
 * which is the range the "Daily Task Status" chart is specified over.
 *
 * <p>Served entirely from A-050's summary tables. <b>Never a live
 * {@code COUNT(*)}</b> — CLAUDE.md is absolute about it, and at A-073's 50,000
 * tickets it is the difference between a dashboard and a timeout.
 *
 * <p>Every role reaches this route. What differs is which table answers it and
 * with what scope — see {@link DashboardService}, where "role-aware" is decided.
 * A capability denial here would leave a Developer with no dashboard at all,
 * when what they should get is their own.
 */
@RestController
@RequestMapping("/api/v1/dashboard")
@Tag(name = "dashboard")
class DashboardController {

    private final DashboardService dashboard;
    private final WidgetService widgets;

    DashboardController(DashboardService dashboard, WidgetService widgets) {
        this.dashboard = dashboard;
        this.widgets = widgets;
    }

    @GetMapping(path = "/summary", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "getDashboardSummary", summary = "KPI cards (S-05)")
    DashboardDtos.SummaryResponse summary(
            Authentication caller,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long assigneeId) {

        CallerIdentity identity = CallerIdentity.of(caller)
                .orElseThrow(() -> new IllegalStateException(
                        "an authenticated request reached the dashboard with no CallerIdentity"));

        return new DashboardDtos.SummaryResponse(
                dashboard.summary(identity, projectId, from, to, assigneeId));
    }

    /**
     * A-056 · {@code GET /dashboard/widget/{widgetKey}} — one widget's series.
     *
     * <p>Widgets 7–12 today; the contract's remaining eight keys answer 404
     * until A-057 to A-059 land. <b>404 and not 501</b>: from the client's side
     * a key that does not resolve to a widget is a key that is not there, and
     * the contract declares exactly one error response for this route.
     *
     * <p>Reachable by every role, like the summary beside it. Which table
     * answers, and whether the caller's table can answer this widget at all, is
     * {@link WidgetService}'s — a capability denial here would take the whole
     * dashboard away from a Developer rather than the four charts their summary
     * table has no columns for.
     */
    @GetMapping(path = "/widget/{widgetKey}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "getDashboardWidget", summary = "One widget's series (S-05)")
    ResponseEntity<WidgetDtos.WidgetResponse> widget(
            Authentication caller,
            @PathVariable String widgetKey,
            @RequestHeader(name = "If-None-Match", required = false) String ifNoneMatch,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        CallerIdentity identity = CallerIdentity.of(caller)
                .orElseThrow(() -> new IllegalStateException(
                        "an authenticated request reached the dashboard with no CallerIdentity"));

        WidgetService.Rendered rendered = widgets
                .widget(identity, widgetKey, projectId, from, to)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No widget is served for '" + widgetKey + "'."));

        // The summary tables refresh every five minutes, so a dashboard polling
        // faster than that is asking a question whose answer provably has not
        // moved. The validator says so for the cost of a hash.
        if (rendered.etag() != null && matches(ifNoneMatch, rendered.etag())) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(rendered.etag()).build();
        }

        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (rendered.etag() != null) {
            response = response.eTag(rendered.etag());
        }
        return response.body(new WidgetDtos.WidgetResponse(rendered.widget()));
    }

    /**
     * {@code *} matches anything, per RFC 9110, and a list is matched
     * element-wise — {@code If-None-Match} is defined as a comma-separated set,
     * and a client sending two validators is entitled to a 304 on either.
     * {@code CalendarController} has the same helper for {@code If-Match};
     * the two are near-identical and a shared one belongs in {@code common/}
     * once a third appears, rather than being extracted across two streams'
     * directories for the second.
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
