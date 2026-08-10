package com.edunext.edutrack.api.realtime;

import com.edunext.edutrack.api.security.dev.DevPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.security.Principal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D-013 · the rules, exhaustively and without a socket.
 *
 * <p>Every branch here is a room somebody could otherwise read. The end-to-end
 * wiring — that this interceptor is actually on the inbound channel, and that a
 * principal reaches it — is proven separately in {@code RealtimeRelayIT}, which
 * now has to satisfy the guard to do its own job.
 */
class SubscriptionAuthorisationTest {

    private static final long RAVI = 7L;
    private static final long TICKET_HE_IS_IN = 4471L;
    private static final long PROJECT_HE_IS_IN = 12L;

    /** Stands in for chat: Ravi is in one ticket thread and one project channel. */
    private final SubscriptionScope chat = new SubscriptionScope() {
        @Override
        public boolean mayObserveTicket(long userId, long ticketId) {
            return userId == RAVI && ticketId == TICKET_HE_IS_IN;
        }

        @Override
        public boolean mayObserveProject(long userId, long projectId) {
            return userId == RAVI && projectId == PROJECT_HE_IS_IN;
        }
    };

    private final SubscriptionAuthorisation interceptor =
            new SubscriptionAuthorisation(List.of(chat));

    // ------------------------------------------------------------- allowed

