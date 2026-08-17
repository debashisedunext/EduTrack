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
 * <p><b>Only {@code CLOSED} may be reopened</b>, not {@code RESOLVED}.
 * {@code workflow_transitions} seeds exactly one row set into {@code REOPENED},
 * and its {@code from_status} is {@code CLOSED}; a resolved ticket goes back
 * through {@code RESOLVED → REWORK}, which is an iteration inside the current
 * cycle and not a new cycle at all. Accepting {@code RESOLVED} here would
 * increment the wrong counter and seal a cycle that had not finished — §4A.2's
 * two counters, confused in the one place it costs most.
 */
class TicketNotClosedException extends RuntimeException {

    private final String status;

    TicketNotClosedException(String status) {
        super("this ticket is " + describe(status) + ", so there is no closed cycle to seal. "
                + "A reopen starts a new cycle after closure; moving a ticket that is still open "
                + "backwards through its workflow is a rework iteration within the current cycle "
                + "(§4A.2) and does not go through this route.");
        this.status = status;
    }

    String status() {
        return status;
    }

    /**
     * {@code RESOLVED} is called out by name because it is the one wrong answer a
     * user would defend: the work looked finished, so reopening reads as the
     * obvious verb. Naming the right route in the message is what stops the
     * second attempt.
     */
    private static String describe(String status) {
        if (status == null || status.isBlank()) {
            return "in no recorded status";
        }
        return "RESOLVED".equals(status)
                ? "RESOLVED rather than CLOSED — it has not been signed off yet"
                : status;
    }
}
