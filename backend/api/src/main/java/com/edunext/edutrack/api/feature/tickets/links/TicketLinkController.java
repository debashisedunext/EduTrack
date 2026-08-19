package com.edunext.edutrack.api.feature.tickets.links;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * C-064 · {@code /tickets/{ticketId}/links}, per {@code contracts/openapi.yaml}.
 *
 * <p>{@code String ticketId}, not {@code long} — the contract's {@code TicketId}
 * is a code, and these are new routes rather than ones inherited from an older
 * pattern, so they follow {@code PriorityChangeController}'s corrected reading
 * rather than {@code TicketDetailController}'s and {@code AttachmentController}'s
 * unfixed one. See that controller's note for the defect this avoids repeating.
 *
 * <p>The {@code /api/v1} prefix is spelled out for {@code PlannedCloseDateController}'s
 * reason: nothing declares it globally.
 */
@RestController
@RequestMapping("/api/v1/tickets/{ticketId}/links")
@Tag(name = "tickets")
class TicketLinkController {

    private final TicketLinkService service;

    TicketLinkController(TicketLinkService service) {
        this.service = service;
    }

    /*
     * A-033 · ticket.update_progress, which all six roles hold — the same
     * capability AttachmentController and CommentController assert for the
     * identical reason: noting that this ticket blocks, duplicates or relates
     * to another is part of working it, not a privileged act. A seventh role
     * without the grant would be denied here.
     *
     * *Which* tickets — on both ends — is a different question and is not
     * asked here. ScopedTickets applies the caller's row scope inside the
     * service to the path ticket and to the submitted target independently,
     * so either one outside the caller's scope answers 404 (A-035).
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('ticket.update_progress')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createTicketLink", summary = "Link this ticket to another")
    TicketLinkDtos.LinkedTicketResponse create(
            Authentication caller,
            @PathVariable String ticketId,
            @Valid @RequestBody TicketLinkDtos.CreateLinkRequest request) {

        return new TicketLinkDtos.LinkedTicketResponse(service.create(caller, ticketId, request));
    }

    /**
     * Same capability as the create — removing a link you added by mistake is
     * part of working the ticket too. {@code ticket_links} is ordinary
     * mutable data, not one of CLAUDE.md's three append-only tables, so
     * unlike the comment and attachment deletes this needs no row-level
     * "uploader, or a PM" rule: either side of a relationship may take it
     * down, and {@link TicketLinkService#delete} still writes a history row
     * naming who did.
     */
    @DeleteMapping("/{linkId}")
    @PreAuthorize("hasAuthority('ticket.update_progress')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "deleteTicketLink", summary = "Remove a link")
    void delete(Authentication caller, @PathVariable String ticketId, @PathVariable long linkId) {
        service.delete(caller, ticketId, linkId);
    }
}
