package com.edunext.edutrack.api.feature.chat;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * D-050 · {@code /chat} per {@code contracts/openapi.yaml}.
 *
 * <p>Three surfaces share these two endpoints. {@code kind} filters the thread
 * list; nothing else in the API distinguishes a ticket thread from a project
 * channel, because to a client nothing else about them differs.
 *
 * <p><strong>404, never 403.</strong> A thread the caller is not in is
 * indistinguishable from one that does not exist — CLAUDE.md's rule for
 * tickets, and it matters more here: a 403 on a direct message confirms that
 * two named people are talking, which is the private part.
 */
@RestController
@RequestMapping("/api/v1/chat")
@Tag(name = "chat")
/*
 * A-033 · authenticated, and no permission, on all six operations.
 *
 * Blueprint §2 has no chat row: there is no capability to hold, because talking
 * to your colleagues is not a privilege the matrix grants. What actually
 * protects a conversation here is membership, and every method already resolves
 * it from CurrentUser.idOf(authentication) and answers 404 — never 403 — when
 * the caller is not in the thread. That is the correct control for this
 * resource and a permission check would not improve it: no code in the
 * catalogue distinguishes "may read this thread" from "may read that one",
 * which is the only question worth asking here.
 *
 * Stated once on the class because it is a property of the resource. Note that
 * it is not vacuous — /api/** is already authenticated by the filter chain, so
 * this repeats that decision, but RouteAuthorizationTest requires every handler
 * to declare one so a future @PostMapping cannot inherit protection by accident
 * and land unreviewed.
 */
@PreAuthorize("isAuthenticated()")
class ChatController {

    /** Matches {@code Limit} in the contract. */
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    /**
     * D-054 · a page of messages names at most this many distinct tickets in one
     * explicit request. Matches {@link TicketCardResolver#MAX_CODES_PER_PAGE},
     * since both are the same resource decision reached from opposite sides.
     */
    private static final int MAX_CARD_CODES = TicketCardResolver.MAX_CODES_PER_PAGE;

    private final ChatService chat;
    private final TicketCardResolver ticketCards;

    ChatController(ChatService chat, TicketCardResolver ticketCards) {
        this.chat = chat;
        this.ticketCards = ticketCards;
    }

    @GetMapping(path = "/threads", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listChatThreads",
            summary = "Threads — ticket, direct message and project channel (S-25)")
    ThreadListResponse threads(Authentication authentication,
                               @RequestParam(required = false) ChatKind kind) {
        return new ThreadListResponse(chat.threads(CurrentUser.idOf(authentication), kind));
    }

