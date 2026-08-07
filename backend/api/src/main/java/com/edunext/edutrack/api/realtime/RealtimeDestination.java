package com.edunext.edutrack.api.realtime;

/**
 * A parsed STOMP destination — what a room actually refers to.
 *
 * <p>Building a destination is only half of blueprint §9.3. D-013 has to go the
 * other way: given the destination on an inbound SUBSCRIBE frame, decide
 * whether this caller is allowed on that room, which means first knowing that
 * {@code /topic/ticket.4471} means ticket 4471. Doing that with an ad-hoc
 * substring in the interceptor is how the socket layer and the REST layer
 * drift apart, and the whole point of D-013 is that they must not.
 *
 * <p>Sealed, so a new room type added to §9.3 makes every {@code switch} over
 * this a compile error rather than a silently unhandled case that authorises
 * nothing — or everything.
 */
public sealed interface RealtimeDestination {

    /** {@code user:{id}} — that user's own event queue. */
    record UserQueue(long userId) implements RealtimeDestination {
    }

    /** {@code ticket:{id}} — everyone viewing one ticket. */
    record TicketTopic(long ticketId) implements RealtimeDestination {
    }

    /** {@code stage:{code}:{projectId}} — one team's queue on one project. */
    record StageTopic(String stageCode, long projectId) implements RealtimeDestination {
    }

    /** {@code project:{id}} — the project team. */
    record ProjectTopic(long projectId) implements RealtimeDestination {
    }

    /** {@code manager:{id}} — a reporting manager's alerts. */
    record ManagerTopic(long managerId) implements RealtimeDestination {
    }
}
