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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;

/**
 * B-063 · {@code GET /users/{userId}/timesheet} — blueprint §21.
 *
 * <p>Mapped under {@code /users} and living in {@code feature/reports}, for the
 * reasons {@link TimesheetService} sets out — the first of which is that the
 * visibility rule this route needs already exists in this package and must not
 * be copied into a second one.
 *
 * <p>Reachable by every authenticated role, because a Developer is entitled to
 * their own week even though they are entitled to nobody else's.
 * <b>Whose week they may open is a row question</b>, answered by
 * {@code Profile360Repository.isVisibleTo} and reported as a 404 — never a 403,
 * which would confirm a user id exists and let anyone enumerate the staff list.
 */
@RestController
@Tag(name = "users")
class TimesheetController {

    private final TimesheetService timesheets;
    private final TimesheetApprovalService approvals;

    TimesheetController(TimesheetService timesheets, TimesheetApprovalService approvals) {
        this.timesheets = timesheets;
        this.approvals = approvals;
    }

    @GetMapping(path = "/api/v1/users/{userId}/timesheet", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "getUserTimesheet", summary = "A resource's week, stage by stage (B-063)")
    TimesheetDtos.TimesheetResponse timesheet(
            Authentication caller,
            @PathVariable long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekOf) {

        CallerIdentity identity = CallerIdentity.of(caller)
                .orElseThrow(() -> new IllegalStateException(
                        "an authenticated request reached the timesheet with no CallerIdentity"));

        return timesheets.week(identity, userId, weekOf)
                .map(TimesheetDtos.TimesheetResponse::new)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No timesheet is available for user " + userId + "."));
    }

    /**
     * B-065 · {@code hasAnyRole('ADMIN','PM')} is the one rowless refusal this
     * feature answers with {@code 403} — see {@link TimesheetApprovalService}'s
     * header for why the row question underneath it is a 404 instead.
     */
    @PostMapping(path = "/api/v1/users/{userId}/timesheet/approval", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','PM')")
    @Operation(operationId = "approveTimesheet", summary = "A manager marks a resource's week reviewed (B-065)")
    ResponseEntity<TimesheetDtos.TimesheetApprovalResponse> approve(
            Authentication caller,
            @PathVariable long userId,
            /*
             * Accepted per CONVENTIONS.md §4 and not yet honoured, on
             * EffortLogController's identical note: the 24-hour replay store
             * is A-035 and does not exist. Unlike that route, a retried
             * request here is not silently harmful — uq_timesheet_approvals_week
             * already turns a genuine double-submit into a 409 naming the
             * first approval rather than a second row.
             */
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekOf,
            @RequestBody(required = false) TimesheetDtos.TimesheetApprovalRequest request) {

        CallerIdentity identity = CallerIdentity.of(caller)
                .orElseThrow(() -> new IllegalStateException(
                        "an authenticated request reached the timesheet approval with no CallerIdentity"));

        String note = request == null ? null : request.note();

        return approvals.approve(identity, userId, weekOf, note)
                .map(a -> ResponseEntity.status(HttpStatus.CREATED)
                        .body(new TimesheetDtos.TimesheetApprovalResponse(a)))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No timesheet is available for user " + userId + "."));
    }
}
