package com.edunext.edutrack.api.feature.tickets.attachments;

import com.edunext.edutrack.domain.tickets.TicketAttachment;
import io.swagger.v3.oas.annotations.media.Schema;

import java.net.URI;
import java.time.Instant;
import java.util.List;

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
            @Schema(description = "C-026. Absent until thumbnails are generated.", nullable = true)
            URI thumbnailUrl,
            boolean isClientVisible,
            boolean isDeleted,
            Long uploadedBy,
            String stageCode,
            Integer cycleNo,
            Instant createdAt) {

        static AttachmentDto of(TicketAttachment row, URI downloadUrl) {
            return new AttachmentDto(
                    row.getId(),
                    row.getFileName(),
                    row.getMimeType(),
                    row.getSizeBytes(),
                    row.getScanStatus(),
                    downloadUrl,
                    // C-026 generates thumbnails; the column exists and is always
                    // null today. Rendered as null rather than omitted so the
                    // field's absence never means "this build has no thumbnails".
                    null,
                    row.isClientVisible(),
                    row.isDeleted(),
                    row.getUploadedBy(),
                    row.getStageCode(),
                    row.getCycleNo() == null ? null : (int) row.getCycleNo(),
                    row.getCreatedAt());
        }
    }

    record AttachmentResponse(AttachmentDto data) {
    }

    record AttachmentListResponse(List<AttachmentDto> data) {
    }
}