    @GetMapping(path = "/threads/{threadId}/messages", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listChatMessages", summary = "Messages, newest first")
    ResponseEntity<MessageListResponse> messages(Authentication authentication,
                                                 @PathVariable long threadId,
                                                 @RequestParam(required = false) Long cursor,
                                                 @RequestParam(required = false) Integer limit) {
        return chat.messages(threadId, CurrentUser.idOf(authentication), cursor, clamp(limit))
                // D-054. The cards are attached here rather than in the service
                // because this is where the caller is, and a card is per-reader.
                .map(messages -> ResponseEntity.ok(
                        new MessageListResponse(ticketCards.attach(authentication, messages))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping(path = "/messages/search", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "searchChatMessages",
            summary = "Search your own conversations (S-25)")
    SearchResponse search(Authentication authentication,
                          @RequestParam(name = "q", required = false) String q,
                          @RequestParam(required = false) Long threadId,
                          @RequestParam(required = false) String cursor,
                          @RequestParam(required = false) Integer limit) {
        ChatService.SearchPage page = chat.search(
                CurrentUser.idOf(authentication), q, threadId, parseCursor(cursor), clamp(limit));
        return new SearchResponse(page.data(), page.meta());
    }

    @PostMapping(path = "/threads/{threadId}/messages",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "postChatMessage", summary = "Post a message")
    ResponseEntity<MessageResponse> post(Authentication authentication,
                                         @PathVariable long threadId,
                                         @Valid @RequestBody ChatDtos.PostMessage request) {
        return chat.post(threadId, CurrentUser.idOf(authentication), request.body())
                .map(message -> ResponseEntity.status(HttpStatus.CREATED)
                        .body(new MessageResponse(withCards(authentication, message))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PatchMapping(path = "/threads/{threadId}/messages/{messageId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "editChatMessage", summary = "Edit a message, inside the five-minute window")
    ResponseEntity<?> edit(Authentication authentication,
                           @PathVariable long threadId,
                           @PathVariable long messageId,
                           @Valid @RequestBody ChatDtos.EditMessage request) {
        return respond(authentication,
                chat.edit(threadId, messageId, CurrentUser.idOf(authentication), request.body()));
    }

    @DeleteMapping(path = "/threads/{threadId}/messages/{messageId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "deleteChatMessage", summary = "Delete a message, leaving a tombstone")
    ResponseEntity<?> delete(Authentication authentication,
                             @PathVariable long threadId,
                             @PathVariable long messageId) {
        return respond(authentication,
                chat.delete(threadId, messageId, CurrentUser.idOf(authentication)));
    }

    /**
     * The three outcomes, mapped to the three statuses the contract promises.
     *
     * <p>{@code NotFound} covers "no such message" and "not yours" alike — a
     * 403 would confirm the message exists and that someone else wrote it,
     * which is the same existence leak CLAUDE.md forbids on tickets.
     *
     * <p>{@code Immutable} is 409 rather than 403: the caller has every right
     * to edit their own message, and did until five minutes ago. The conflict
     * is with the state of the resource, not with their authority over it.
     */
    private ResponseEntity<?> respond(Authentication caller, ChatService.Outcome outcome) {
        return switch (outcome) {
            case ChatService.Outcome.Applied applied ->
                    ResponseEntity.ok(new MessageResponse(withCards(caller, applied.message())));
            case ChatService.Outcome.NotFound ignored ->
                    ResponseEntity.notFound().build();
            case ChatService.Outcome.Immutable immutable ->
                    ResponseEntity.status(HttpStatus.CONFLICT)
                            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                            .body(problem(immutable.reason()));
        };
    }

    /**
     * D-054 · one message's cards, for the caller who just wrote or changed it.
     *
     * <p>An edit matters here as much as a post: somebody who fixes a typo in a
     * ticket code expects the card to appear, and returning the edited message
     * without one would look like the reference had not been recognised.
     */
    private ChatDtos.ChatMessage withCards(Authentication caller, ChatDtos.ChatMessage message) {
        return ticketCards.attach(caller, List.of(message)).getFirst();
    }

    /**
     * D-054 · cards for codes a client has in hand.
     *
     * <p>This exists because of the one thing the read path cannot do. A live
     * message goes out as a <em>single frame to a whole room</em>, so it cannot
     * carry cards — whose cards would they be? Each client therefore resolves
     * for itself, and gets exactly what its own scope allows.
     *
     * <p>Passing codes in is not a way to ask what exists. The scope is applied
     * to every one of them, and a code the caller may not see is simply missing
     * from the answer, indistinguishable from one that was never issued —
     * which is also what happens when the code is in a message body they are
     * reading. Malformed codes are dropped by the same parser the server uses
     * on a body, so the endpoint cannot be handed a pattern the read path would
     * not have matched either.
     */
    @GetMapping(path = "/ticket-cards", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "resolveTicketCards",
            summary = "Ticket cards for codes named in a live message (S-25)")
    TicketCardListResponse ticketCards(Authentication authentication,
                                       @RequestParam(name = "codes", required = false) String codes) {
        if (codes == null || codes.isBlank()) {
            return new TicketCardListResponse(List.of());
        }
        // Parsed rather than split, so the endpoint and the message body agree
        // on what a ticket code is — one definition, one place.
        Set<String> wanted = TicketRefParser.codesIn(codes.replace(',', ' '));
        if (wanted.size() > MAX_CARD_CODES) {
            wanted = wanted.stream().limit(MAX_CARD_CODES)
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        }
        return new TicketCardListResponse(ticketCards.cardsFor(authentication, wanted));
    }

    /** RFC 9457, per CONVENTIONS.md §3. */
    private static Map<String, Object> problem(String detail) {
        return Map.of(
                "type", "https://edutrack/errors/chat-message-immutable",
                "title", "This message can no longer be changed",
                "status", HttpStatus.CONFLICT.value(),
                "detail", detail);
    }

    /**
     * A client asking for a million rows gets {@link #MAX_LIMIT}, not a 400.
     * The page size is our resource decision, not a contract the caller can
     * violate.
     */
    private static int clamp(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    record ThreadListResponse(List<ChatDtos.ChatThread> data) {
    }

    record MessageListResponse(List<ChatDtos.ChatMessage> data) {
    }

    record MessageResponse(ChatDtos.ChatMessage data) {
    }

    record SearchResponse(List<ChatDtos.ChatSearchHit> data, ChatDtos.SearchMeta meta) {
    }

    record TicketCardListResponse(List<ChatDtos.TicketCard> data) {
    }

    /**
     * A cursor we did not issue is treated as no cursor rather than a 400.
     * Search is a place people arrive with a hand-edited URL, and starting from
     * the top is a better answer there than an error page.
     */
    private static Long parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(cursor.trim());
        } catch (NumberFormatException malformed) {
            return null;
        }
    }
}
