package com.edunext.edutrack.api.feature.tickets.detail;

import com.edunext.edutrack.api.feature.tickets.TicketWire;
import com.edunext.edutrack.api.feature.tickets.links.TicketLinkDtos;
import com.edunext.edutrack.api.feature.transitions.RibbonWire;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * A-052 · the shapes {@code GET /tickets/{ticketId}/full} answers with.
 *
 * <p>Mirrors {@code TicketDetailResponse} in the contract. Two of its nine
 * fields are deliberately absent and {@link TicketDetailService} explains why.
 */
final class TicketDetailDtos {

    private TicketDetailDtos() {
    }

    /** The envelope: {@code { data }}, per blueprint §1652. */
    record DetailResponse(Detail data) {
    }

    /**
     * @param linkedTickets   C-064 · {@code TicketLinkService.viewsFor}'s shape
     *                        reused rather than duplicated — see that class's
     *                        javadoc on why the source/target label derivation
     *                        must have one implementation, not two
     * @param ribbon          the current cycle's journey, from
     *                        {@code RibbonAssembler} — null only for a ticket
     *                        with no {@code workflow_template_id} (predates the
     *                        workflow designer), see {@link TicketDetailService}
     * @param availableActions C-043 · {@code handoff}/{@code rework} when the
     *                        golden rule allows it, empty otherwise — see
     *                        {@link TicketDetailService}
     */
    record Detail(
            TicketWire.Ticket ticket,
            List<Cycle> cycles,
            RibbonWire.Ribbon ribbon,
            List<HistoryEntry> history,
            List<EffortLog> effortLogs,
            List<Comment> comments,
            List<Attachment> attachments,
            List<UserRef> watchers,
            List<TicketLinkDtos.LinkedTicketView> linkedTickets,
            List<String> availableActions) {
    }

    /*
     * The `ticket` field's shape is TicketWire.Ticket, one package up.
     *
     * A-052 declared it here because the detail page was the only route
     * answering with a ticket. C-038's `reopenTicket` answers the same schema —
     * TicketResponse is { data: Ticket } — and so do the six other lifecycle
     * routes the contract declares, so the record moved to where all of them can
     * reach it rather than being copied per route. The JSON is unchanged: same
     * component names, same order, same mapping.
     */

    record Cycle(
            int cycleNo,
            boolean isSealed,
            Instant startedAt,
            Instant closedAt,
            String reason,
            java.math.BigDecimal effortHrs) {
    }

    /**
     * One append-only journal row. {@code isCorrection} and {@code correctsEntryId}
     * are surfaced rather than hidden: a compensating entry is the only way this
     * table records a change of mind (A-043), and a client that cannot see which
     * rows are reversals renders a history that contradicts itself.
     */
    record HistoryEntry(
            long id,
            Short cycleNo,
            String eventType,
            String fieldName,
            String oldValue,
            String newValue,
            Long actorId,
            String actorType,
            String remarks,
            boolean isCorrection,
            Long correctsEntryId,
            Instant createdAt) {
    }

    record EffortLog(
            long id,
            int cycleNo,
            String stageCode,
            int iterationNo,
            long userId,
            LocalDate workDate,
            java.math.BigDecimal hours,
            String note,
            boolean isCorrection,
            Long correctsEntryId,
            Instant loggedAt) {
    }

    record Comment(
            long id,
            Long authorId,
            String bodyHtml,
            boolean isInternal,
            boolean isEdited,
            Instant editedAt,
            Instant createdAt) {
    }

    record Attachment(
            long id,
            String fileName,
            String mimeType,
            long sizeBytes,
            String thumbnailKey,
            boolean isClientVisible,
            String scanStatus,
            Long uploadedBy,
            Instant createdAt) {
    }

    record UserRef(long id, String displayName) {
    }
}
