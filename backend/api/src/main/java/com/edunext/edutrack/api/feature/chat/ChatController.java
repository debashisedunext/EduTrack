package com.edunext.edutrack.api.feature.chat;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
}
