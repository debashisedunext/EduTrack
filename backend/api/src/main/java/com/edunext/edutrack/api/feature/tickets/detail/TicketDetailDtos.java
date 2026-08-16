package com.edunext.edutrack.api.feature.tickets.detail;

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
     * @param ribbon          always null for now — see {@link TicketDetailService}
     * @param availableActions always null for now — see {@link TicketDetailService}
     */
    record Detail(
            Ticket ticket,
            List<Cycle> cycles,
            Object ribbon,
            List<HistoryEntry> history,
            List<EffortLog> effortLogs,
            List<Comment> comments,
            List<Attachment> attachments,
            List<UserRef> watchers,
            List<String> availableActions) {
    }

    record Ticket(
            long id,
            String ticketCode,
            long projectId,
            String title,
            String description,
            Integer taskTypeId,
            String level,
            String originalLevel,
            String status,
            String environment,
            Instant dateReported,
            Long reportedBy,
            Long assignedTo,
            java.math.BigDecimal estimatedEffortHrs,
            java.math.BigDecimal totalEffortHrs,
            Instant plannedCloseDate,
            Instant actualCloseDate,
            boolean isReopened,
            int reopenCount,
            int currentCycleNo,
            boolean isDelayed,
            String currentStage,
            int currentIteration,
            int reworkCount) {
    }

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

    record UserRef(long id) {
    }
}
