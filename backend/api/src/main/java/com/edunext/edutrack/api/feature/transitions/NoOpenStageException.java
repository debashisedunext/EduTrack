package com.edunext.edutrack.api.feature.transitions;

/**
 * C-042 · 422 — there is no open stage on this ticket's current cycle to
 * advance from.
 *
 * <p>Covers the two states this service will not build a hop onto: a ticket
 * whose first hop has never been written, and a ticket whose most recent open
 * hop belongs to an earlier, already-sealed-elsewhere cycle. The second case
 * is exactly what {@code ReopenService}'s own javadoc names and leaves
 * undone — a reopened ticket's new cycle starts with no first hop of its own,
 * and {@link com.edunext.edutrack.domain.journal.TicketJournal#openHopFor}
 * still finds the previous cycle's unsealed row. Opening that first hop is
 * not this method's job (it has no stage to advance <em>from</em> yet); it is
 * raised here rather than guessed at.
 */
class NoOpenStageException extends RuntimeException {

    NoOpenStageException(long ticketId) {
        super("ticket " + ticketId + " has no open stage on its current cycle to advance from. "
                + "Its ribbon needs a first hop opened for this cycle before it can be advanced.");
    }
}
