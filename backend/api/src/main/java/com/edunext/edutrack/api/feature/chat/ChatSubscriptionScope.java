package com.edunext.edutrack.api.feature.chat;

import com.edunext.edutrack.api.realtime.SubscriptionScope;
import org.springframework.stereotype.Component;

/**
 * D-013 · chat's answer to "may this user watch this room?".
 *
 * <p>Chat broadcasts to {@code /topic/ticket.{id}} and {@code /topic/project.{id}},
 * so it has to say who may listen. The rule is the one the REST side already
 * uses and needs no {@code ScopeResolver} to state: <strong>you may watch a room
 * you have a thread in.</strong> Membership is explicit in
 * {@code chat_participants}, which is why D-050 could enforce authorisation
 * without waiting for A-034 and why this can too.
 *
 * <p>It is deliberately narrower than the room will eventually be. After D-058
 * the ticket topic also carries ribbon advances, whose audience is "anyone who
 * could GET the ticket" — a strictly larger set that A-034 defines. That
 * arrives as a second {@link SubscriptionScope}; the interceptor unions them,
 * so nothing here has to change or widen in anticipation.
 */
@Component
class ChatSubscriptionScope implements SubscriptionScope {

    private final ChatRepository repository;

    ChatSubscriptionScope(ChatRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean mayObserveTicket(long userId, long ticketId) {
        return repository.participatesInTicketThread(userId, ticketId);
    }

    @Override
    public boolean mayObserveProject(long userId, long projectId) {
        return repository.participatesInProjectThread(userId, projectId);
    }
}
