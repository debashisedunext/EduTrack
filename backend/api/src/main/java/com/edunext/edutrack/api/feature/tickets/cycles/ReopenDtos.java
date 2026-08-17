package com.edunext.edutrack.api.feature.tickets.cycles;

import com.edunext.edutrack.api.feature.tickets.TicketWire;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * C-038 · the shapes {@code POST /tickets/{ticketId}/reopen} takes and answers,
 * mirroring {@code ReopenRequest} and {@code TicketResponse} in the contract.
 */
final class ReopenDtos {

    private ReopenDtos() {
    }

    /**
     * S-22's five fields. Only the reason is required, and every other field
     * falls back to something the ticket already knows — see
     * {@link ReopenService}, which resolves each default and says why.
     *
     * @param reason           why the ticket is being reopened. Mandatory in the
     *                         contract, mandatory in the dialog, and mandatory in
     *                         {@code workflow_transitions} — the seeded
     *                         {@code CLOSED → REOPENED} rows all carry
     *                         {@code requires_reason = 1}. It is stored on the
     *                         <em>new</em> cycle's {@code reopen_reason}, which is
     *                         what makes "why does this ticket have three cycles"
     *                         answerable per cycle rather than once per ticket
     * @param restartStageCode where the new cycle's ribbon begins, defaulting to
     *                         {@code TRIAGE} per the contract
     * @param assigneeId       null keeps whoever held the closing cycle, per S-22
     *                         ("new assignee (defaults to previous)")
     * @param plannedCloseDate null recomputes it from the SLA matrix rather than
     *                         reusing cycle N's, which is in the past
     * @param estimatedHrs     the revised estimate for this cycle's work. Null
     *                         leaves the ticket's estimate alone
     */
    record ReopenRequest(
            @NotBlank @Size(min = 3, max = 1000) String reason,
            @Size(max = 20) String restartStageCode,
            Long assigneeId,
            Instant plannedCloseDate,
            @Positive BigDecimal estimatedHrs) {
    }

    /** {@code TicketResponse} — the envelope is {@code { data }}, per §1652. */
    record TicketResponse(TicketWire.Ticket data) {
    }
}
