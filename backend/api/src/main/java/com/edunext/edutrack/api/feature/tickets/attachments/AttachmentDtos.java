package com.edunext.edutrack.api.feature.tickets.attachments;

import com.edunext.edutrack.domain.tickets.TicketAttachment;
import io.swagger.v3.oas.annotations.media.Schema;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * C-025 · the wire shapes for {@code listAttachments} and
 * {@code uploadAttachment}, per {@code contracts/openapi.yaml}'s
 * {@code Attachment} schema.
 *
 * <p>Field-for-field with the contract, which needed no change for this task —
 * {@code scanStatus} and the nullable {@code downloadUrl} were specified from
 * the beginning and describe exactly the behaviour §4B.4 asks for.
 */
final class AttachmentDtos {

    private AttachmentDtos() {
    }

    /**
     * The contract's {@code UserRef}, as this feature needs it.
     *
     * <p>Only the two fields {@code UserRef} declares as required. {@code avatarUrl}
     * and {@code role} are optional in the schema and are not populated here: the
     * two places this appears are an uploader's name under a file and §4B.4's
     * tombstone, and neither draws an avatar or branches on a role. Sending them
     * would mean a join per listing for fields nothing reads.
     *
     * <p>Declared locally rather than shared, following {@code ChatDtos.UserRef}
     * and {@code ProjectDtos}: a common DTO across four streams' features is shared
     * surface that has to be renegotiated whenever any one of them needs another
     * field. The contract is the agreement, not a Java class.
     */
    @Schema(name = "UserRef")
    record UserRef(long id, String displayName) {
    }

    /**
     * @param downloadUrl <b>null until the scan passes.</b> Not an error and not
     *                    an omission: it is how "stored but not yet vouched for"
     *                    is expressed on the wire, and it is the reason the
     *                    client renders a pending state rather than a broken
     *                    image. An INFECTED or deleted attachment is null for
     *                    ever — see {@link AttachmentService#signedUrlFor}
     * @param storageKey  deliberately absent from this record. The key is not a
     *                    URL and reveals nothing on its own, but publishing it
     *                    would invite a client to build one
     */
    @Schema(name = "Attachment")
    record AttachmentDto(
            Long id,
            String fileName,
            String contentType,
            long sizeBytes,
            @Schema(description = "Not downloadable until CLEAN.", allowableValues = {"PENDING", "CLEAN", "INFECTED"})
            String scanStatus,
            @Schema(description = "Short-lived signed URL. Never a public bucket path.", nullable = true)
            URI downloadUrl,
            @Schema(description = """
                    C-026 · a short-lived signed URL for a small PNG reduction, on the same terms as \
                    `downloadUrl`. Null whenever there is no reduction to serve — a document, a video, \
                    an image the server cannot decode, or one already small enough that the original \
                    is the thumbnail. A client must render an image without one from `downloadUrl` \
                    rather than treat it as missing.""", nullable = true)
            URI thumbnailUrl,
            boolean isClientVisible,
            boolean isDeleted,
            @Schema(description = """
                    Who attached it. **C-028 corrected this from a bare id to the `UserRef` the \
                    contract and the generated client have always declared** — the mismatch went \
                    unnoticed through C-025, C-026 and C-027 because no screen read the field.""",
                    nullable = true)
            UserRef uploadedBy,
            @Schema(description = """
                    C-028 · who removed it, on a row where `isDeleted` is true. Null otherwise, and \
                    null on a removal by a user account that no longer exists — the client renders \
                    the tombstone without an actor rather than inventing a name.""", nullable = true)
            UserRef deletedBy,
            @Schema(description = """
                    C-028 · when it was removed. Present exactly when `deletedBy` can be, and the \
                    other half of §4B.4's "file removed by X on date".""", nullable = true)
            Instant deletedAt,
            String stageCode,
            Integer cycleNo,
            Instant createdAt) {

        /**
         * @param downloadUrl  from {@link AttachmentService#signedUrlFor}, or null
         * @param thumbnailUrl from {@link AttachmentService#thumbnailUrlFor}, or
         *                     null — which is the ordinary case for most of what
         *                     gets attached, not an error
         * @param people       resolved ids from {@link AttachmentUserRefs#resolve},
         *                     which is given every id in the listing at once. A
         *                     missing id yields null rather than a placeholder
         */
        static AttachmentDto of(TicketAttachment row,
                                URI downloadUrl,
                                URI thumbnailUrl,
                                Map<Long, UserRef> people) {
            return new AttachmentDto(
                    row.getId(),
                    row.getFileName(),
                    row.getMimeType(),
                    row.getSizeBytes(),
                    row.getScanStatus(),
                    downloadUrl,
                    // Rendered as null rather than omitted, so the field's absence
                    // never reads as "this build has no thumbnails".
                    thumbnailUrl,
                    row.isClientVisible(),
                    row.isDeleted(),
                    person(people, row.getUploadedBy()),
                    // C-028. Both are stamped by the same write, so a row carrying
                    // one without the other is a hand edit rather than a state this
                    // application produces — they are passed through as they are
                    // found rather than reconciled, because inventing the missing
                    // half would conceal exactly that.
                    person(people, row.getDeletedBy()),
                    row.getDeletedAt(),
                    row.getStageCode(),
                    row.getCycleNo() == null ? null : (int) row.getCycleNo(),
                    row.getCreatedAt());
        }

        private static UserRef person(Map<Long, UserRef> people, Long id) {
            return id == null ? null : people.get(id);
        }
    }

    record AttachmentResponse(AttachmentDto data) {
    }

    record AttachmentListResponse(List<AttachmentDto> data) {
    }
}
