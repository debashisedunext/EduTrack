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

    /**
     * D-059 · may this user watch one team's queue on one project?
     *
     * <p>Narrower than it sounds, and deliberately so. What travels on a stage
     * topic is a <strong>nudge and nothing else</strong> — "the QA queue on
     * project 7 moved" — with no ticket id, code, title or assignee in the
     * frame. The rows themselves come from {@code GET /stages/queue}, which
     * applies whatever scope that endpoint applies. So granting this is not
     * granting a view of anybody's tickets, and this question can be answered
     * without settling the one C-062 has to.
     *
     * <p><strong>If a ticket identity is ever put into a stage frame, this rule
     * has to be revisited first.</strong> That is the change that would turn a
     * nudge into a feed, and it would not look like a security change in a diff.
     */
    default boolean mayObserveStage(long userId, String stageCode, long projectId) {
        return false;
    }
}
