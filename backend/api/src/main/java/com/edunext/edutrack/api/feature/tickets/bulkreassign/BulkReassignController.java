package com.edunext.edutrack.api.feature.tickets.bulkreassign;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C-063 · {@code POST /tickets/bulk-reassign}, per {@code contracts/openapi.yaml}.
 *
 * <p>Two callers share this one route: S-17's grid selection (C-017, already
 * merged) built its dialog and hooks against this endpoint before it existed —
 * {@code ResourceService}'s javadoc names it in as much detail — and S-24's
 * wizard is the other. Neither is visible from here; the difference is
 * entirely in how the caller assembled {@code ticketIds}.
 *
 * <p>The {@code /api/v1} prefix is spelled out because nothing declares it
 * globally — see {@code PlannedCloseDateController}'s note.
 */
@RestController
@RequestMapping("/api/v1/tickets/bulk-reassign")
@Tag(name = "tickets")
class BulkReassignController {

    private final BulkReassignService service;

    BulkReassignController(BulkReassignService service) {
        this.service = service;
    }

    /*
     * ⚠ hasAnyRole rather than hasAuthority, and this is the one route in the
     * ticket feature that reads this way. Read this before copying it.
     *
     * The contract's own matrix for this route — spelled out in prose beside
     * `bulk-level`'s, unlike every other route here which just names a §2
     * capability — is Admin and PM, full stop. Not Support, who nonetheless
     * holds `ticket.assign`: single-ticket reassignment (§2's Assign row) and
     * bulk reassignment are not the same permission, and the contract is
     * explicit that this one is narrower.
     *
     * No seeded capability has that exact grant set. `project.manage` happens
     * to ("Admin and PM"), but borrowing it would gate a ticket action behind
     * a permission whose name and every other use is about project master
     * data — the wrong kind of surprise for whoever reads the seed migration
     * next to this annotation. `ticket.assign` is the closest by meaning and
     * wrong by one role. Rather than pick the less-wrong mismatch silently,
     * this asserts the role pair the contract actually specifies and says so:
     * raised for Stream A as a gap, a dedicated `ticket.bulk_reassign` code
     * the day S-09 needs one, the same way `PriorityChangeController` raised
     * its own borrowed capability instead of quietly living with it.
     *
     * *Which* tickets is still not asked here — ScopedTickets applies row
     * scope inside the service, so a PM reaching past their own projects gets
     * a per-ticket "Not found or out of scope" in the result, never a 403.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','PM')")
    @Operation(operationId = "bulkReassignTickets",
            summary = "Reassign many tickets from one resource to another (S-17, S-24)",
            description = """
                    Each ticket gets its own history entry and its own transaction — one \
                    refusing does not roll back the rest, and the response reports every \
                    ticket's outcome rather than a single status code.

                    A ticket already assigned to `toUserId` is left alone: no history row, \
                    reported as succeeded. An id outside the caller's scope, or naming no \
                    ticket at all, is reported as refused with "Not found or out of scope" — \
                    row scoping applies before anything else, so the two are indistinguishable \
                    by design (no existence leak).""")
    BulkReassignDtos.BulkResultResponse reassign(
            Authentication caller,
            /*
             * Accepted and not yet honoured — the 24-hour replay store is
             * A-035 and does not exist, following QuickUpdateController's and
             * ReopenController's identical note on their own routes. A client
             * retrying a dropped response has nothing to catch it today.
             */
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody BulkReassignDtos.BulkReassignRequest request) {

        return new BulkReassignDtos.BulkResultResponse(service.reassign(caller, request));
    }
}
