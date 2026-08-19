package com.edunext.edutrack.api.feature.transitions;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * C-058 · {@code GET /tickets/{ticketId}/journey}, blueprint §4A.4.
 *
 * <p><b>{@code ticketId} is the ticket <i>code</i>, not the row id</b> —
 * {@code CRM-26-00347}. A {@code long} here is the bug
 * {@code PriorityChangeController}'s own comment records and C-040 then hit a
 * second time: every real client sends the code, and the numeric binding 400s
 * with {@code Failed to convert 'ticketId'} against a mistake no unit test,
 * permission-matrix row or MSW handler can see.
 *
 * <p><b>{@code isAuthenticated()} rather than a capability</b>, following
 * {@code EffortLogController.list} and {@code CommentController}'s listing:
 * reading a ticket's own journey is not a privileged act once
 * {@code ScopedTickets} has decided the caller may see the ticket at all. All
 * six roles hold it, which is what {@code PermissionMatrix} records.
 *
 * <p><b>No {@code @Validated} on this class, deliberately — it makes the
 * {@code @Min} below worse rather than better.</b> Spring 6.1's built-in method
 * validation already checks a constrained controller parameter and raises
 * {@code HandlerMethodValidationException}, which {@code spring.mvc.problemdetails}
 * renders as a 400 with the RFC 9457 body every other route answers with.
 * Adding {@code @Validated} switches on the older AOP path instead, which throws
 * {@code ConstraintViolationException} — unmapped here, so {@code ?cycle=0}
 * answered <b>500</b>. Caught by calling the route rather than by any test:
 * no unit test, permission-matrix row or MSW handler exercises a violated
 * parameter constraint. This class is also the only controller in the codebase
 * that reached for either annotation, which was the first hint it was wrong.
 *
 * <p><b>Read-only, and no write route belongs here ever.</b> The rows it
 * aggregates come from {@code ticket_stage_transitions} and
 * {@code ticket_effort_logs}, both append-only at four layers. A {@code PATCH}
 * on a roll-up would be a write to those tables wearing a different name.
 */
@RestController
@RequestMapping("/api/v1/tickets/{ticketId}")
@Tag(name = "journey")
class JourneyController {

    private final JourneyService service;

    JourneyController(JourneyService service) {
        this.service = service;
    }

    @GetMapping(path = "/journey", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "getJourney", summary = "Per-stage, per-resource effort roll-up (§4A.4)",
            description = "`GET` only. The active/idle split is the point: `idleMins = durationMins − Σ "
                    + "effort in that stage, iteration AND cycle`. The cycle in that join is the "
                    + "correction PLAN.md §3.4 makes to the blueprint's own query — without it, cycle "
                    + "1's effort double-counts into cycle 2 after a reopen.")
    JourneyDtos.JourneyResponse get(
            Authentication caller,
            @PathVariable String ticketId,
            @RequestParam(required = false) @Min(1) Integer cycle) {

        return service.get(caller, ticketId, cycle);
    }
}
