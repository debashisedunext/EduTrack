package com.edunext.edutrack.api.feature.tickets;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * C-012 · the inline planned-close-date preview, per
 * {@code contracts/openapi.yaml}.
 *
 * <p><b>Why this is a server round trip rather than a calculation in the form.</b>
 * The date depends on the SLA matrix, the org working week, org and project
 * holidays and the assignee's approved leave. A client-side estimate would be a
 * second implementation of {@code WorkingHoursService} — the exact thing that
 * class exists to prevent — and it would be a second implementation that
 * disagrees, because the browser cannot see leave for a resource the user has
 * no permission to read. Worse, it would disagree <em>quietly</em>: the number
 * shown before saving and the number stored on save would differ by a weekend.
 *
 * <p>A {@code GET} with no side effects, so it is safe to fire on every change
 * to project, task type, level or assignee, cacheable per parameter set, and
 * carries nothing the user has typed. It deliberately does not take the ticket
 * body: previewing must not require a valid draft, and §4B.1's rule is that
 * changing the level recomputes the date <em>while the form is still being
 * filled in</em>.
 *
 * <p>The literal path segment sits beside {@code /tickets/{ticketId}} the way
 * {@code /tickets/bulk-reassign} already does. Spring prefers the literal over
 * the template, and {@code TicketId}'s pattern could not match this string in
 * any case.
 *
 * <p>The {@code /api/v1} prefix is spelled out because nothing declares it
 * globally — see {@code CalendarController}'s note, and the 404s that cost.
 */
@RestController
@RequestMapping("/api/v1/tickets")
@Tag(name = "tickets")
class PlannedCloseDateController {

    private final PlannedCloseDateService plannedCloseDates;

    PlannedCloseDateController(PlannedCloseDateService plannedCloseDates) {
        this.plannedCloseDates = plannedCloseDates;
    }

    /**
     * @param projectId  required — the SLA matrix is per project, and resolving
     *                   without one silently answers with the org-wide default
     * @param taskTypeId optional; without it rung 1 cannot match and resolution
     *                   starts at the project's default for the level. The form
     *                   can therefore preview before a task type is picked
     * @param level      required — every rung of the ladder is keyed by it
     * @param assigneeId optional; whose approved leave stops the clock
     * @param from       optional ISO-8601 instant, defaulting to now. Present
     *                   because §7.5 lets a PM or Admin backdate Date Reported,
     *                   and a preview measured from the wrong start is worse
     *                   than no preview
     */
    @GetMapping(path = "/planned-close-date", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "previewPlannedCloseDate",
            summary = "Resolve the SLA policy and preview the planned close date")
    PlannedCloseDateDtos.PlannedCloseDateResponse preview(
            @RequestParam long projectId,
            @RequestParam(required = false) Integer taskTypeId,
            @RequestParam String level,
            @RequestParam(required = false) Long assigneeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from) {

        PlannedCloseDateService.Preview preview =
                plannedCloseDates.preview(projectId, taskTypeId, level, assigneeId, from);
        return new PlannedCloseDateDtos.PlannedCloseDateResponse(
                PlannedCloseDateDtos.PlannedCloseDatePreview.of(preview));
    }
}
