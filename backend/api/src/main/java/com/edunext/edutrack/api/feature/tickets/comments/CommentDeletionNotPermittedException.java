package com.edunext.edutrack.api.feature.tickets.comments;

import java.io.Serial;

/**
 * C-033 · 403. The caller can read this comment and may not remove it.
 *
 * <h2>403 here, where {@link CommentNotEditableException} answers 422</h2>
 *
 * <p>The two refusals are genuinely different shapes. An edit can be refused
 * because of the <em>row</em> — the window closed, and the caller may well have
 * been entitled four minutes ago — so 422 is right there. A deletion is refused
 * purely because of <em>who is asking</em>: there is no window, no clock, and
 * nothing about the comment that would ever make a Developer able to remove a
 * colleague's remark. That is what 403 means.
 *
 * <h2>And 403 rather than 404, which the rest of this feature answers</h2>
 *
 * <p>C-028 argued this at length for the mirror-image case and every word holds.
 * A-035's rule is not weakened, because it does not apply: reaching this exception
 * means {@code ScopedTickets} already passed the caller and
 * {@link CommentService#delete} already confirmed the row is on that ticket. The
 * caller is looking at the comment, with its author and its text, in a thread they
 * just fetched. <b>There is no existence to leak.</b>
 *
 * <p>The distinction worth holding on to is C-028's: <em>404 conceals whether a
 * row exists; 403 concedes that it does and refuses the verb.</em>
 */
class CommentDeletionNotPermittedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    CommentDeletionNotPermittedException() {
        super("Only the person who wrote a comment, a project manager or an administrator "
                + "can remove it. Whoever removes it, the thread keeps a note that it was here.");
    }
}
