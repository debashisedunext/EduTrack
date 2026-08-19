package com.edunext.edutrack.api.feature.tickets.history;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * C-059 · {@code /tickets/{ticketId}/history}, per {@code contracts/openapi.yaml}.
 *
 * <p>{@code String ticketId}, resolved by {@code ScopedTickets.requireByCode} —
 * the contract's {@code TicketId} parameter is the {@code CRM-26-00347} code,
 * not the row id, per this package's own {@code README.md} note on the two
 * routes that got this wrong. {@code EffortLogController} and
 * {@code PriorityChangeController} are the precedent this follows.
 *
 * <p>The {@code /api/v1} prefix is spelled out because nothing declares it
 * globally — see {@code PlannedCloseDateController}'s note.
 */
@RestController
@RequestMapping("/api/v1/tickets/{ticketId}/history")
@Tag(name = "tickets")
class TicketHistoryController {

    private final TicketHistoryService service;

    TicketHistoryController(TicketHistoryService service) {
        this.service = service;
    }

    /*
     * isAuthenticated() rather than a capability, following
     * CommentController.list and EffortLogController.list: reading a ticket's
     * own history is not a privileged act once ScopedTickets has already decided
     * the caller may see the ticket at all. *Which* tickets is answered by
     * ScopedTickets inside the service, and an out-of-scope ticket answers 404
     * rather than 403 — A-035's existence leak.
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "listTicketHistory", summary = "History — read only, cycle-grouped",
            description = """
                    `GET` only. **No mutation route exists for this path by design**, and the \
                    API rejects mutation even if the DOM is manipulated to attempt it.

                    Comments, field changes and handoffs interleave into one chronological \
                    stream when `include=comments` — two separate lists the reader would \
                    otherwise reconcile by timestamp is exactly what this avoids. \
                    `include=attachments` is declared on the contract and not yet honoured; \
                    C-060 is the attachments tab.""")
    TicketHistoryDtos.HistoryListResponse list(
            Authentication caller,
            @PathVariable String ticketId,
            @RequestParam(required = false) Integer cycle,
            @RequestParam(required = false) List<String> include,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {

        return service.list(caller, ticketId, cycle, include, cursor, limit);
    }
}
