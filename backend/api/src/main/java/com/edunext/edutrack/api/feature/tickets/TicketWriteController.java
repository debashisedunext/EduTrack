package com.edunext.edutrack.api.feature.tickets;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * {@code POST /tickets} and {@code PATCH /tickets/{ticketId}}.
 *
 * <p><b>Both have been in the contract since D-001 with no server behind
 * them.</b> The create form (C-010) and every other client have been running
 * against D-004's mock, which is the arrangement working as designed — but it
 * meant §7.5's fields had nowhere to be written, which is what C-067 needed and
 * why these landed together.
 *
 * <p><b>{@code ticketId} is the ticket <i>code</i>, not the row id.</b>
 * {@code CRM-26-00347}. A {@code long} here is the bug
 * {@code PriorityChangeController} records and C-040 then hit again: every real
 * client sends the code, and the numeric binding 400s against a mistake no unit
 * test, permission row or mock handler can see.
 *
 * <p><b>{@code ticket.create} on the POST, {@code ticket.update_progress} on the
 * PATCH — both held by all six roles.</b> §2's "Create ticket" row is six ticks:
 * a Developer who finds a bug in their own work raises it rather than asking a
 * PM to type it, and V20260806_0900 seeds the capability to every role. The
 * capability is still named rather than {@code isAuthenticated()}, so the day §2
 * narrows it the route follows the seed instead of needing a code change.
 */
@RestController
@RequestMapping("/api/v1/tickets")
@Tag(name = "tickets")
class TicketWriteController {

    private final TicketWriteService service;

    TicketWriteController(TicketWriteService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('ticket.create')")
    @Operation(operationId = "createTicket", summary = "Create a ticket",
            description = "The ID is issued server-side from the project sequence, never a COUNT(*). "
                    + "`isClientRaised` is derived from whether a client and a contact are both named "
                    + "and is ignored on the way in (§4B.2). A client with no primary contact, or an "
                    + "inactive one, is a 400 keyed on `clientId` (B-028/B-029).")
    ResponseEntity<TicketCreateDtos.TicketResponse> create(
            Authentication caller,
            @Valid @RequestBody TicketCreateDtos.CreateRequest request) {

        TicketWire.Ticket created = service.create(caller, request);
        return ResponseEntity
                .created(URI.create("/api/v1/tickets/%s/full".formatted(created.ticketCode())))
                .body(new TicketCreateDtos.TicketResponse(created));
    }

    @PatchMapping(path = "/{ticketId}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('ticket.update_progress')")
    @Operation(operationId = "updateTicket", summary = "Edit a ticket's own fields",
            description = "One `FIELD_CHANGED` history row per field that actually changed — a request "
                    + "naming four fields where two differ writes two rows. Rich text is sanitised "
                    + "server-side before it is compared or stored (PLAN.md §3.9).")
    TicketCreateDtos.TicketResponse patch(
            Authentication caller,
            @PathVariable String ticketId,
            @Valid @RequestBody TicketCreateDtos.PatchRequest request) {

        return new TicketCreateDtos.TicketResponse(service.patch(caller, ticketId, request));
    }
}
