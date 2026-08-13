package com.edunext.edutrack.api.feature.chat;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * D-055 / D-056 · "Ask Status" per {@code contracts/openapi.yaml}.
 *
 * <p><strong>The paths sit under {@code /tickets} and the code sits in
 * {@code feature/chat}, and that is not a mistake.</strong> CLAUDE.md's feature
 * packaging is about where code lives, not about which URL prefix a feature may
 * serve — and the whole of this feature is chat: it posts a chat message into a
 * chat thread, closes on a chat reply, and reads {@code chat_participants} to
 * decide who may see the badge. Putting it in {@code feature/tickets} to match
 * the URL would put Stream D's code in Stream C's package, which is the thing
 * the rule actually forbids. The contract has carried
 * {@code POST /tickets/{ticketId}/ask-status} tagged {@code [tickets, chat]}
 * since D-001, so the address was agreed long before the implementation.
 *
 * <h2>Why the route-level decision is only {@code isAuthenticated()}</h2>
 *
 * <p>A-033 requires every handler to declare one, and this is the honest answer
 * rather than the weak one. Who may ask for a status is <strong>a question
 * about the row</strong> — are you <em>this</em> assignee's reporting manager,
 * are you a PM on <em>this</em> ticket's project — and {@code @PreAuthorize}
 * decides before the row is loaded, so it cannot express it. Inventing a
 * capability like {@code ticket.ask_status} would put a coarse gate in front of
 * the real check and imply the real check was somewhere it is not; the decision
 * lives in {@link StatusRequestService#ask}, where the ticket is in hand, and
 * answers 404 rather than 403 for exactly the reason CONVENTIONS.md §7 gives.
 *
 * <p>Same reasoning and same annotation as {@link ChatController}. Adding a
 * permission would also mean editing {@code api/security/permission/}, which is
 * Stream A's, and it is not a change worth asking for to express something the
 * annotation cannot enforce anyway.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "chat")
@PreAuthorize("isAuthenticated()")
class StatusRequestController {

    private final StatusRequestService statusRequests;

    StatusRequestController(StatusRequestService statusRequests) {
        this.statusRequests = statusRequests;
    }

    @PostMapping(path = "/tickets/{ticketId}/ask-status",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "askTicketStatus",
            summary = "Request a status update from the assignee (S-25)")
    ResponseEntity<?> ask(Authentication authentication,
                          @PathVariable long ticketId,
                          @Valid @RequestBody(required = false) StatusRequestDtos.AskStatus request) {
        StatusRequestService.Outcome outcome = statusRequests.ask(
                ticketId,
                CurrentUser.idOf(authentication),
                request == null ? null : request.note());

        return switch (outcome) {
            // 202 whether the card was posted now or had already been posted.
            // A second click is the same request, and telling the manager their
            // click "failed" when the question is already outstanding would
            // invite them to keep clicking.
            case StatusRequestService.Outcome.Asked asked ->
                    ResponseEntity.accepted().body(new StatusRequestResponse(asked.request()));
            case StatusRequestService.Outcome.NotFound ignored ->
                    ResponseEntity.notFound().build();
            // 422, not 403: the caller has the authority and the request is
            // well-formed — the ticket is simply not in a state that can answer
            // it. The same distinction the handoff endpoint draws.
            case StatusRequestService.Outcome.Rejected rejected ->
                    ResponseEntity.unprocessableEntity()
                            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                            .body(problem(rejected.reason()));
        };
    }

    /**
     * D-056 · the distinct badge on the ticket — what is outstanding here.
     */
    @GetMapping(path = "/tickets/{ticketId}/status-requests", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listTicketStatusRequests",
            summary = "Open status requests on this ticket (S-25)")
    StatusRequestListResponse onTicket(Authentication authentication, @PathVariable long ticketId) {
        return new StatusRequestListResponse(
                statusRequests.openOnTicket(ticketId, CurrentUser.idOf(authentication)));
    }

    /**
     * D-056 · the manager's "Awaiting response" list.
     *
     * <p>Under {@code /me} rather than {@code /users/{id}/…}, the same choice
     * D-042 made: a path taking a user id is one an Admin would reasonably
     * expect to work, and "who is ignoring whom" is not a report to hand out as
     * a side effect of a URL shape nobody decided to build.
     */
    @GetMapping(path = "/me/awaiting-response", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listAwaitingResponse",
            summary = "Status requests you are still waiting on (S-25)")
    StatusRequestListResponse awaiting(Authentication authentication) {
        return new StatusRequestListResponse(statusRequests.awaiting(CurrentUser.idOf(authentication)));
    }

    /** RFC 9457, per CONVENTIONS.md §3. */
    private static Map<String, Object> problem(String detail) {
        return Map.of(
                "type", "https://edutrack/errors/status-request-not-possible",
                "title", "This ticket cannot be asked for a status",
                "status", HttpStatus.UNPROCESSABLE_ENTITY.value(),
                "detail", detail);
    }

    record StatusRequestResponse(StatusRequestDtos.StatusRequest data) {
    }

    record StatusRequestListResponse(List<StatusRequestDtos.StatusRequest> data) {
    }
}
