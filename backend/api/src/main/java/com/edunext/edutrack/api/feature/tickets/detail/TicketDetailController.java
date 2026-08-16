package com.edunext.edutrack.api.feature.tickets.detail;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A-052 · {@code GET /tickets/{ticketId}/full} — the detail page in one call.
 *
 * <h2>⚠ This lives in Stream C's directory</h2>
 *
 * <p>{@code api/feature/tickets/} belongs to Stream C per TEAM-PLAN.md §6, and
 * A-052 is a Stream A task whose surface is Stream C's screen. It is written
 * here rather than somewhere convenient because feature packaging means the
 * route lives with its feature — putting a {@code /tickets/} endpoint under
 * {@code dashboard/} to avoid the ownership question would be worse. CODEOWNERS
 * requests Divyansh's review automatically, which is the mechanism that makes
 * this visible rather than quiet; <b>it needs his sign-off before merge.</b>
 *
 * <h2>ETag, and why it is the response's own hash</h2>
 *
 * <p>The tag is computed from the assembled answer rather than from
 * {@code tickets.updated_at}. A detail response is an aggregate of eight
 * sources, and {@code updated_at} moves for none of them: adding a comment,
 * logging effort or uploading a file all change what this endpoint returns
 * while leaving the ticket row untouched. A tag derived from the ticket alone
 * would serve a stale 304 for exactly the updates a detail page exists to show.
 *
 * <p>The cost is that the body is assembled before the 304 is decided — the
 * saving is bandwidth and client rendering, not database work. That is the
 * honest trade and it is the same one {@code CalendarController} already makes.
 */
@RestController
@RequestMapping("/api/v1/tickets")
@Tag(name = "tickets")
class TicketDetailController {

    private final TicketDetailService detail;

    TicketDetailController(TicketDetailService detail) {
        this.detail = detail;
    }

    /**
     * Every authenticated role may ask. Whether <em>this</em> ticket comes back
     * is {@code ScopedTickets}' decision, and it answers 404 rather than 403 —
     * a permission denial here would confirm the ticket exists, which is the
     * existence leak A-035 removed.
     */
    @GetMapping(path = "/{ticketId}/full", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "getTicketDetail", summary = "Ticket detail in one call")
    ResponseEntity<TicketDetailDtos.DetailResponse> full(
            Authentication caller,
            @PathVariable long ticketId,
            @RequestParam(required = false) Integer cycle,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {

        TicketDetailDtos.Detail data = detail.detail(caller, ticketId, cycle);
        String etag = etagOf(data);

        if (ifNoneMatch != null && matches(ifNoneMatch, etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        return ResponseEntity.ok().eTag(etag).body(new TicketDetailDtos.DetailResponse(data));
    }

    /**
     * Records hash structurally, so this moves when — and only when — some field
     * of the answer changes. Same idiom as {@code CalendarController.etagOf}.
     */
    private static String etagOf(TicketDetailDtos.Detail data) {
        return Integer.toHexString(data.hashCode());
    }

    /** {@code *} matches anything, per RFC 9110. */
    private static boolean matches(String ifNoneMatch, String current) {
        String candidate = ifNoneMatch.trim();
        if ("*".equals(candidate)) {
            return true;
        }
        return candidate.replace("W/", "").replace("\"", "").equals(current);
    }
}
