package com.edunext.edutrack.api.feature.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.security.Principal;

/**
 * D-051 · the typing indicator, blueprint §9.3's {@code typing} event.
 *
 * <p>The first consumer of the {@code /app} prefix D-012 registered. A client
 * sends to {@code /app/chat/{threadId}/typing} and every viewer of the thread's
 * room hears {@code chat.typing}.
 *
 * <p><strong>Nothing is persisted, deliberately.</strong> "Ravi is typing" is
 * true for about two seconds and worthless afterwards; writing it would add a
 * row per keystroke burst to a table that exists to hold evidence. It is also
 * why this is the one part of chat that may be lost without consequence — if
 * the socket drops, the indicator simply stops, which is the correct behaviour
 * anyway.
 *
 * <p><strong>Membership is still checked.</strong> An unauthorised typing event
 * leaks two things: that a thread exists, and who is active in it. It is a
 * smaller leak than a message, not an absent one.
 */
@Controller
class ChatTypingController {

    private static final Logger log = LoggerFactory.getLogger(ChatTypingController.class);

    private final ChatService chat;

    ChatTypingController(ChatService chat) {
        this.chat = chat;
    }

    /**
     * @param principal the STOMP session's user, established at handshake. Null
     *                  when the socket was opened without authentication —
     *                  possible today because D-013 has not landed, so this
     *                  drops the message rather than guessing an identity.
     */
    @MessageMapping("/chat/{threadId}/typing")
    void typing(@DestinationVariable long threadId,
                @Payload(required = false) TypingSignal signal,
                Principal principal) {
        Long userId = userIdOf(principal);
        if (userId == null) {
            // Fail closed and say so. A typing indicator that silently does
            // nothing is indistinguishable from a colleague who is not typing.
            log.warn("chat: typing on thread {} from an unauthenticated socket, dropped", threadId);
            return;
        }

        boolean typing = signal == null || signal.typing();
        chat.typing(threadId, userId, typing);
    }

    private static Long userIdOf(Principal principal) {
        if (principal instanceof Authentication authentication) {
            return CurrentUser.idOf(authentication);
        }
        return null;
    }

    /**
     * {@code {"typing": false}} is how a client says "stopped", rather than
     * waiting for a timeout. The field is optional so a bare send still means
     * "typing" — the common case should be the cheapest to write.
     */
    record TypingSignal(boolean typing) {
    }
}
