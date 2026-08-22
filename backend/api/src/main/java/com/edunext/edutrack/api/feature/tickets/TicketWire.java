package com.edunext.edutrack.api.feature.tickets;

import com.fasterxml.jackson.annotation.JsonProperty;

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
            // B-066 · the contract's field is `ticketId` (blueprint's human-
            // readable code, TicketId-shaped) — this component kept the Java
            // name the entity accessor uses, and nothing was annotated, so
            // every one of the eight routes below has been serving this as
            // `ticketCode` in the actual JSON. No test caught it: every test
            // on this record calls the accessor directly or reads it from a
            // Java object before serialization, and ContractConformanceTest's
            // GET-body check only compares one level of nesting, where this
            // field sits two deep (`data.ticket.ticketId` on the detail read,
            // `data.tickets[].ticketId` on B-066's own Client 360). Caught
            // here because B-066 is the first caller to actually render the
            // nested field in a browser. Flagged for Stream C's sign-off
            // (Divyansh) rather than fixed quietly — it touches all eight
            // lifecycle routes' wire shape, even though the Java accessor
            // name (`ticketCode()`) is unchanged and every call site compiles
            // as-is.
            @JsonProperty("ticketId") String ticketCode,
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
            int pctComplete,

            /*
             * C-067 · §7.5's "Where it happened", appended rather than slotted in
             * beside description. The contract's Ticket schema is additive by
             * convention, and a record's component order is its JSON order under
             * springdoc — inserting mid-record would reorder four other fields in
             * the emitted spec and churn the generated client for nothing.
             *
             * moduleId is the id, not the name. The contract says so and gives the
             * reason: a ticket raised against a module since retired still has to
             * render, and the name is resolved through GET /masters/modules where
             * the deactivated rows are also returned.
             */
            Integer moduleId,
            String screenName,
            String feature,
            String stepsToGenerate) {
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
                t.getPctComplete(),
                t.getModuleId(), t.getScreenName(), t.getFeature(), t.getStepsToGenerate());
    }
}
