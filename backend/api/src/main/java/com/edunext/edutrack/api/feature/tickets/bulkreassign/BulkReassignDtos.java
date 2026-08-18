package com.edunext.edutrack.api.feature.tickets.bulkreassign;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * C-063 · the wire shapes for {@code POST /tickets/bulk-reassign}, per
 * {@code contracts/openapi.yaml}.
 *
 * <p>One endpoint, two callers — S-17's grid selection and S-24's wizard — and
 * this file is deliberately blind to which one sent a request. Nothing here
 * carries a {@code fromUserId}: the contract never asks for one, because the
 * two callers differ only in how {@code ticketIds} was assembled, not in what
 * the server does with it.
 */
final class BulkReassignDtos {

    private BulkReassignDtos() {
    }

    /**
     * @param ticketIds the {@code CRM-26-00347} codes to move, 1..500 of them.
     *                   Duplicates collapse before anything runs, the same rule
     *                   {@code ResourceService.setStatus} applies to its own
     *                   selection.
     * @param toUserId   who receives every ticket named above
     * @param reason     mandatory here, unlike the single-ticket priority route
     *                   — a selection can span assigned and unassigned tickets,
     *                   and this is the only surviving record of why a batch
     *                   moved
     */
    record BulkReassignRequest(
            @NotEmpty @Size(max = 500) List<@NotBlank String> ticketIds,
            @NotNull Long toUserId,
            @NotBlank @Size(min = 3, max = 500) String reason) {
    }

    /** The contract's {@code BulkResultResponse} — {@code { data: BulkResultData } }. */
    record BulkResultResponse(BulkResultData data) {
    }

    record BulkResultData(int succeeded, int failed, List<TicketOutcome> results) {
    }

    /**
     * One ticket's fate. {@code reason} is null on success — the field exists
     * for the refusal, not to say "ok" twice.
     */
    record TicketOutcome(String ticketId, boolean ok, String reason) {

        static TicketOutcome ok(String ticketId) {
            return new TicketOutcome(ticketId, true, null);
        }

        static TicketOutcome refused(String ticketId, String reason) {
            return new TicketOutcome(ticketId, false, reason);
        }
    }
}
