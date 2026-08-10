package com.edunext.edutrack.api.feature.masters;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

/**
 * B-023 · {@code /masters} calendar endpoints, per
 * {@code contracts/openapi.yaml}.
 *
 * <p>The working week is a singleton resource, so it is {@code GET}/{@code PUT}
 * on a path with no id. It carries an {@code ETag} and requires
 * {@code If-Match} back on the write: two admins editing the working week from
 * different tabs is exactly the lost update CONVENTIONS.md §5 exists for, and
 * here a silently discarded change alters every SLA figure computed afterwards.
 */
@RestController
@RequestMapping("/masters")
@Tag(name = "masters")
class CalendarController {

    private final CalendarService calendar;

    CalendarController(CalendarService calendar) {
        this.calendar = calendar;
    }

    // ------------------------------------------------------------------
    // The calendar read model
    // ------------------------------------------------------------------

    @GetMapping(path = "/holidays", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getWorkingCalendar",
            summary = "Working calendar — org holidays and weekly-off pattern")
    CalendarDtos.CalendarResponse holidays(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return new CalendarDtos.CalendarResponse(calendar.calendar(from, to));
    }

    @PostMapping(path = "/holidays",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "createHoliday", summary = "Add an org or project holiday (S-14)")
    ResponseEntity<CalendarDtos.HolidayResponse> createHoliday(
            @Valid @RequestBody CalendarDtos.HolidayWrite write) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new CalendarDtos.HolidayResponse(calendar.createHoliday(write)));
    }

    @PatchMapping(path = "/holidays/{holidayId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "updateHoliday", summary = "Edit a holiday")
    CalendarDtos.HolidayResponse updateHoliday(@PathVariable long holidayId,
                                               @Valid @RequestBody CalendarDtos.HolidayPatch patch) {
        return calendar.updateHoliday(holidayId, patch)
                .map(CalendarDtos.HolidayResponse::new)
                .orElseThrow(CalendarController::notFound);
    }

    @DeleteMapping("/holidays/{holidayId}")
    @Operation(operationId = "deleteHoliday", summary = "Remove a holiday")
    ResponseEntity<Void> deleteHoliday(@PathVariable long holidayId) {
        if (!calendar.deleteHoliday(holidayId)) {
            throw notFound();
        }
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------
    // The working week
    // ------------------------------------------------------------------

    @GetMapping(path = "/working-calendar", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getWorkingWeek",
            summary = "The org working week — weekly-off pattern and working-day bounds")
    ResponseEntity<CalendarDtos.WorkingWeekResponse> workingWeek() {
        CalendarDtos.WorkingWeek week = calendar.workingWeek();
        return ResponseEntity.ok().eTag(etagOf(week))
                .body(new CalendarDtos.WorkingWeekResponse(week));
    }

    /**
     * {@code If-Match} is required, not optional.
     *
     * <p>A write without one is refused with 428 rather than allowed through:
     * treating a missing precondition as "no conflict" would mean the guard
     * protects only the clients that already opted in, which is the set that
     * needed it least.
     */
    @PutMapping(path = "/working-calendar",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "updateWorkingWeek", summary = "Replace the org working week")
    ResponseEntity<CalendarDtos.WorkingWeekResponse> updateWorkingWeek(
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody CalendarDtos.WorkingWeekUpdate update) {

        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                    "If-Match is required. GET the working week first and send back its ETag.");
        }
        String current = etagOf(calendar.workingWeek());
        if (!matches(ifMatch, current)) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                    "The working week changed since you read it. Reload and reapply your edit.");
        }

        CalendarDtos.WorkingWeek saved = calendar.updateWorkingWeek(update);
        return ResponseEntity.ok().eTag(etagOf(saved))
                .body(new CalendarDtos.WorkingWeekResponse(saved));
    }

    // ------------------------------------------------------------------
    // Resource leave
    // ------------------------------------------------------------------

    @GetMapping(path = "/leaves", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listResourceLeaves",
            summary = "Resource leave, the per-person half of the working calendar")
    CalendarDtos.ResourceLeaveListResponse leaves(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {

        List<CalendarDtos.ResourceLeave> all = calendar.leaves(userId, from, to);
        int size = limit == null ? CalendarService.DEFAULT_LIMIT
                : Math.min(Math.max(limit, 1), CalendarService.MAX_LIMIT);
        int start = decodeCursor(cursor);
        int end = Math.min(start + size, all.size());
        List<CalendarDtos.ResourceLeave> page = start >= all.size() ? List.of() : all.subList(start, end);

        boolean hasMore = end < all.size();
        return new CalendarDtos.ResourceLeaveListResponse(page,
                new CalendarDtos.Meta(hasMore ? encodeCursor(end) : null, hasMore, all.size()));
    }

    @PostMapping(path = "/leaves",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "createResourceLeave", summary = "Record leave for a resource")
    ResponseEntity<CalendarDtos.ResourceLeaveResponse> createLeave(
            @Valid @RequestBody CalendarDtos.ResourceLeaveWrite write) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new CalendarDtos.ResourceLeaveResponse(calendar.createLeave(write)));
    }

    @PatchMapping(path = "/leaves/{leaveId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "updateResourceLeave", summary = "Edit or approve a leave record")
    CalendarDtos.ResourceLeaveResponse updateLeave(@PathVariable long leaveId,
                                                   @Valid @RequestBody CalendarDtos.ResourceLeavePatch patch) {
        return calendar.updateLeave(leaveId, patch)
                .map(CalendarDtos.ResourceLeaveResponse::new)
                .orElseThrow(CalendarController::notFound);
    }

    @DeleteMapping("/leaves/{leaveId}")
    @Operation(operationId = "deleteResourceLeave", summary = "Remove a leave record")
    ResponseEntity<Void> deleteLeave(@PathVariable long leaveId) {
        if (!calendar.deleteLeave(leaveId)) {
            throw notFound();
        }
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * Derived from the content, not from {@code updated_at}.
     *
     * <p>A timestamp-based tag changes when a save rewrites identical values,
     * which would fail a caller's {@code If-Match} for an edit that conflicts
     * with nothing. Hashing what the client actually read means the tag only
     * moves when the answer does.
     */
    private static String etagOf(CalendarDtos.WorkingWeek week) {
        return Integer.toHexString(week.hashCode());
    }

    /** {@code *} matches anything, per RFC 9110. */
    private static boolean matches(String ifMatch, String current) {
        String candidate = ifMatch.trim();
        if ("*".equals(candidate)) {
            return true;
        }
        return candidate.replace("W/", "").replace("\"", "").equals(current);
    }

    private static int decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(
                    new String(java.util.Base64.getDecoder().decode(cursor))));
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }

    private static String encodeCursor(int index) {
        return java.util.Base64.getEncoder().encodeToString(String.valueOf(index).getBytes());
    }

    /** 404, never 403 — CLAUDE.md's no-existence-leak rule. */
    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
    }
}
