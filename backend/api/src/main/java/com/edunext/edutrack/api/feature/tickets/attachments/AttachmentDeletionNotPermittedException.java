package com.edunext.edutrack.api.feature.tickets.attachments;

import java.io.Serial;

/**
 * C-028 · 403. Blueprint §4B.4's deletion rule — "the uploader may delete within
 * 15 minutes; after that it is a soft delete leaving a tombstone".
 *
 * <h2>403 here, where the rest of this feature answers 404</h2>
 *
 * <p>A-035's rule is that an out-of-scope id answers <b>404</b>, so a caller
 * cannot learn that a ticket exists by being refused access to it. That rule is
 * not weakened here, because it does not apply: reaching this exception means
 * {@code ScopedTickets} already let the caller through and
 * {@link AttachmentService#delete} already confirmed the row is on that ticket.
 * The caller can see this attachment — it is in the listing they just rendered,
 * with its file name and its size. <b>There is no existence to leak.</b>
 *
 * <p>Answering 404 anyway would be worse than merely pedantic. The client would
 * have to render "that attachment no longer exists" for a file still on screen,
 * and the user's next action — asking a PM to remove it — is one the true answer
 * suggests and the false one hides.
 *
 * <p>The distinction to hold on to: <em>404 conceals whether a row exists; 403
 * concedes that it does and refuses the verb.</em> Row scope is the first
 * question and is answered before this class is reachable.
 */
class AttachmentDeletionNotPermittedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private AttachmentDeletionNotPermittedException(String message) {
        super(message);
    }

    /**
     * Somebody else's file, inside the window.
     *
     * <p>The message names the window rather than the uploader. Who attached it
     * is already on the row the caller is looking at, and phrasing the refusal
     * around the person ("only Ravi Kumar may remove this") reads as a standing
     * rule when it is a temporary one — the caller may well be able to remove it
     * in ten minutes, and that is the fact worth stating.
     */
    static AttachmentDeletionNotPermittedException notTheUploader() {
        return new AttachmentDeletionNotPermittedException(
                "Only the person who attached this file can remove it in the first few minutes. "
                        + "After that a project manager or an administrator can remove it, "
                        + "leaving a note that it was here.");
    }

    /**
     * Outside the window, and not one of the three who may still act.
     *
     * <p>Deliberately does not say "ask a PM" a second time — the caller
     * <em>is</em> being told what the rule is, and a refusal that ends in an
     * instruction to escalate every time trains people to ignore it.
     */
    static AttachmentDeletionNotPermittedException windowClosed() {
        return new AttachmentDeletionNotPermittedException(
                "The window for removing this file has passed. A project manager or an administrator "
                        + "can still remove it, and the ticket will show that it was here.");
    }
}
