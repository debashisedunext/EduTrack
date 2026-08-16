package com.edunext.edutrack.api.feature.tickets.list;

import com.edunext.edutrack.common.pagination.PageMeta;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The shapes {@code GET /tickets} answers with.
 *
 * <p>Flat ids rather than nested {@code project} and {@code client} objects.
 * The contract's {@code Ticket} schema nests them, and a list is exactly where
 * that costs most — one row per ticket becomes one project lookup per ticket,
 * and S-17 renders 50 rows. The masters are small and cached client-side, which
 * is the pattern {@code moduleId} already documents in the contract: "resolve
 * the name through {@code GET /masters/modules} rather than expecting it
 * inline". Same argument, same answer.
 */
final class TicketListDtos {

    private TicketListDtos() {
    }

    record ListResponse(List<TicketSummary> data, PageMeta meta) {
    }

    /**
     * One grid row.
     *
     * <p>Deliberately not the 58-property {@code Ticket} schema. S-17 shows a
     * row, not a record: description, steps-to-generate and the rest are the
     * detail page's job, and shipping 20 KB of sanitised HTML per row to render
     * a title would make the list slower than the waterfall A-052 exists to
     * remove.
     */
    record TicketSummary(
            long id,
            String ticketCode,
            String title,
            long projectId,
            Long clientId,
            boolean isClientRaised,
            Integer taskTypeId,
            String level,
            String originalLevel,
            String status,
            String currentStage,
            Long assignedTo,
            Long reportedBy,
            int cycleNo,
            int iterationNo,
            int reopenCount,
            boolean isReopened,
            Instant dateReported,
            Instant plannedCloseDate,
            Instant actualCloseDate,
            boolean isDelayed,
            Instant delayedSince,
            BigDecimal estimatedEffortHrs,
            BigDecimal totalEffortHrs,
            int commentCount,
            int attachmentCount,
            Instant createdAt) {
    }
}
