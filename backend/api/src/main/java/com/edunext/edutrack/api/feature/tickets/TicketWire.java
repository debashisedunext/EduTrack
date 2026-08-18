package com.edunext.edutrack.api.feature.tickets;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The contract's {@code Ticket} schema, and the one mapping from the entity onto
 * it.
 *
 * <p>Extracted by C-038 rather than invented by it. A-052 wrote this record
 * inside {@code detail/TicketDetailDtos} because the detail page was the only
 * thing answering with a ticket; {@code reopenTicket} answers
 * {@code TicketResponse}, which is {@code { data: Ticket }} — the same schema —
 * and so does every other lifecycle route the contract declares
 * ({@code assignTicket}, {@code changeStatus}, {@code closeTicket},
 * {@code resolveTicket}, eight in total). A second copy of a 24-field wire
 * record is a second place for a field to be forgotten, and the forgetting is
 * silent: the response still serialises, and the client reads {@code undefined}
 * from a field the schema says is always there.
 *
 * <p>So the record lives beside the feature rather than inside one route's DTO
 * file, and {@link #of} is the only place an entity becomes a wire ticket.
 *
 * <p><b>Field order is the contract's, and the JSON is byte-identical to what
 * A-052 shipped</b> — the record was moved, not rewritten, so the detail
 * endpoint's response does not change.
 */
public final class TicketWire {

    private TicketWire() {
    }

    /**
     * @param level         current, and the escalation engine may raise it
     * @param originalLevel what it was raised at; never mutated (A-070's
     *                      "born critical vs became critical")
     * @param totalEffortHrs Σ across <em>all</em> cycles, which is why a reopen
     *                      never touches it — see {@code ReopenService}
     * @param currentCycleNo increments on reopen after closure;
     *                      {@code currentIteration} increments on a backward
     *                      move within a cycle. Two independent counters
     *                      (§4A.2), the most misread pair in the spec
     * @param pctComplete   C-036 · S-21's slider. Added after A-052 first wrote
     *                      this record, hence out of the contract's own field
     *                      order — appending rather than reordering keeps every
     *                      existing caller's JSON a prefix of its new one
     */
    public record Ticket(
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
            BigDecimal estimatedEffortHrs,
            BigDecimal totalEffortHrs,
            Instant plannedCloseDate,
            Instant actualCloseDate,
            boolean isReopened,
            int reopenCount,
            int currentCycleNo,
            boolean isDelayed,
            String currentStage,
            int currentIteration,
            int reworkCount,
            int pctComplete) {
    }

    /** The entity as the contract's {@code Ticket}. */
    public static Ticket of(com.edunext.edutrack.domain.tickets.Ticket t) {
        return new Ticket(
                t.getId(), t.getTicketCode(), t.getProjectId(), t.getTitle(), t.getDescription(),
                t.getTaskTypeId(), t.getLevel(), t.getOriginalLevel(), t.getStatus(),
                t.getEnvironment(), t.getDateReported(), t.getReportedBy(), t.getAssignedTo(),
                t.getEstimatedEffortHrs(), t.getTotalEffortHrs(), t.getPlannedCloseDate(),
                t.getActualCloseDate(), t.isReopened(), t.getReopenCount(), t.getCurrentCycleNo(),
                t.isDelayed(), t.getCurrentStage(), t.getCurrentIteration(), t.getReworkCount(),
                t.getPctComplete());
    }
}
