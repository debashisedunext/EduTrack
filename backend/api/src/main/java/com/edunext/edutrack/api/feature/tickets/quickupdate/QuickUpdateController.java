package com.edunext.edutrack.api.feature.tickets.quickupdate;

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
 * C-036 · {@code POST /tickets/{ticketId}/quick-update}, per {@code
 * contracts/openapi.yaml} — S-21's slide-over, the resource's daily driver.
 *
 * <p>{@code String ticketId}, resolved by {@code ScopedTickets.requireByCode} —
 * the contract's {@code TicketId} parameter is the {@code CRM-26-00347} code,
 * not the row id, following {@code EffortLogController}'s note on the same
 * choice for the route beside this one.
 *
 * <p>The {@code /api/v1} prefix is spelled out because nothing declares it
 * globally — see {@code PlannedCloseDateController}'s note.
 */
@RestController
@RequestMapping("/api/v1/tickets/{ticketId}")
@Tag(name = "tickets")
class QuickUpdateController {

    private final QuickUpdateService service;

    QuickUpdateController(QuickUpdateService service) {
        this.service = service;
    }

    /*
     * ticket.update_progress — "Update status or log effort on an assigned
     * ticket", seeded for all six roles (V20260806_0900), the same capability
     * EffortLogController's POST already uses. §4A.4's own row — "Update
     * status/effort on assigned ticket" — is a flat ✅ for every role, so this
     * capability is not borrowed the way PriorityChangeController's is.
     *
     * *Which* tickets is answered by ScopedTickets inside the service, not
     * here — a Developer's row scope already limits them to tickets assigned
     * to them, so nothing in this annotation needs to repeat "assigned".
     */
    @PostMapping(path = "/quick-update", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('ticket.update_progress')")
    @Operation(operationId = "quickUpdateTicket",
            summary = "Status, effort, note and ETA in one call (S-21)",
            description = """
                    The two-click update from the list and My Tasks. Every field is \
                    send-if-touched — an absent field is left alone, never cleared.

                    Effort logged here is auto-stamped with the ticket's current stage, \
                    iteration and cycle — never sent by the client, which would let a stale \
                    tab attribute effort to a stage the ticket left twenty minutes ago.

                    `revisedEta` requires `revisedEtaReason` — sent without one, the call is \
                    refused rather than silently dropping the date.

                    `attachmentIds` links nothing: `POST /tickets/{ticketId}/attachments` \
                    already put the file on this ticket the moment it was picked, and this \
                    only confirms every id named actually belongs here.""")
    QuickUpdateDtos.TicketResponse update(
            Authentication caller,
            @PathVariable String ticketId,
            /*
             * Accepted per CONVENTIONS.md §4 and not yet honoured — the
             * 24-hour replay store is A-035 and does not exist, following
             * EffortLogController's and ReopenController's identical note on
             * their own routes. A retried quick update has nothing to catch
             * it today; flagged rather than silently built around, for the
             * same reason those two give.
             */
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody QuickUpdateDtos.QuickUpdateRequest request) {

        TicketWire.Ticket updated = service.update(caller, ticketId, request);
        return new QuickUpdateDtos.TicketResponse(updated);
    }
}
