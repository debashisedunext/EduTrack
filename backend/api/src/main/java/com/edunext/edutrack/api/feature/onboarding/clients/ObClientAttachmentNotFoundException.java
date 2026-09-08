package com.edunext.edutrack.api.feature.onboarding.clients;

/**
 * B-107 · no such attachment <b>under this client</b> — 404.
 *
 * <p>The attachment id is always resolved together with the client id it is
 * nested under ({@code findByIdAndObClientId}), so a real file belonging to
 * somebody else's client answers exactly the same as an invented id.
 * {@link ObContactNotFoundException} gives the reasoning at length and it is
 * unchanged here, with one thing worth adding about this table specifically:
 * {@code ob_attachments} is polymorphic within the module, so its ids are drawn
 * from a sequence shared with every journey step's and sign-off's file. A status
 * that distinguished "not yours" from "not there" would therefore enumerate not
 * just this client's documents but the module's entire upload history one
 * integer at a time.
 *
 * <p>A caller who may see the file and may not remove it is
 * {@link ObClientAttachmentRemovalNotPermittedException} and 403 — by then the
 * scoped client read has already handed them the listing it is in.
 */
class ObClientAttachmentNotFoundException extends RuntimeException {

    ObClientAttachmentNotFoundException(long obClientId, long attachmentId) {
        super("no attachment " + attachmentId + " on onboarding client " + obClientId);
    }
}
