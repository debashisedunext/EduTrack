package com.edunext.edutrack.api.feature.chat;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
@RequestMapping("/chat")
@Tag(name = "chat")
class ChatController {

    /** Matches {@code Limit} in the contract. */
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final ChatService chat;

    ChatController(ChatService chat) {
        this.chat = chat;
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
                .map(messages -> ResponseEntity.ok(new MessageListResponse(messages)))
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
                .map(message -> ResponseEntity.status(HttpStatus.CREATED).body(new MessageResponse(message)))
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
        return respond(chat.edit(threadId, messageId, CurrentUser.idOf(authentication), request.body()));
    }

    @DeleteMapping(path = "/threads/{threadId}/messages/{messageId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "deleteChatMessage", summary = "Delete a message, leaving a tombstone")
    ResponseEntity<?> delete(Authentication authentication,
                             @PathVariable long threadId,
                             @PathVariable long messageId) {
        return respond(chat.delete(threadId, messageId, CurrentUser.idOf(authentication)));
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
    private ResponseEntity<?> respond(ChatService.Outcome outcome) {
        return switch (outcome) {
            case ChatService.Outcome.Applied applied ->
                    ResponseEntity.ok(new MessageResponse(applied.message()));
            case ChatService.Outcome.NotFound ignored ->
                    ResponseEntity.notFound().build();
            case ChatService.Outcome.Immutable immutable ->
                    ResponseEntity.status(HttpStatus.CONFLICT)
                            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                            .body(problem(immutable.reason()));
        };
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
