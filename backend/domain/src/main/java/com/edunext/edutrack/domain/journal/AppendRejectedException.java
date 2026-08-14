package com.edunext.edutrack.domain.journal;

/**
 * A-040 · an append the {@link TicketJournal} refused.
 *
 * <p>Every one of these is a programming error rather than bad user input: a
 * caller supplied a hash it does not own, appended a hop while another is still
 * open, or reversed an entry without saying which. None of them can be reached
 * by a user typing something wrong — Stream C's DTO validation answers that with
 * a 400 long before the journal is called — so this deliberately extends
 * {@link IllegalArgumentException} and renders as a 500. A caller that catches
 * it to produce a friendlier message is hiding a bug in its own code.
 */
public class AppendRejectedException extends IllegalArgumentException {

    public AppendRejectedException(String message) {
        super(message);
    }

    /**
     * For a rejection whose real explanation was raised further down — in
     * practice a {@code CanonicalJsonException} from A-042's chain computation,
     * whose message names the fix precisely. The cause is kept rather than
     * flattened into a string so that whoever reads the stack trace sees which
     * value in the payload had no canonical form.
     */
    public AppendRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
