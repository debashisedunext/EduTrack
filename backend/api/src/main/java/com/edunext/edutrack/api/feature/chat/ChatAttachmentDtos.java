package com.edunext.edutrack.api.feature.chat;

import java.time.Instant;

/**
 * D-053 · the shapes the chat attachment routes take and answer —
 * {@code ChatAttachment} in the contract.
 */
final class ChatAttachmentDtos {

    private ChatAttachmentDtos() {
    }

    /**
     * @param scanStatus PENDING | CLEAN | INFECTED. <b>Returned, never hidden</b>
     *                   — C-025's own rule and its reason: hiding a pending row
     *                   makes a scan delay indistinguishable from a failed upload
     *                   and leaves a user re-attaching the same file. §7.6's
     *                   requirement is that the file not become <em>readable</em>,
     *                   and that is enforced by the absent {@code downloadUrl}
     *                   rather than by the row's absence
     * @param downloadUrl present only for a CLEAN row that has not been
     *                    tombstoned. Signed and short-lived, so it cannot be
     *                    pasted into a channel somebody else reads
     * @param contentType the <b>sniffed</b> type, named as the contract's
     *                    {@code Attachment.contentType} names it — the column is
     *                    {@code mime_type} and the wire has always said
     *                    {@code contentType}; a chat file that disagreed would
     *                    make one client parse two spellings of one idea
     * @param isImage     whether the client should render it inline. Decided from
     *                    the <b>sniffed</b> MIME type, never from the file name —
     *                    a client that renders an {@code <img>} from a declared
     *                    extension will happily try it on a renamed executable
     */
    record ChatAttachmentView(
            long id,
            String fileName,
            String contentType,
            long sizeBytes,
            String scanStatus,
            boolean isImage,
            String downloadUrl,
            ChatDtos.UserRef uploadedBy,
            Instant createdAt) {
    }

    /** {@code { data }} — the envelope every response in this contract uses. */
    record ChatAttachmentResponse(ChatAttachmentView data) {
    }
}
