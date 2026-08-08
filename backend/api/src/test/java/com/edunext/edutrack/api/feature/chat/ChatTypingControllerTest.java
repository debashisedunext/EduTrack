package com.edunext.edutrack.api.feature.chat;

import com.edunext.edutrack.api.security.dev.DevPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.security.Principal;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The STOMP entry point for D-051's typing indicator.
 *
 * <p>What is worth pinning down here is the failure path. An unauthenticated
 * socket is possible today — D-013 has not landed — and the choice between
 * dropping the message and guessing an identity is the difference between a
 * dead indicator and one that attributes typing to the wrong person.
 */
class ChatTypingControllerTest {

    private final ChatService chat = mock(ChatService.class);
    private final ChatTypingController controller = new ChatTypingController(chat);

    private static Principal authenticated(long userId) {
        DevPrincipal principal = new DevPrincipal(
                userId, "ravi", "Ravi Kumar", "DEVELOPER", List.of(), List.of());
        return new UsernamePasswordAuthenticationToken(principal, null, List.of());
    }

    @Test
    void anAuthenticatedSignalReachesTheEngine() {
        controller.typing(4471L, new ChatTypingController.TypingSignal(true), authenticated(7L));

        verify(chat).typing(4471L, 7L, true);
    }

    @Test
    @DisplayName("a bare send means typing — the common case is the cheapest to write")
    void anAbsentPayloadMeansTyping() {
        controller.typing(4471L, null, authenticated(7L));

        verify(chat).typing(4471L, 7L, true);
    }

    @Test
    void stoppedIsCarriedThrough() {
        controller.typing(4471L, new ChatTypingController.TypingSignal(false), authenticated(7L));

        verify(chat).typing(4471L, 7L, false);
    }

    @Test
    @DisplayName("an unauthenticated socket is dropped, never guessed at")
    void noPrincipalMeansNoEvent() {
        controller.typing(4471L, new ChatTypingController.TypingSignal(true), null);

        // Defaulting to any user id here would attribute typing to somebody who
        // is not there. Failing closed is the only safe answer until D-013.
        verify(chat, never()).typing(anyLong(), anyLong(), anyBoolean());
    }

    @Test
    void anUnknownPrincipalShapeIsAlsoDropped() {
        controller.typing(4471L, new ChatTypingController.TypingSignal(true), () -> "someone");

        verify(chat, never()).typing(anyLong(), anyLong(), anyBoolean());
    }
}
