package com.edunext.edutrack.api.feature.tickets.comments;

import java.io.Serial;

/**
 * C-033 · 422. The comment exists, the caller can read it, and it may not be
 * rewritten.
 *
 * <p>The contract types all three refusals as one status and one description —
 * <em>"edit window has closed, or the caller is not the author"</em> — so this is
 * one class with three factories rather than three classes. There is nothing for
 * a client to branch on: every case ends the same way, with the box closing and
 * the comment staying as it was written.
 *
 * <h2>422 and not 403, unlike the neighbouring delete</h2>
 *
 * <p>C-028 answers 403 when somebody may see an attachment and may not remove it,
 * and this route deliberately does not follow it. The contract settled the status
 * before either task existed, and it is the better fit in any case: a 403 says
 * <em>you</em> may not do this, which is true of the author-only case and false of
 * the window, where the caller is the author and was entitled to do it four
 * minutes ago. What changed is the state of the row, and 422 is the status for a
 * request that is well-formed and cannot be applied to the resource as it stands.
 *
 * <p>None of this weakens A-035. Row scope is settled by {@code ScopedTickets}
 * before this package sees the request, and the ticket check inside
 * {@code CommentService} throws {@link CommentNotFoundException} before anything
 * here is reachable. By the time this is thrown the caller is looking at the
 * comment in a thread they just fetched.
 */
class CommentNotEditableException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private CommentNotEditableException(String message) {
        super(message);
    }

    /**
     * §4B.5's five minutes have run out.
     *
     * <p>The message says the comment is locked rather than telling the caller
     * to ask an Admin, because <b>no role can unlock it</b>. That is the whole
     * point of the rule and it is the one refusal in this codebase with no
     * escalation path — offering one, even implicitly, would be a lie.
     */
    static CommentNotEditableException windowClosed() {
        return new CommentNotEditableException(
                "The five minutes for editing this comment have passed and it is now locked. "
                        + "Post a follow-up comment instead — that way the thread shows the correction "
                        + "as well as what it corrects.");
    }

    /**
     * Somebody else's words.
     *
     * <p><b>Admin and PM are refused here and allowed on the delete</b>, and the
     * asymmetry is §4B.5's own: "no role, including Admin, can silently rewrite a
     * comment". A supervisory <em>removal</em> leaves a tombstone saying who
     * removed it, so the record survives the act. A supervisory <em>edit</em>
     * cannot — whatever marker it left, the thread would still attribute the new
     * wording to the original author, which is precisely the rewrite the sentence
     * forbids. The remedy for a comment that must not stand is to delete it, in
     * the open, or to reply to it.
     */
    static CommentNotEditableException notTheAuthor() {
        return new CommentNotEditableException(
                "Only the person who wrote a comment can edit it — no role can change "
                        + "someone else's words. Reply in the thread, or remove the comment, "
                        + "which leaves a note that it was here.");
    }

    /**
     * Already a tombstone.
     *
     * <p>Reachable through an ordinary race — two tabs, or a slow client editing
     * a comment a PM has just removed — so it is worded as a fact about the row
     * rather than as a rejection of the caller.
     */
    static CommentNotEditableException alreadyDeleted() {
        return new CommentNotEditableException(
                "This comment has been removed, so there is nothing left to edit.");
    }
}
