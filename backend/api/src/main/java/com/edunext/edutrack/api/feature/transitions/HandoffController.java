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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C-044 · {@code POST /tickets/{ticketId}/handoff}, per
 * {@code contracts/openapi.yaml} — S-29.
 *
 * <p>The contract has declared {@code handoffTicket} since D-001 with nothing
 * behind it, the same gap {@code ReopenController}'s and {@code CloseController}'s
 * own javadoc records for their routes.
 *
 * <p>{@code ticket.handoff} is granted to all six roles ({@code V20260806_0900}
 * — C-043's own note on why the golden rule is not a permission code) — so
 * every signed-in caller reaches {@link HandoffService}, and <em>which</em>
 * ticket they may advance is decided twice over: {@code ScopedTickets} inside
 * the service (404, never 403, for a ticket outside the caller's scope,
 * A-035) and then {@code StageOwnership.mayAdvance} (422, C-043) for a ticket
 * they can see but do not currently own.
 */
@RestController
@RequestMapping("/api/v1/tickets/{ticketId}/handoff")
@Tag(name = "ribbon")
class HandoffController {

    private final HandoffService service;

    HandoffController(HandoffService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('ticket.handoff')")
    @Operation(operationId = "handoffTicket",
            summary = "Advance to the next stage (S-29)",
            description = """
                    Only the current stage owner — plus PM and Admin — may advance a ticket \
                    (422 otherwise, C-043). Seals the hop being left in working minutes, \
                    inserts the next, confirms effort for the stage being left \
                    (`effortHours`, mandatory unless the project allows warn-only — G-1's default \
                    is blocking, and no project carries the override yet), notifies the receiving \
                    owner (bell + mandatory mail) and pushes the ribbon's advance live over \
                    WebSocket to anyone with the ticket open, plus a nudge to the stage queues \
                    the ticket entered and left.""")
    HandoffDtos.RibbonResponse handoff(
            Authentication caller,
            @PathVariable String ticketId,
            /*
             * Accepted per CONVENTIONS.md §4 and NOT yet honoured, on
             * ReopenController's own precedent for the identical gap: the
             * 24-hour replay store does not exist yet. A retried handoff is
             * refused by uq_stage_transitions (ticket_id, cycle_no, seq_no)
             * rather than silently opening a second hop.
             */
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody HandoffDtos.HandoffRequest request) {

        return service.handoff(caller, ticketId, request);
    }
}
