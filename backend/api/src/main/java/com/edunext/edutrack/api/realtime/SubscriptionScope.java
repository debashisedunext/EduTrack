package com.edunext.edutrack.api.realtime;

/**
 * D-013 · who may watch a §9.3 room.
 *
 * <p>An interface rather than a query in the interceptor, because the answer
 * belongs to whichever feature owns the data. Chat knows about
 * {@code chat_participants}; A-034's {@code ScopeResolver} will know which
 * tickets a role can see. Realtime must not learn either, or the socket grows a
 * second copy of every scoping rule and the two drift.
 *
 * <p><strong>Implementations are combined as a union.</strong> One room can be
 * reachable for more than one reason — {@code /topic/ticket.4471} carries chat
 * messages today and will carry ribbon advances after D-058, and those have
 * different audiences. Any implementation granting access is enough; the
 * interceptor denies only when every one of them declines.
 *
 * <p>The default is deny. A new implementation opens a door; nothing here opens
 * one by accident.
 */
public interface SubscriptionScope {

    /** May this user watch what happens to this ticket? */
    default boolean mayObserveTicket(long userId, long ticketId) {
        return false;
    }

    /** May this user watch this project's room? */
    default boolean mayObserveProject(long userId, long projectId) {
        return false;
    }
}
