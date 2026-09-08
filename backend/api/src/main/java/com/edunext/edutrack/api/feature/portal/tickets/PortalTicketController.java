package com.edunext.edutrack.api.feature.portal.tickets;

import com.edunext.edutrack.api.feature.portal.tickets.PortalTicketDtos.PortalAttachment;
import com.edunext.edutrack.api.feature.portal.tickets.PortalTicketDtos.PortalComment;
import com.edunext.edutrack.api.feature.portal.tickets.PortalTicketDtos.PortalTicket;
import com.edunext.edutrack.api.feature.portal.tickets.PortalTicketDtos.PortalTicketResponse;
import com.edunext.edutrack.common.pagination.CursorPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * A-127 · the client portal's ticketing side — CP-06 and CP-07.
 *
 * <h2>Four reads and no write verb</h2>
 *
 * <p>Plan §1: "No client-raised tickets. The portal's ticketing side is
 * view-only; raising tickets stays with the support desk." There is no
 * {@code @PostMapping} here for somebody to extend later, which makes
 * "no raise-ticket" a property of the application rather than a rule that has to
 * be remembered.
 *
 * <h2>Authorisation is {@code isAuthenticated()}, and the reason is structural</h2>
 *
 * <p>{@code @PreAuthorize} speaks blueprint §2's six <em>platform</em> roles, and
 * the caller here holds none of them: a {@code CLIENT} principal is an external
 * account with no {@code role} claim at all (A-125). The question these routes
 * actually turn on is <em>which kind</em> of principal is calling, and that fork
 * belongs where A-126 put it — {@code PortalRouteFilter}, ahead of the handler,
 * answering 404 in both directions. Inventing a platform-role restriction here
 * would assert a rule nobody decided and would read, in review, like the thing
 * that keeps staff out. It is not.
 *
 * <p>These four routes are entered in {@code PermissionMatrix} as
 * {@code everyRole}, which is the honest reading of what this annotation does —
 * with the filter's 404 recorded there and asserted in
 * {@code PortalRouteFilterTest}, on the precedent the onboarding block already
 * set: 404 is not 403.
 *
 * <h2>Empty and absent are the only two failures</h2>
 *
 * <p>An unlinked account, a withdrawn {@code portal_access}, another client's
 * ticket and a ticket code that never existed all resolve to an empty page or a
 * 404. None of them is distinguishable from the others, and that is the design.
 */
@RestController
@RequestMapping("/api/v1/portal/tickets")
@Tag(name = "portal")
@PreAuthorize("isAuthenticated()")
class PortalTicketController {

    private final PortalTicketService service;

    PortalTicketController(PortalTicketService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(operationId = "listPortalTickets")
    CursorPage<PortalTicket> list(
            Authentication caller,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "false") boolean excludeClosed,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {

        return service.list(caller, status, excludeClosed, sort, cursor, limit);
    }

    /**
     * One ticket, with an ETag — and a real {@code 304} for it.
     *
     * <p>Derived from the record's own content rather than from
     * {@code updated_at}: the portal projection omits most of the row, so a staff
     * edit to a field this surface does not serve would otherwise invalidate a
     * customer's cache and re-send an identical body.
     *
     * <p><b>The {@code 304} is Spring's, not this method's</b>, and the first
     * draft got that wrong. A {@code ResponseEntity} carrying an {@code ETag}
     * goes through {@code HttpEntityMethodProcessor}, which calls
     * {@code checkNotModified} and turns the response into a bodyless 304 when
     * {@code If-None-Match} matches — comma-separated sets and weak validators
     * included. Hand-rolling that comparison here, as {@code DashboardController}
     * does, added fifteen lines that changed no observable behaviour: a mutation
     * that disabled them left {@code PortalTicketControllerTest} entirely green.
     *
     * <p>{@code DashboardController}'s copy is not the same thing and is right
     * where it is: there the point is to skip <em>building</em> an expensive
     * widget before answering. Here the read has already happened by the time
     * there is an {@code ETag} to compare, so the branch saved nothing and only
     * looked like it was doing the work.
     */
    @GetMapping("/{ticketId}")
    @Operation(operationId = "getPortalTicket")
    ResponseEntity<PortalTicketResponse> get(Authentication caller,
                                             @PathVariable String ticketId) {

        PortalTicket ticket = service.get(caller, ticketId).orElseThrow(PortalTicketController::notFound);

        return ResponseEntity.ok()
                .eTag("\"" + Integer.toHexString(ticket.hashCode()) + "\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PortalTicketResponse(ticket));
    }

    @GetMapping("/{ticketId}/comments")
    @Operation(operationId = "listPortalTicketComments")
    CursorPage<PortalComment> comments(
            Authentication caller,
            @PathVariable String ticketId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {

        return service.comments(caller, ticketId, cursor, limit)
                .orElseThrow(PortalTicketController::notFound);
    }

    @GetMapping("/{ticketId}/attachments")
    @Operation(operationId = "listPortalTicketAttachments")
    CursorPage<PortalAttachment> attachments(
            Authentication caller,
            @PathVariable String ticketId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {

        return service.attachments(caller, ticketId, cursor, limit)
                .orElseThrow(PortalTicketController::notFound);
    }

    /**
     * One refusal for four different facts.
     *
     * <p>Unknown code, another client's ticket, an unlinked account and a
     * withdrawn portal access all raise this, and it carries no detail that could
     * separate them. {@code PublicSignoffExceptionHandler} makes the same choice
     * for the same reason on the sign-off surface next door.
     */
    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket not found");
    }
}
