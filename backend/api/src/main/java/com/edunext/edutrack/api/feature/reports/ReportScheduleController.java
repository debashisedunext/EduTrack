package com.edunext.edutrack.api.feature.reports;

import com.edunext.edutrack.api.security.CallerIdentity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * A-065 · §7.8's "All reports schedulable by email (daily/weekly/monthly)".
 *
 * <h2>Four routes where D-001 declared one</h2>
 *
 * <p>The contract carried {@code POST /reports/schedule} answering a bare
 * {@code 201} with no body, and nothing else. That is not a shippable feature —
 * it is a subscription with no unsubscribe: no way to see what you have
 * scheduled, no way to stop it, and no way to reach the file it produced. The
 * three added here are the minimum that makes the first one honest, and the
 * contract now carries all four.
 *
 * <h2>Reachable by every role, like the reports themselves</h2>
 *
 * <p>{@code isAuthenticated()} rather than a capability, matching
 * {@link ReportController} one file over: §2 gives all six roles a reports
 * section and {@link ReportScope} decides the rows. A Developer scheduling
 * their own scorecard is exactly what §2's "Own perf." grants, and a capability
 * check here would take that away.
 *
 * <p>What each role may <em>see</em> is unchanged and unchangeable by
 * scheduling: every run goes through {@link ReportService#run} under the
 * owner's current identity, so a schedule can never return a row its owner
 * could not have opened in the viewer that morning.
 */
@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "reports")
class ReportScheduleController {

    private final ReportScheduleService schedules;
    private final ReportFileStore files;

    ReportScheduleController(ReportScheduleService schedules, ReportFileStore files) {
        this.schedules = schedules;
        this.files = files;
    }

    /**
     * The route D-001 declared, now answering with what it created.
     *
     * <p>{@code 201} with the schedule rather than an empty body: the client
     * needs the id to offer "cancel" without a second round trip, and
     * {@code nextRunAt} is the answer to the only question somebody has after
     * pressing Schedule — when will this actually arrive.
     */
    @PostMapping(path = "/schedule", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "scheduleReport", summary = "Schedule a recurring report email")
    ReportScheduleDtos.ScheduleResponse schedule(
            Authentication caller,
            @RequestBody ReportScheduleDtos.ScheduleRequest request) {
        return new ReportScheduleDtos.ScheduleResponse(schedules.create(identity(caller), request));
    }

    /**
     * The caller's own schedules, cancelled ones included.
     *
     * <p>Cancelled rows stay in the list because "why did this stop arriving"
     * is a question the screen has to be able to answer, and a row that
     * disappears on cancel answers it with silence.
     */
    @GetMapping(path = "/schedules", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "listReportSchedules", summary = "My scheduled reports")
    ReportScheduleDtos.ScheduleListResponse mine(Authentication caller) {
        return new ReportScheduleDtos.ScheduleListResponse(schedules.mine(identity(caller)));
    }

    /**
     * Cancel one.
     *
     * <p>{@code 404} for a schedule that is not the caller's, never {@code 403}
     * — §2's rule for an out-of-scope id, and it holds here for the reason it
     * holds for a ticket: a 403 would confirm that schedule 41 exists and
     * belongs to somebody, which is a fact worth nothing to the owner and
     * something to anybody enumerating.
     */
    @DeleteMapping(path = "/schedules/{id}")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "cancelReportSchedule", summary = "Cancel a scheduled report")
    void cancel(Authentication caller, @PathVariable long id) {
        schedules.cancel(identity(caller), id);
    }

    /**
     * The file one run produced.
     *
     * <h2>This is the route the emailed link points at, and why it is a route
     * at all</h2>
     *
     * <p>The mail carries no attachment and no signed URL. It carries a link to
     * here, which means the bytes are handed over only after Spring Security
     * has established who is asking and this method has confirmed the schedule
     * is theirs — <em>at the moment of the click</em>, not at the moment the
     * mail was sent. An attachment cannot make that check, and a presigned URL
     * makes it once and then keeps working for anybody the mail is forwarded to.
     *
     * <p>{@code 404} covers all three of "no such schedule", "not yours" and
     * "the file has aged out of the object store". The first two must be
     * indistinguishable for the reason above; the third is genuinely the same
     * answer — there is nothing here to give you.
     */
    @GetMapping("/schedules/{id}/runs/{runId}/download")
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "downloadScheduledReport", summary = "Download a scheduled run's file")
    ResponseEntity<byte[]> download(Authentication caller,
                                    @PathVariable long id,
                                    @PathVariable long runId) {
        ReportScheduleService.StoredFile file = schedules.fileFor(identity(caller), id, runId, files)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No downloadable run " + runId + " for scheduled report " + id + "."));

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.fileName()).build().toString())
                // A generated report is a snapshot of rows that move, and this
                // one was generated days ago. Never cached — the same
                // no-store ReportExportService sets on the interactive export,
                // for the stronger reason that this URL is in an email and will
                // be clicked by more than one person on more than one machine.
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(file.bytes());
    }

    private static CallerIdentity identity(Authentication caller) {
        return CallerIdentity.of(caller)
                .orElseThrow(() -> new IllegalStateException(
                        "an authenticated request reached report schedules with no CallerIdentity"));
    }
}
