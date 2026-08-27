package com.edunext.edutrack.api.realtime;

import com.edunext.edutrack.api.security.CallerIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.List;

import static com.edunext.edutrack.api.realtime.RealtimeDestination.ManagerTopic;
import static com.edunext.edutrack.api.realtime.RealtimeDestination.ProjectTopic;
import static com.edunext.edutrack.api.realtime.RealtimeDestination.StageTopic;
import static com.edunext.edutrack.api.realtime.RealtimeDestination.TicketTopic;
import static com.edunext.edutrack.api.realtime.RealtimeDestination.UserQueue;

/**
 * D-013 · the socket-layer equivalent of the row-scope guard.
 *
 * <p>Until this existed, chat's entire authorisation model could be walked
 * around by opening a socket. The REST side is careful — a thread you are not
 * in is indistinguishable from one that does not exist — and then every message
 * posted to it was broadcast to {@code /topic/ticket.{id}}, which anybody could
 * subscribe to. CLAUDE.md calls this "the socket-layer equivalent of Stream A's
 * scope guard, and skipping it reopens the exact hole the guard closes".
 *
 * <p><strong>Deny by default.</strong> A destination this class does not
 * understand is refused, not passed through. That is the only safe direction:
 * D-014's {@link RealtimeDestinations#parse} returns empty for anything outside
 * §9.3, and a new room added without a rule here must fail loudly rather than
 * be readable by everyone.
 *
 * <p><strong>Rejections throw rather than drop.</strong> Returning {@code null}
 * would swallow the SUBSCRIBE, and the client would sit there believing it was
 * subscribed and receiving nothing — the same silent failure D-014 exists to
 * prevent. An exception becomes a STOMP ERROR frame the client can report.
 */
@Component
class SubscriptionAuthorisation implements ChannelInterceptor {

    /**
     * What a client actually subscribes to for its own events.
     *
     * <p>Not {@code /user/{id}/queue/events} — that is the <em>send</em> form
     * {@link RealtimeDestinations#user(long)} builds. Spring resolves a
     * subscription to {@code /user/queue/events} against the session's own
     * principal, so a client cannot name somebody else's queue here even if it
     * tries. The id-bearing form is refused below precisely because it looks
     * like it should work and never would.
     */
    static final String OWN_QUEUE = "/user/queue/events";

    private static final Logger log = LoggerFactory.getLogger(SubscriptionAuthorisation.class);

    private final List<SubscriptionScope> scopes;

    SubscriptionAuthorisation(List<SubscriptionScope> scopes) {
        this.scopes = scopes;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        if (!StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            return message;
        }

        String destination = accessor.getDestination();
        Long userId = userIdOf(accessor.getUser());

        if (userId == null) {
            // Not "no rule matched" but "we do not know who this is", and the
            // two deserve different logs — an unauthenticated socket is a
            // wiring fault, not an access decision.
            throw refuse(destination, "the socket carries no identified user");
        }
        if (!authorised(userId, destination)) {
            throw refuse(destination, "user " + userId + " may not subscribe");
        }
        return message;
    }

    private boolean authorised(long userId, String destination) {
        if (OWN_QUEUE.equals(destination)) {
            // Spring scopes this to the subscriber's own session. There is
            // nothing further to check and nothing they could widen it to.
            return true;
        }

        return RealtimeDestinations.parse(destination)
                .map(room -> switch (room) {
                    case TicketTopic ticket ->
                            any(scope -> scope.mayObserveTicket(userId, ticket.ticketId()));
                    case ProjectTopic project ->
                            any(scope -> scope.mayObserveProject(userId, project.projectId()));

                    // The id-bearing send form. Subscribing to it would resolve
                    // to a per-session destination nothing publishes to, so it
                    // fails silently rather than leaking — refused so that the
                    // mistake surfaces at the point it is made.
                    case UserQueue ignored -> false;

                    // D-059. Open now, and not because A-034 landed — because
                    // the frame turned out not to need it. What travels here is
                    // a nudge with no ticket in it, so the question is "may this
                    // person be told their team's queue moved", which project
                    // membership answers. StageQueueSubscriptionScope records
                    // the argument, including what has to change first if a
                    // ticket identity is ever put in the frame.
                    case StageTopic stage ->
                            any(scope -> scope.mayObserveStage(
                                    userId, stage.stageCode(), stage.projectId()));

                    // Still shut, and no task opens it. §9.3 lists the room;
                    // nothing publishes to it, and the only rule that would fit
                    // — "the people who report to you" — is the one A-034
                    // explicitly refuses to use for scope, since a manager's
                    // visibility comes from their projects rather than from who
                    // reports to them. A room with no publisher and no agreed
                    // rule stays closed.
                    case ManagerTopic ignored -> false;
                })
                .orElse(false);
    }

    /**
     * The union, with one scope's outage isolated from the rest.
     *
     * <p>Each implementation answers from its own tables, so any of them can
     * fail on its own — a dropped connection, a slow query, a table this
     * deployment has not migrated yet. Letting that propagate would refuse
     * <em>every</em> subscription, including the rooms a healthy scope was
     * about to grant: chat's database being unreachable would silently close
     * the ribbon's rooms too, and the STOMP ERROR frame would name the
     * destination rather than the cause.
     *
     * <p><strong>A scope that cannot answer grants nothing, but vetoes
     * nothing either.</strong> Since the union only ever grants, swallowing a
     * failure can only make the decision more restrictive, never less — a
     * broken scope can never open a door, and the overall default is still
     * deny. It is logged at error because a scope throwing here is a fault,
     * not an access decision, and the two must not look alike in a log.
     */
    private boolean any(java.util.function.Predicate<SubscriptionScope> granted) {
        for (SubscriptionScope scope : scopes) {
            try {
                if (granted.test(scope)) {
                    return true;
                }
            } catch (RuntimeException failure) {
                log.error("realtime: {} could not answer and granted nothing",
                        scope.getClass().getSimpleName(), failure);
            }
        }
        return false;
    }

    private static MessageDeliveryException refuse(String destination, String reason) {
        log.warn("realtime: refused subscription to {} — {}", destination, reason);
        // The message names the destination but never says why a specific room
        // was refused beyond this, so a probe learns nothing about which
        // tickets or threads exist — the same reason REST answers 404 and not
        // 403 for an out-of-scope row.
        return new MessageDeliveryException("subscription refused: " + destination);
    }

    /** The same extraction {@code ChatTypingController} performs, for the same reason. */
    /**
     * <b>Delegates to {@code CallerIdentity}, which is the one place that knows
     * what a caller looks like.</b> This used to cast to {@code DevPrincipal}
     * and nothing else, so it understood only the {@code dev-noauth} profile's
     * principal and returned {@code null} for the {@code JwtAuthenticationToken}
     * the real chain produces - refusing every subscription under real
     * authentication, {@code /user/queue/events} included, with "the socket
     * carries no identified user".
     *
     * <p>That is exactly the divergence {@code CallerIdentity}'s own javadoc
     * warns about: it accepts both shapes and says so, and the whole HTTP side
     * has always gone through it. A second, narrower implementation of the same
     * question is what made realtime work in development and fail in every
     * other profile.
     */
    private static Long userIdOf(Principal principal) {
        if (principal instanceof Authentication authentication) {
            return CallerIdentity.of(authentication).map(CallerIdentity::userId).orElse(null);
        }
        return null;
    }
}
