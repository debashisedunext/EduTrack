package com.edunext.edutrack.api.feature.portal.tickets;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * A-127 · the portal's own wire shapes for CP-06 and CP-07.
 *
 * <h2>Separate serializers, not narrowed staff ones</h2>
 *
 * <p>Plan §9: "separate portal DTO serializers, never staff DTOs with fields
 * hidden client-side". Nothing here is derived from {@code TicketDtos},
 * {@code CommentDto} or {@code AttachmentDtos}, and nothing here should become
 * so. A projection would be exactly a staff DTO with fields hidden, and it puts
 * the never-visible list — owners, internal comments, escalations, TAT
 * internals, other clients' anything — one forgotten annotation away from a
 * customer.
 *
 * <p>The consequence worth stating: a field added to the staff ticket shape does
 * <b>not</b> appear here, and that silence is the design. Anything a customer
 * should see is added deliberately, to this file, against the plan.
 */
final class PortalTicketDtos {

    private PortalTicketDtos() {
    }

    /**
     * One of the client's own tickets.
     *
     * <p>{@code projectName} rather than a project id: an id is a staff handle
     * and one a caller could try on the staff tree. {@code plannedCloseDate} is
     * served because the date is a commitment already made to this client; the
     * SLA ladder that produced it, the breach flags and the stage cloacks are TAT
     */
    @Schema(name = "PortalTicket")
    record PortalTicket(
            long id,
            String ticketId,
            String title,
            String description,
            String status,
            String projectName,
            String taskTypeName,
            Instant dateReported,
            Instant plannedCloseDate,
            Instant actualCloseDate,
            Instant lastUpdatedAt) {
    }

    /**
     * A comment a colleague deliberately marked client-visible.
     *
     * <p>{@code authorName} is a display name and nothing else — never a
     * {@code UserRef}, which carries the id, email and role that make up a staff
     * handle. Serving the name at all follows {@code ObStepCommunication}, which
     * already publishes exactly this to exactly this principal: somebody who
     * marked a comment client-visible wrote it <em>to</em> the customer, which is
     * not the ownership fact the never-visible list is about.
     *
     * <p>{@code authorType} carries {@code CLIENT} although nothing emits it yet.
     * The portal thread is read-only in phase 1, so the value is a branch the
     * screen can build against rather than a shape that has to change later.
     */
    @Schema(name = "PortalComment")
    record PortalComment(
            long id,
            String body,
            String authorType,
            String authorName,
            Instant createdAt) {
    }

    /**
     * A client-visible file that has cleared the scanner.
     *
     * <p>No {@code scanStatus} and no {@code uploadedBy}: only CLEAN rows are
     * listed, so the first could only ever say CLEAN, and the second is an owner.
     * No {@code isClientVisible} either — every row here has already been
     * filtered on it, and {@code ChatAttachment}'s note records why a constant
     * flag is worse than an absent one.
     */
    @Schema(name = "PortalAttachment")
    record PortalAttachment(
            long id,
            String fileName,
            String contentType,
            long sizeBytes,
            String downloadUrl,
            String thumbnailUrl,
            Instant createdAt) {
    }

    /** The {@code { data }} envelope for the single-ticket read. */
    @Schema(name = "PortalTicketResponse")
    record PortalTicketResponse(PortalTicket data) {
    }
}