    @Test
    void aRoomYouHaveAThreadInIsAllowed() {
        assertThatCode(() -> subscribe(RAVI, "/topic/ticket." + TICKET_HE_IS_IN))
                .doesNotThrowAnyException();
        assertThatCode(() -> subscribe(RAVI, "/topic/project." + PROJECT_HE_IS_IN))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("your own queue needs no check — Spring scopes it to your session")
    void ownQueueIsAllowed() {
        assertThatCode(() -> subscribe(RAVI, SubscriptionAuthorisation.OWN_QUEUE))
                .doesNotThrowAnyException();
    }

    // -------------------------------------------------------------- denied

    @Test
    @DisplayName("a ticket room you have no thread in is refused")
    void anotherTicketsRoomIsRefused() {
        // The whole point of the task: chat's REST side is careful to make a
        // thread you are not in indistinguishable from one that does not
        // exist, and this is where that was being walked around.
        assertThatThrownBy(() -> subscribe(RAVI, "/topic/ticket.9999"))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    void anotherUsersRoomIsRefusedEvenWhereTheyAreAllowed() {
        assertThatThrownBy(() -> subscribe(99L, "/topic/ticket." + TICKET_HE_IS_IN))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    @DisplayName("stage and manager rooms stay shut until A-034")
    void roomsNeedingScopeResolverAreRefused() {
        // A stage queue open to everyone is a list of who is working on what
        // across the whole organisation. Closed is the safe default while the
        // rule that would open it correctly does not exist yet.
        assertThatThrownBy(() -> subscribe(RAVI, "/topic/stage.QA.7"))
                .isInstanceOf(MessageDeliveryException.class);
        assertThatThrownBy(() -> subscribe(RAVI, "/topic/manager.3"))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    @DisplayName("the id-bearing user destination is the send form and is refused")
    void theServerSideUserDestinationIsNotSubscribable() {
        // Subscribing to it resolves to a per-session destination nothing
        // publishes to: accepted, silent, and receiving nothing forever.
        // Refusing turns that into an error at the point the mistake is made.
        assertThatThrownBy(() -> subscribe(RAVI, "/user/" + RAVI + "/queue/events"))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    @DisplayName("a destination outside §9.3 is refused, not passed through")
    void unknownDestinationsAreRefused() {
        // Deny by default is the only safe direction: a room added later
        // without a rule here must fail loudly rather than be world-readable.
        assertThatThrownBy(() -> subscribe(RAVI, "/topic/everything"))
                .isInstanceOf(MessageDeliveryException.class);
        assertThatThrownBy(() -> subscribe(RAVI, "/queue/events"))
                .isInstanceOf(MessageDeliveryException.class);
        assertThatThrownBy(() -> subscribe(RAVI, "/topic/ticket.not-a-number"))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    void anUnauthenticatedSocketMaySubscribeToNothing() {
        assertThatThrownBy(() -> interceptor.preSend(subscribeMessage(null, "/topic/ticket." + TICKET_HE_IS_IN), null))
                .isInstanceOf(MessageDeliveryException.class);
        assertThatThrownBy(() -> interceptor.preSend(
                subscribeMessage(null, SubscriptionAuthorisation.OWN_QUEUE), null))
                .as("not even your own queue, because we do not know whose it is")
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    @DisplayName("with no scope implementations at all, nothing is subscribable but your own queue")
    void denyByDefaultWithNoScopes() {
        SubscriptionAuthorisation bare = new SubscriptionAuthorisation(List.of());

        assertThatThrownBy(() -> bare.preSend(
                subscribeMessage(authenticated(RAVI), "/topic/ticket." + TICKET_HE_IS_IN), null))
                .isInstanceOf(MessageDeliveryException.class);
        assertThatCode(() -> bare.preSend(
                subscribeMessage(authenticated(RAVI), SubscriptionAuthorisation.OWN_QUEUE), null))
                .doesNotThrowAnyException();
    }

    // ------------------------------------------------- other STOMP commands

    @Test
    @DisplayName("only SUBSCRIBE is judged — CONNECT and SEND pass through untouched")
    void otherCommandsAreNotIntercepted() {
        // SEND is guarded at its @MessageMapping handler instead (chat's typing
        // endpoint checks membership itself). Judging it here would need this
        // class to understand every /app route.
        for (StompCommand command : List.of(StompCommand.CONNECT, StompCommand.SEND, StompCommand.DISCONNECT)) {
            StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
            accessor.setDestination("/topic/ticket.9999");
            accessor.setLeaveMutable(true);
            Message<byte[]> message =
                    MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

            assertThat(interceptor.preSend(message, null)).isSameAs(message);
        }
    }

    // --------------------------------------------- one scope failing alone

    /**
     * The bug that turned {@code RealtimeRelayIT} red in CI and stayed green
     * locally: {@code ChatSubscriptionScope} queries the database on every
     * SUBSCRIBE, and where no database was reachable the exception came out of
     * {@code preSend} and refused the subscription outright — including a room
     * a second, healthy scope was granting. Nothing logged it as a fault, so it
     * read as "realtime is broken" rather than "chat's scope cannot answer".
     */
    @Test
    @DisplayName("a scope that throws does not veto a scope that grants")
    void aBrokenScopeDoesNotRefuseWhatAHealthyOneAllows() {
        SubscriptionAuthorisation withABrokenScope = new SubscriptionAuthorisation(
                List.of(exploding(), chat));

        assertThatCode(() -> withABrokenScope.preSend(
                subscribeMessage(authenticated(RAVI), "/topic/ticket." + TICKET_HE_IS_IN), null))
                .doesNotThrowAnyException();
    }

    /** Swallowing the failure must not become an accidental allow-all. */
    @Test
    @DisplayName("a scope that throws still grants nothing of its own")
    void aBrokenScopeGrantsNothing() {
        SubscriptionAuthorisation onlyBroken = new SubscriptionAuthorisation(List.of(exploding()));

        assertThatThrownBy(() -> onlyBroken.preSend(
                subscribeMessage(authenticated(RAVI), "/topic/ticket." + TICKET_HE_IS_IN), null))
                .isInstanceOf(MessageDeliveryException.class);
    }

    /** Stands in for a scope whose database has gone away mid-flight. */
    private static SubscriptionScope exploding() {
        return new SubscriptionScope() {
            @Override
            public boolean mayObserveTicket(long userId, long ticketId) {
                throw new IllegalStateException("no connection available");
            }

            @Override
            public boolean mayObserveProject(long userId, long projectId) {
                throw new IllegalStateException("no connection available");
            }
        };
    }

    // ------------------------------------------------------------- helpers

    private void subscribe(long userId, String destination) {
        interceptor.preSend(subscribeMessage(authenticated(userId), destination), null);
    }

    private static Message<byte[]> subscribeMessage(Principal user, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        accessor.setUser(user);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static Principal authenticated(long userId) {
        DevPrincipal principal = new DevPrincipal(
                userId, "ravi", "Ravi Kumar", "DEVELOPER", List.of(), List.of());
        return new UsernamePasswordAuthenticationToken(principal, null, List.of());
    }
}
