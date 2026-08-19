package com.edunext.edutrack.api.feature.tickets.links;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

/**
 * C-064 · the wire shapes for {@code /tickets/{ticketId}/links}, per
 * {@code contracts/openapi.yaml}.
 *
 * <p>{@code public}, unlike {@code PriorityChangeDtos} and its siblings —
 * {@link TicketRef} and {@link LinkedTicketView} are reused by
 * {@code detail.TicketDetailService} for {@code TicketDetailResponse
 * .linkedTickets}, on {@code TicketWire}'s own precedent for a shape more
 * than one route answers with. A nested type cannot be {@code public} to
 * another package while its enclosing class stays package-private.
 */
public final class TicketLinkDtos {

    private TicketLinkDtos() {
    }

    /** The contract's {@code CreateTicketLinkRequest}. */
    record CreateLinkRequest(
            @NotBlank String targetTicketId,
            @NotBlank String linkType) {
    }

    /**
     * The contract's {@code TicketRef} — the linked ticket's own identity,
     * enough to render a chip without a second fetch. {@code public} so
     * {@code TicketDetailService} (a sibling package) can reuse it for
     * {@code TicketDetailResponse.linkedTickets} rather than declaring a
     * second, identical shape.
     */
    public record TicketRef(String ticketId, String title, String level, String status) {

        public static TicketRef of(com.edunext.edutrack.domain.tickets.Ticket t) {
            return new TicketRef(t.getTicketCode(), t.getTitle(), t.getLevel(), t.getStatus());
        }
    }

    /**
     * The contract's minimal {@code UserRef} — {@code id} and
     * {@code displayName} only, on {@code AttachmentUserRefs}'s own
     * reasoning: both fields the contract requires, nothing this feature
     * has no use for.
     */
    public record UserRef(long id, String displayName) {
    }

    /**
     * The contract's {@code LinkedTicket}. {@code public} for the same
     * reason as {@link TicketRef}.
     *
     * @param linkType how this row reads <b>from the ticket it is being
     *                 shown under</b> — see {@link TicketLinkType#inverse()}.
     *                 Not necessarily the type the row was created with.
     */
    public record LinkedTicketView(
            long id,
            String linkType,
            TicketRef ticket,
            Instant createdAt,
            UserRef createdBy) {
    }

    /** The contract's {@code LinkedTicketResponse} — {@code { data: LinkedTicket }}. */
    record LinkedTicketResponse(LinkedTicketView data) {
    }
}
