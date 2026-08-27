package com.edunext.edutrack.api.feature.tickets.cycles;

/**
 * C-038 · <b>422</b> — the contract's own response on {@code reopenTicket}:
 * "Ticket is not closed, so there is nothing to reopen."
 *
 * <p>422 rather than 409, because the contract says 422 and because what refuses
 * the request is the state of the row rather than a collision with another
 * writer: nobody's concurrent edit made this ticket open, it simply is. The same
 * reasoning C-033 wrote for {@code CommentNotEditableException}, which is the
 * precedent this follows.
 *
 * <p>422 rather than 403 for a different reason. The caller is looking at a
 * ticket they just fetched and holds {@code ticket.reopen}; there is no
 * existence to conceal and no capability missing. What is wrong is the request,
 * against a ticket the caller can see perfectly well.
 *
 * <p><b>{@code CLOSED} and {@code RESOLVED} may both be reopened</b>, as of
 * 26 Aug 2026 ({@code V20260826_2145}). This javadoc used to say the opposite
 * and to argue that accepting {@code RESOLVED} "would increment the wrong
 * counter and seal a cycle that had not finished". The second half was the
 * real concern and it is answered rather than waived: when the Support desk
 * refuses a sign-off, the cycle <em>has</em> finished — that is precisely what
 * the desk is saying — and {@code ReopenService} seals it with the reason
 * recorded on the cycle that follows. The first half no longer holds either.
 * §4A.2's two counters answer two questions, and on this route the cycle
 * counter is the right one: an iteration is work bouncing backwards inside an
 * attempt, a cycle is the attempt being made again because what was delivered
 * was not accepted.
 *
 * <p>What is still refused is every other status. A ticket in
 * {@code IN_PROGRESS}, {@code REWORK} or {@code AWAITING_INFO} is mid-attempt,
 * and going backwards inside one is {@code POST /rework} — an iteration, which
 * is what {@link #describe} still points those callers at.
 */
class TicketNotClosedException extends RuntimeException {

    private final String status;

    TicketNotClosedException(String status) {
        super("this ticket is " + describe(status) + ", so there is no finished cycle to seal. "
                + "A reopen starts a new cycle after a closure or a refused sign-off; moving a "
                + "ticket that is still mid-attempt backwards through its workflow is a rework "
                + "iteration within the current cycle (§4A.2) and does not go through this route.");
        this.status = status;
    }

    String status() {
        return status;
    }

    /**
     * {@code RESOLVED} no longer appears here: it is a status this route
     * accepts, so it can never reach this exception. The special case that used
     * to name it is gone rather than left to rot into a message nobody can
     * trigger.
     */
    private static String describe(String status) {
        if (status == null || status.isBlank()) {
            return "in no recorded status";
        }
        return status;
    }
}
