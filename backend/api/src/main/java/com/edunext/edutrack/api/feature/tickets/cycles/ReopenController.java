package com.edunext.edutrack.api.feature.tickets.cycles;

import com.edunext.edutrack.api.feature.tickets.TicketWire;
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
 * C-038 · {@code POST /tickets/{ticketId}/reopen}, per
 * {@code contracts/openapi.yaml}.
 *
 * <p>The contract has declared {@code reopenTicket} since D-001 with nothing
 * serving it, which is the state C-028 found for {@code deleteAttachment} and
 * named as a defect: the generated client has always exported the function, so
 * S-22's dialog would have worked against the mock and 404'd in production.
 *
 * <p>A {@code POST} rather than a {@code PATCH} on the ticket, and that is the
 * contract's shape rather than a preference. A reopen is not an edit to a field
 * — it seals one cycle and creates another, and the request body's five fields
 * describe the <em>new</em> cycle, not new values for the ticket's. Naming it as
 * an action is also what lets it be refused as one: 422 "there is nothing to
 * reopen" has no sensible expression as a rejected field.
 *
 * <p>The {@code /api/v1} prefix is spelled out because nothing declares it
 * globally; see {@code PlannedCloseDateController}'s note and the 404s that cost.
 */
@RestController
@RequestMapping("/api/v1/tickets/{ticketId}/reopen")
@Tag(name = "tickets")
class ReopenController {

    private final ReopenService service;

    ReopenController(ReopenService service) {
        this.service = service;
    }

    /*
     * A-033 · ticket.reopen, which Admin, PM and Support hold and Developer, QA
     * and Deployment do not.
     *
     * This is the rare ticket route where the capability really is the whole
     * answer, and it is worth saying why, because most of this feature's routes
     * are deliberately all-six with the rule one layer down. Blueprint §2's
     * "Reopen ticket" row grants it to exactly three roles, and
     * workflow_transitions seeds CLOSED → REOPENED for the same three and no
     * others — two independent statements of the same restriction. It is not a
     * row rule in disguise: whether a Developer may reopen does not depend on
     * which ticket, on who is assigned, or on the clock. A Developer who thinks a
     * closed ticket is wrong reopens it by asking, which is the point of the
     * restriction rather than a gap in it.
     *
     * *Which* tickets is still not asked here. ScopedTickets applies the row
     * scope inside the service, so a ticket outside the caller's scope answers
     * 404 and never 403 (A-035) whatever capability they hold.
     *
     * The seeded transitions also carry requires_reason = 1, and ReopenRequest's
     * @NotBlank reason is that requirement on the wire. The two agree by
     * construction rather than by anyone checking, because a reason is the only
     * thing this route cannot proceed without.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('ticket.reopen')")
    @Operation(operationId = "reopenTicket",
            summary = "Reopen — seal cycle N, open cycle N+1 (S-22)",
            description = """
                    One transaction: seal cycle N, insert cycle N+1 with a fresh start date, \
                    planned close date and assignee, increment `reopenCount`, clear \
                    `actualCloseDate`, write history.

                    **Cycle N's effort is never touched** — nothing here reads or writes an \
                    effort log, and `totalEffortHrs` is already the sum across all cycles.

                    Only a **CLOSED** ticket can be reopened; `RESOLVED` is a `422`, because \
                    moving a ticket that has not been signed off backwards is a rework \
                    iteration inside the current cycle and not a new cycle.

                    Omitted fields fall back to what the ticket already knows: the assignee to \
                    the closing cycle's, the restart stage to `TRIAGE`, and the planned close \
                    date is **recomputed** from the SLA ladder rather than reused — cycle N's \
                    is in the past, and a reopened ticket carrying it would be born breached.

                    Not yet: the new cycle's ribbon row. `currentStage` is set, but the stage \
                    transition that draws the segment — and the seal on the hop the ticket was \
                    resting in — belong to C-042's transition service.""")
    ReopenDtos.TicketResponse reopen(
            Authentication caller,
            @PathVariable long ticketId,
            /*
             * Accepted per CONVENTIONS.md §4 and NOT yet honoured: the 24-hour
             * replay store does not exist. Until it does, a retried reopen is
             * refused by uq_ticket_cycles (ticket_id, cycle_no) rather than
             * silently opening a third cycle — which is the failure mode that
             * matters, and is why this ships rather than waiting.
             * ResourceController.create documents the same position for
             * uq_users_username.
             */
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ReopenDtos.ReopenRequest request) {

        TicketWire.Ticket reopened = service.reopen(caller, ticketId, request);
        return new ReopenDtos.TicketResponse(reopened);
    }
}
