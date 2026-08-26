package com.edunext.edutrack.api.feature.transitions;

import com.edunext.edutrack.api.feature.tickets.TicketWire;
import com.edunext.edutrack.common.pagination.PageMeta;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * C-062 · the shapes {@code GET /stages/queue} answers with — the contract's
 * {@code StageQueueResponse}, property for property.
 */
final class StageQueueDtos {

    private StageQueueDtos() {
    }

    /**
     * Named explicitly, matching the contract's own {@code StageQueueResponse}.
     * springdoc keys {@code components.schemas} by <b>simple class name</b>, and
     * a bare {@code ListResponse} here silently overwrites
     * {@code TicketListDtos.ListResponse} in the served document — exactly the
     * failure {@code TicketListDtos.Project}'s own javadoc names, and the one
     * {@code ContractConformanceTest} caught here: {@code GET /stages/queue}
     * documented {@code TicketSummary}'s fields instead of this record's.
     */
    @Schema(name = "StageQueueResponse")
    record ListResponse(List<QueueRow> data, PageMeta meta) {
    }

    /**
     * One waiting ticket. {@code ticket} is the full contract {@code Ticket}
     * shape — {@link TicketWire.Ticket} — because the contract embeds it
     * whole rather than a summary; S-31 has no separate row DTO to keep in
     * step with the detail page's the way {@code TicketListDtos.TicketSummary}
     * does for the grid.
     *
     * <p>Named explicitly for the same collision reason {@link ListResponse}
     * states — a bare {@code QueueRow} would be low-risk today, but the whole
     * point of naming one of the pair is not to have to reason about which one
     * needed it.
     */
    @Schema(name = "StageQueueRow")
    record QueueRow(TicketWire.Ticket ticket, Instant enteredStageAt, int timeInStageMins, boolean stageSlaBreached) {
    }
}
