package com.edunext.edutrack.api.feature.onboarding.attachments;

import java.time.Instant;
import java.util.List;

/**
 * B-107 · the shapes the module's attachment routes answer with — the
 * contract's {@code ObAttachment}.
 *
 * <p>Public because the owner arms live in different feature packages by design
 * (OB-05's client documents here, C-121's step uploads and B-116's sign-off
 * evidence in theirs) and all of them render <b>one</b> row of one table. A
 * per-package view record would be four spellings of the same nine fields, and
 * the first to drift would be {@code downloadUrl} — the field whose absence is
 * the security property.
 */
public final class ObAttachmentDtos {

    private ObAttachmentDtos() {
    }

    /** {@code UserRef} — duplicated per package on {@code ObEscalationDtos.UserRef}'s own precedent. */
    public record ActorRef(long id, String displayName) {

        public static ActorRef of(Long id, String displayName) {
            return id == null ? null : new ActorRef(id, displayName);
        }
    }

    /**
     * One file, as OB-05 draws it.
     *
     * @param kind        {@code REFERENCE} — a document staff attached for the
     *                    client to read — or {@code SUBMISSION}, what the client
     *                    sent in. A-102's own vocabulary, and it is on the wire
     *                    because it decides what the portal may do with the row:
     *                    a client may replace their own submission and may never
     *                    touch a reference
     * @param scanStatus  PENDING | CLEAN | INFECTED | FAILED. <b>Returned, never
     *                    hidden</b> — C-025's rule and its reason: hiding a
     *                    pending row makes a scan delay indistinguishable from a
     *                    failed upload and leaves somebody re-attaching the same
     *                    file. The requirement is that the file not become
     *                    <em>readable</em>, and that is enforced by the absent
     *                    {@code downloadUrl} rather than by the row's absence
     * @param downloadUrl present only for a CLEAN row that has not been
     *                    tombstoned. Signed and short-lived, so it cannot
     *                    usefully be pasted somewhere else
     * @param isDeleted   true on a tombstone. A listing returns a removed row
     *                    only when the removal is one the record should keep —
     *                    see {@code ObClientAttachmentService.isVisibleTombstone}.
     *                    A tombstone never carries a {@code downloadUrl}: the
     *                    bytes are gone, not merely hidden
     * @param uploadedBy  the staff user who uploaded it, or null when the file
     *                    came from the client portal — {@code uploadedByContact}
     *                    is a row of a different table and B-126 is what makes
     *                    that case reachable. The client renders the contact's
     *                    side from {@code uploadedByType}
     */
    public record ObAttachmentView(
            long id,
            String fileName,
            String contentType,
            long sizeBytes,
            String kind,
            String uploadedByType,
            String scanStatus,
            String downloadUrl,
            boolean isDeleted,
            ActorRef uploadedBy,
            ActorRef deletedBy,
            Instant deletedAt,
            Instant createdAt) {
    }

    /** {@code { data }} — the envelope every response in this contract uses. */
    public record ObAttachmentResponse(ObAttachmentView data) {
    }

    /** {@code { data: [] }} — no {@code meta}; the set is bounded, see the contract. */
    public record ObAttachmentListResponse(List<ObAttachmentView> data) {
    }
}
