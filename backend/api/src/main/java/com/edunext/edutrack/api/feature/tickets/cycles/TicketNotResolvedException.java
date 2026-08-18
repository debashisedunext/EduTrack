package com.edunext.edutrack.api.feature.tickets.cycles;

/**
 * C-040 · <b>422</b> — the contract's own response shape on {@code closeTicket}
 * (an {@code UnprocessableTransition}, the same class {@code reopenTicket} uses
 * for {@link TicketNotClosedException}).
 *
 * <p>422 rather than 409, on {@code TicketNotClosedException}'s own precedent:
 * what refuses the request is the state of the row, not a collision with
 * another writer. 422 rather than 403 for the same reason too — the caller
 * holds {@code ticket.close} and is looking at a ticket they just fetched;
 * there is no existence to conceal and no capability missing.
 *
 * <p><b>Only {@code RESOLVED} may be closed.</b> {@code workflow_transitions}
 * seeds exactly one row set into {@code CLOSED}, and its {@code from_status} is
 * {@code RESOLVED} (row 12, {@code V20260807_1100}) — the sign-off move G-3
 * reserves for Admin, PM and Support. A ticket still {@code IN_PROGRESS} or
 * {@code REWORK} has not been claimed complete; one already {@code CLOSED} has
 * nothing left to close.
 */
class TicketNotResolvedException extends RuntimeException {

    private final String status;

    TicketNotResolvedException(String status) {
        super("this ticket is " + describe(status) + ", so there is no resolved cycle to close. "
                + "Only a RESOLVED ticket can be closed (§4A.2) — sign it off with a resolution "
                + "first, or reopen it if it is already CLOSED.");
        this.status = status;
    }

    String status() {
        return status;
    }

    /**
     * {@code CLOSED} is named specifically, on {@code TicketNotClosedException}'s
     * own reasoning for {@code RESOLVED}: it is the one wrong answer a user might
     * defend — "close" sounds idempotent — and naming the real state is what
     * stops a second, identical attempt.
     */
    private static String describe(String status) {
        if (status == null || status.isBlank()) {
            return "in no recorded status";
        }
        return "CLOSED".equals(status)
                ? "already CLOSED"
                : status;
    }
}
