package com.edunext.edutrack.api.feature.tickets.assign;

import com.edunext.edutrack.api.feature.tickets.TicketWire;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * C-049 · the wire shapes for {@code POST /tickets/{ticketId}/assign}, per
 * {@code contracts/openapi.yaml}.
 */
final class AssignDtos {

    private AssignDtos() {
    }

    /**
     * @param assigneeId who receives the ticket — the only required field, so
     *                   the same route covers a first assignment and a
     *                   reassignment
     * @param note       optional context, carried on the {@code STAGE_REASSIGNED}
     *                   history row's {@code remarks} — unlike bulk reassignment,
     *                   the contract does not make this mandatory
     */
    record AssignRequest(
            @NotNull Long assigneeId,
            @Size(max = 2000) String note) {
    }

    /** The contract's {@code TicketResponse} — {@code { data: Ticket }}. */
    record TicketResponse(TicketWire.Ticket data) {
    }
}
