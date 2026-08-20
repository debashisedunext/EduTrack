package com.edunext.edutrack.api.feature.reports;

import com.edunext.edutrack.api.security.CallerIdentity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    TimesheetController(TimesheetService timesheets) {
        this.timesheets = timesheets;
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
}
