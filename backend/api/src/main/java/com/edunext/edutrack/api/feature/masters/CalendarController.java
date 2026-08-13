package com.edunext.edutrack.api.feature.masters;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
 *
 * <h2>Permissions (A-033)</h2>
 *
 * <p><b>Reads are open to all six roles; writes require {@code master.write},
 * which only Admin holds.</b> Blueprint §2 puts "Master data (task types, SLA,
 * workflow, holidays)" in the Admin column and nowhere else. The asymmetry is
 * the point: every role's tickets are dated by this calendar — a Friday-18:00
 * ticket with a 4-hour SLA must not breach on Saturday morning — so a Developer
 * who cannot read the holiday list cannot be shown why their planned close date
 * is Monday. Reading the org's non-working days is not a capability worth
 * withholding; changing them moves every SLA figure in the system.
 *
 * <p><b>{@code PATCH /leaves} is an open question, resolved conservatively.</b>
 * Its summary says "Edit or approve a leave record", and approval reads like
 * something a reporting manager does — but §2 has no leave row and there is no
 * {@code leave.approve} code in the {@code permissions} table, so there is
 * nothing narrower to assert. Leave is treated as the per-person half of the
 * working-calendar master and gated on {@code master.write}, which makes leave
 * approval Admin-only. <b>If a PM is meant to approve their reportees' leave,
 * that is a new §2 row, a new permission code and a migration — not a widened
 * check here.</b> Widening it locally is how a permission model stops matching
 * the matrix it claims to implement. Raised with Stream B rather than decided
 * quietly.
 *
 * <p>Row scope stays A-034's: {@code GET /leaves} takes a {@code userId} filter
 * and does not yet restrict whose leave a caller may read.
 *
 * <p><b>The {@code /api/v1} prefix was missing until B-010.</b> Nothing declares
 * it globally — there is no context path and no {@code configurePathMatch}
 * prefix — so every other controller spells it out and this one did not, leaving
 * all nine calendar operations served at {@code /masters/…} while the generated
 * client, the MSW handlers and the contract all call {@code /api/v1/masters/…}.
 * Every one of them would have 404'd. It survived review because
 * {@code CalendarControllerTest} invokes the controller as a plain object, which
 * never consults the request mapping; {@code MasterRoutesTest} now pins the
 * prefix for both controllers in this feature.
 */
@RestController
@RequestMapping("/api/v1/masters")
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
    @PreAuthorize("isAuthenticated()")
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
    @PreAuthorize("hasAuthority('master.write')")
    @Operation(operationId = "createHoliday", summary = "Add an org or project holiday (S-14)")
    ResponseEntity<CalendarDtos.HolidayResponse> createHoliday(
            @Valid @RequestBody CalendarDtos.HolidayWrite write) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new CalendarDtos.HolidayResponse(calendar.createHoliday(write)));
    }

    @PatchMapping(path = "/holidays/{holidayId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('master.write')")
    @Operation(operationId = "updateHoliday", summary = "Edit a holiday")
    CalendarDtos.HolidayResponse updateHoliday(@PathVariable long holidayId,
                                               @Valid @RequestBody CalendarDtos.HolidayPatch patch) {
        return calendar.updateHoliday(holidayId, patch)
                .map(CalendarDtos.HolidayResponse::new)
                .orElseThrow(CalendarController::notFound);
    }

    @DeleteMapping("/holidays/{holidayId}")
    @PreAuthorize("hasAuthority('master.write')")
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
    @PreAuthorize("isAuthenticated()")
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
    // The single highest-leverage master write in the system: this is the
    // denominator of every SLA and duration figure EduTrack computes.
    @PreAuthorize("hasAuthority('master.write')")
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
    // Read is open: the assignee picker and the planned-close-date preview both
    // depend on knowing who is away. Whose leave you may read is A-034's.
    @PreAuthorize("isAuthenticated()")
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
    // master.write, conservatively — see the class javadoc's open question.
    @PreAuthorize("hasAuthority('master.write')")
    @Operation(operationId = "createResourceLeave", summary = "Record leave for a resource")
    ResponseEntity<CalendarDtos.ResourceLeaveResponse> createLeave(
            @Valid @RequestBody CalendarDtos.ResourceLeaveWrite write) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new CalendarDtos.ResourceLeaveResponse(calendar.createLeave(write)));
    }

    @PatchMapping(path = "/leaves/{leaveId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    // The "or approve" in this summary is the open question. master.write until
    // §2 grows a leave row — see the class javadoc.
    @PreAuthorize("hasAuthority('master.write')")
    @Operation(operationId = "updateResourceLeave", summary = "Edit or approve a leave record")
    CalendarDtos.ResourceLeaveResponse updateLeave(@PathVariable long leaveId,
                                                   @Valid @RequestBody CalendarDtos.ResourceLeavePatch patch) {
        return calendar.updateLeave(leaveId, patch)
                .map(CalendarDtos.ResourceLeaveResponse::new)
                .orElseThrow(CalendarController::notFound);
    }

    @DeleteMapping("/leaves/{leaveId}")
    @PreAuthorize("hasAuthority('master.write')")
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
