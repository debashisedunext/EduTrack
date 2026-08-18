package com.edunext.edutrack.api.feature.tickets.cycles;

import com.edunext.edutrack.api.feature.tickets.TicketWire;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * C-040 · the shapes {@code POST /tickets/{ticketId}/close} takes and answers,
 * mirroring {@code CloseRequest} and {@code TicketResponse} in the contract.
 */
final class CloseDtos {

    private CloseDtos() {
    }

    /**
     * S-23's five fields. Only {@code resolutionSummary} carries the
     * wireframe's asterisk; the other four are optional and each falls back to
     * something {@link CloseService} derives — see there for what and why.
     *
     * @param resolutionSummary        how this cycle was closed. Mandatory in
     *                                 the contract and in the dialog, stored on
     *                                 the cycle being sealed — {@code ticket_cycles
     *                                 .resolution_summary}, "how THIS cycle was
     *                                 closed" per its own column comment — and on
     *                                 the {@code CLOSED} history row's remarks
     * @param rootCauseCategory        a free label, no master behind it yet
     * @param actualCloseDate          null defaults to now, per S-23's own
     *                                 parenthetical
     * @param finalEffortHours         a confirmation of hours already logged,
     *                                 not a new figure — see {@link CloseService}
     * @param requestClientVerification null/false is silent; true is recorded
     *                                 on the cycle and on a history row Stream D
     *                                 has no consumer for yet
     */
    record CloseRequest(
            @NotBlank @Size(min = 3, max = 4000) String resolutionSummary,
            @Size(max = 100) String rootCauseCategory,
            Instant actualCloseDate,
            @Positive BigDecimal finalEffortHours,
            Boolean requestClientVerification) {
    }

    /** {@code TicketResponse} — the envelope is {@code { data }}, per §1652. */
    record TicketResponse(TicketWire.Ticket data) {
    }
}
