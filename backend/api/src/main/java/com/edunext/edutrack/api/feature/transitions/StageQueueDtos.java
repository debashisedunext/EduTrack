package com.edunext.edutrack.api.feature.transitions;

import com.edunext.edutrack.api.feature.tickets.TicketWire;
import com.edunext.edutrack.common.pagination.PageMeta;

import java.time.Instant;
import java.util.List;

/**
 * C-062 · the shapes {@code GET /stages/queue} answers with — the contract's
 * {@code StageQueueResponse}, property for property.
 */
final class StageQueueDtos {

    private StageQueueDtos() {
    }

    record ListResponse(List<QueueRow> data, PageMeta meta) {
    }

    /**
     * One waiting ticket. {@code ticket} is the full contract {@code Ticket}
     * shape — {@link TicketWire.Ticket} — because the contract embeds it
     * whole rather than a summary; S-31 has no separate row DTO to keep in
     * step with the detail page's the way {@code TicketListDtos.TicketSummary}
     * does for the grid.
     */
    record QueueRow(TicketWire.Ticket ticket, Instant enteredStageAt, int timeInStageMins, boolean stageSlaBreached) {
    }
}
