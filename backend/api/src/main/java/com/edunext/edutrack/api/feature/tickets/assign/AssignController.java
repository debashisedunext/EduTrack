package com.edunext.edutrack.api.feature.tickets.assign;

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
 * C-049 · {@code POST /tickets/{ticketId}/assign}, per
 * {@code contracts/openapi.yaml}.
 *
 * <p>The contract has declared {@code assignTicket} since D-001 with nothing
 * behind it — the same gap C-020 found for {@code changeTicketPriority} and
 * C-038 for {@code reopenTicket} — so the generated client's {@code
 * assignTicket}/{@code useAssignTicket} and {@code frontend/src/mocks/}' own
 * handler have both been waiting on this.
 *
 * <p>{@code ticketId} is the ticket <em>code</em>, per {@code TicketId}'s
 * contract schema and {@code PriorityChangeController}'s note on the two
 * neighbouring routes that got this wrong by declaring {@code long}.
 *
 * <p>The {@code /api/v1} prefix is spelled out because nothing declares it
 * globally — see {@code PlannedCloseDateController}'s note.
 */
@RestController
@RequestMapping("/api/v1/tickets/{ticketId}/assign")
@Tag(name = "tickets")
class AssignController {

    private final AssignService service;

    AssignController(AssignService service) {
        this.service = service;
    }

    /*
     * ticket.assign — blueprint §2's "Assign / reassign ticket" row, granted to
     * Admin, PM and Support (V20260806_0900). Unlike PriorityChangeController's
     * use of the identical capability, nothing here is borrowed: this route is
     * exactly what the code is named for, so PermissionMatrix's row is authored
     * from §2 directly rather than from a task-specific sentence.
     *
     * *Which* tickets is still not asked here. ScopedTickets applies the row
     * scope inside the service, so a ticket outside the caller's scope answers
     * 404 and never 403 (A-035) whatever capability they hold.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('ticket.assign')")
    @Operation(operationId = "assignTicket",
            summary = "Assign or reassign within the current stage",
            description = """
                    **This does not create a new ribbon segment.** Reassignment inside a stage \
                    writes `STAGE_REASSIGNED` and splits effort attribution so both resources \
                    appear in the journey roll-up. Only a handoff advances the ribbon.

                    Assigning the ticket to whoever already holds it is a no-op: `200` with the \
                    ticket unchanged and no history row.""")
    AssignDtos.TicketResponse assign(
            Authentication caller,
            @PathVariable String ticketId,
            @Valid @RequestBody AssignDtos.AssignRequest request) {

        return new AssignDtos.TicketResponse(service.assign(caller, ticketId, request));
    }
}
