package com.edunext.edutrack.api.feature.tickets.effort;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * C-035 · {@code /tickets/{ticketId}/effort} and {@code /tickets/{ticketId}/effort-logs},
 * per {@code contracts/openapi.yaml}.
 *
 * <p>{@code String ticketId}, resolved by {@code ScopedTickets.requireByCode} —
 * the contract's {@code TicketId} parameter is the {@code CRM-26-00347} code,
 * not the row id, exactly as {@code PriorityChangeController}'s note argues at
 * length for the identical parameter on the route beside this one. Two other
 * ticket routes still declare {@code long ticketId} and work only against the
 * mock; that is raised there, not repeated here.
 *
 * <p>The {@code /api/v1} prefix is spelled out because nothing declares it
 * globally — see {@code PlannedCloseDateController}'s note.
 */
@RestController
@RequestMapping("/api/v1/tickets/{ticketId}")
@Tag(name = "tickets")
class EffortLogController {

    private final EffortLogService service;

    EffortLogController(EffortLogService service) {
        this.service = service;
    }

    /*
     * ticket.update_progress — "Update status or log effort on an assigned
     * ticket", seeded for all six roles (V20260806_0900), the same capability
     * CommentController's post and QuickUpdate already use. §4A.4's own row —
     * "Update status/effort on assigned ticket" — is a flat ✅ for every role, so
     * unlike PriorityChangeController this capability is not borrowed.
     *
     * *Which* tickets is answered by ScopedTickets inside the service, not here:
     * a Developer's row scope already limits them to tickets assigned to them,
     * so nothing in this annotation needs to repeat "assigned" — it is the row
     * scope's job, and restating it here would be the same rule in two places.
     */
    @PostMapping(path = "/effort", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('ticket.update_progress')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "logEffort", summary = "Append an effort log",
            description = """
                    **Append-only. There is no update or delete route, and there never will be.** \
                    A correction is a new entry with `isCorrection` and `correctsEntryId`, exactly \
                    like an accounting reversal.

                    **Stage, iteration and cycle are stamped server-side from the ticket's current \
                    position — never sent by the client**, which would let a stale tab attribute \
                    effort to the wrong stage.

                    For a correction, `hours` and `workDate` are not read: the compensating entry \
                    computes the exact negation of the entry it reverses and copies its work date, \
                    so a caller cannot leave a residue that reads as real work.""")
    EffortLogDtos.EffortLogResponse log(
            Authentication caller,
            @PathVariable String ticketId,
            /*
             * Accepted per CONVENTIONS.md §4 and, following ResourceController's
             * and ReopenController's identical note, not yet honoured: the
             * 24-hour replay store is A-035 and does not exist. Unlike a create
             * guarded by a unique constraint, a retried effort log has nothing to
             * catch it — a double-submitted POST really does double the hours.
             * Flagged rather than silently built around: a bespoke replay store
             * for one route is a bigger change than this task, and a shared one
             * is Stream A's to design, as the two precedents above both say of
             * their own routes.
             */
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody EffortLogDtos.EffortLogRequest request) {

        return new EffortLogDtos.EffortLogResponse(service.log(caller, ticketId, request));
    }

    /*
     * isAuthenticated() rather than a capability, following CommentController's
     * listing: reading a ticket's own effort log is not a privileged act once
     * ScopedTickets has already decided the caller may see the ticket at all.
     */
    @GetMapping(path = "/effort-logs", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "listEffortLogs", summary = "Effort log — read only",
            description = "`GET` only. No `PUT`, `PATCH` or `DELETE` route is defined for this path "
                    + "by design. See `POST /tickets/{ticketId}/effort`.")
    EffortLogDtos.EffortLogListResponse list(
            Authentication caller,
            @PathVariable String ticketId,
            @RequestParam(required = false) Integer cycle,
            @RequestParam(required = false) String stage,
            @RequestParam(required = false) Integer iteration,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {

        return service.list(caller, ticketId, cycle, stage, iteration, cursor, limit);
    }
}
