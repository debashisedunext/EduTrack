package com.edunext.edutrack.api.feature.transitions;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C-046 · {@code POST /tickets/{ticketId}/rework}, per
 * {@code contracts/openapi.yaml} — blueprint §4A.1's backward actions.
 *
 * <p>{@code ticket.rework} is granted to <b>all six roles</b>
 * ({@code V20260806_0900}), exactly like {@code ticket.handoff}: the real
 * restriction is the golden rule one layer down — only the current stage
 * owner, PM or Admin may move a given ticket — which {@code StageOwnership}
 * applies per ticket and a capability cannot express. So no 403 is mapped
 * here, on {@code HandoffExceptionHandler}'s own reasoning.
 *
 * <p>The path variable is the ticket <b>code</b>. {@code CloseController}'s
 * note records {@code @PathVariable long ticketId} as a bug found only by
 * exercising the packaged app against a real ticket-code path segment, and
 * {@code TicketDetailController} and {@code ReopenController} still carry it.
 * This route is new, so it is built with {@code String} and
 * {@code ScopedTickets#requireByCode} rather than repeating the mistake a
 * fourth time.
 */
@RestController
@RequestMapping("/api/v1/tickets/{ticketId}/rework")
@Tag(name = "ribbon")
class ReworkController {

    private final ReworkService service;

    ReworkController(ReworkService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('ticket.rework')")
    @Operation(operationId = "reworkTicket",
            summary = "Send the ticket backwards",
            description = """
                    `reason` is mandatory; a defect list is expected on a QA failure. \
                    `toStageCode` must be one of the current stage's allowed return \
                    targets — a stage that exists but is not a return target is refused \
                    with 422, since the same request would be valid from a different \
                    stage.

                    `iterationNo` increments for every subsequent transition in this \
                    cycle — that is what makes rework measurable and what the ping-pong \
                    flag counts at `iterationNo >= 3`. It does not touch `cycleNo`, and \
                    it does not move the planned close date (decision G-2): the original \
                    commitment stands, and rework is what `iterationNo` measures.""")
    ReworkDtos.RibbonResponse rework(
            Authentication caller,
            @PathVariable String ticketId,
            @Valid @RequestBody ReworkDtos.ReworkRequest request) {

        return service.rework(caller, ticketId, request);
    }
}
