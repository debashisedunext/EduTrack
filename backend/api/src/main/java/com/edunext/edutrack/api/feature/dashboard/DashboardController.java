package com.edunext.edutrack.api.feature.dashboard;

import com.edunext.edutrack.api.security.CallerIdentity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
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
}
