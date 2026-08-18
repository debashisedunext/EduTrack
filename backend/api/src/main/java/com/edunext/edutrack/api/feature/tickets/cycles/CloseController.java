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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C-040 · {@code POST /tickets/{ticketId}/close}, per
 * {@code contracts/openapi.yaml} — S-23.
 *
 * <p>The contract has declared {@code closeTicket} since D-001 with nothing
 * serving it — the same state {@code ReopenController}'s own javadoc records
 * for {@code reopenTicket}, and the reason the generated client's
 * {@code closeTicket} has always compiled against a route that 404'd.
 *
 * <p>A {@code POST} rather than a {@code PATCH}, on {@code ReopenController}'s
 * own reasoning: closing is not an edit to a field, it seals a cycle, and
 * naming it as an action is what lets "there is nothing to close" be refused
 * as one (422) rather than expressed as a rejected field.
 */
@RestController
@RequestMapping("/api/v1/tickets/{ticketId}/close")
@Tag(name = "tickets")
class CloseController {

    private final CloseService service;

    CloseController(CloseService service) {
        this.service = service;
    }

    /*
     * ticket.close, which Admin, PM and Support hold and Developer, QA and
     * Deployment do not — blueprint §2's "Close ticket" row, and G-3
     * (PLAN.md §5): closure belongs to the Sign-off stage owner, never the
     * Developer who claimed the work complete. workflow_transitions seeds
     * RESOLVED -> CLOSED for the same three roles and no others
     * (V20260807_1100, row 12) — two independent statements of the same
     * restriction, exactly the shape ReopenController's own comment
     * documents for ticket.reopen.
     *
     * *Which* tickets is still not asked here. ScopedTickets applies the row
     * scope inside the service, so a ticket outside the caller's scope
     * answers 404 and never 403 (A-035) whatever capability they hold.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('ticket.close')")
    @Operation(operationId = "closeTicket",
            summary = "Close the current cycle (S-23)",
            description = """
                    Stamps `actualCloseDate` and seals the current cycle. Only a **RESOLVED** \
                    ticket can be closed; `workflow_transitions` seeds no other row into \
                    `CLOSED`, so an IN_PROGRESS or already-CLOSED ticket is a `422`.

                    `resolutionSummary` is the only required field. `actualCloseDate` omitted \
                    defaults to now; `finalEffortHours` is recorded as a history entry rather \
                    than a new effort log, since it confirms hours already logged rather than \
                    adding new ones.""")
    /*
     * ⚠ String, not long — PriorityChangeController's own fix, applied here
     * rather than repeated as a third instance of the bug it names.
     *
     * The contract's `TicketId` is a ticket CODE (`^[A-Z][A-Z0-9]{1,9}-\d{2}-
     * \d{5,}$`, e.g. `CRM-26-00347`), so every real client — including the
     * generated one this dialog uses — puts a code in this path segment.
     * `TicketDetailController.full` and `ReopenController.reopen` still
     * declare `@PathVariable long ticketId`, which 400s against the real
     * backend for every request a real caller sends; it has not bitten either
     * because both have only ever been exercised against the mock, which
     * routes on the string. Found here by actually calling this route against
     * the running application rather than only the mock or a mocked unit
     * test — `ScopedTickets.requireByCode` has carried the fix's other half
     * since A-035. The other two routes are raised, not fixed here, on
     * PriorityChangeController's own precedent: another stream's route is
     * another task's test surface.
     */
    CloseDtos.TicketResponse close(
            Authentication caller,
            @PathVariable String ticketId,
            @Valid @RequestBody CloseDtos.CloseRequest request) {

        TicketWire.Ticket closed = service.close(caller, ticketId, request);
        return new CloseDtos.TicketResponse(closed);
    }
}
