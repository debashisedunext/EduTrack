package com.edunext.edutrack.api.feature.onboarding.clients;

/**
 * B-107 · the caller may see this document and may not remove it — 403.
 *
 * <h2>Why this concedes existence where the sibling 404 does not</h2>
 *
 * <p>CONVENTIONS.md §7 answers 404 wherever a refusal would otherwise confirm a
 * row. Nothing is confirmed here: the caller is looking at the row in a listing
 * they have just fetched through {@code listObClientAttachments}, which the
 * scoped client read already let them have. What is refused is the <em>verb</em>,
 * not the row — the same split {@link ObClientReadOnlyException} makes on the
 * client record and C-033 records between {@code deleteComment}'s 403 and
 * {@code editComment}'s 422.
 *
 * <p>Answering 404 instead would tell somebody that a file they can see on OB-05
 * does not exist, which is the kind of refusal that gets reported as a bug and
 * then worked around.
 *
 * <h2>Two messages, because the caller's next step differs</h2>
 *
 * <p>Inside the fifteen minutes, whoever is looking at a colleague's file may
 * simply be about to ask them to remove it. Outside it, nobody but an OB Admin
 * or an Onboarding Manager can act at all, and saying so saves a wasted request.
 *
 * <p>Neither message names the uploader. The row already does, and a refusal
 * phrased around a person reads as permanent when half of this rule is temporal.
 */
class ObClientAttachmentRemovalNotPermittedException extends RuntimeException {

    private ObClientAttachmentRemovalNotPermittedException(String message) {
        super(message);
    }

    static ObClientAttachmentRemovalNotPermittedException notTheUploader() {
        return new ObClientAttachmentRemovalNotPermittedException(
                "Only the person who uploaded this document can remove it in the first "
                        + "fifteen minutes. An OB Admin or an Onboarding Manager can remove it "
                        + "at any time.");
    }

    static ObClientAttachmentRemovalNotPermittedException windowClosed() {
        return new ObClientAttachmentRemovalNotPermittedException(
                "This document has been on the client record long enough that removing it is "
                        + "an OB Admin or Onboarding Manager action, and it will leave a "
                        + "\"removed by\" note either way.");
    }
}
