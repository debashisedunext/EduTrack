package com.edunext.edutrack.api.feature.clients;

import com.edunext.edutrack.api.feature.tickets.TicketWire;
import com.edunext.edutrack.common.pagination.PageMeta;

import java.math.BigDecimal;
import java.util.List;

/**
 * B-066 · S-32's Client 360 view, per {@code contracts/openapi.yaml}'s
 * {@code Client360Response} — declared since D-001 with no server behind it,
 * the same "declared, mocked, never mounted" gap B-025 and B-027 already
 * closed on this resource.
 */
final class Client360Dtos {

    private Client360Dtos() {
    }

    /**
     * {@code tickets} reuses {@link TicketWire.Ticket} rather than
     * {@code TicketListDtos.TicketSummary}: the contract's schema for this
     * field is {@code Ticket}, not {@code TicketSummary}, and {@code TicketWire}
     * is the one place C-038 put the entity-to-{@code Ticket} mapping so that a
     * ninth caller does not become a second copy of a 24-field record.
     *
     * @param openCount           status {@code <> CLOSED}, across every ticket
     *                            the caller may see against this client —
     *                            independent of {@code status}, the query
     *                            filter on the list beside it
     * @param slaCompliancePct    null when nothing closed carried a planned
     *                            close date, never 0% — {@code ResourceScorecardRunner}'s
     *                            distinction between "nothing was committed"
     *                            and "nothing was met"
     * @param avgResolutionHrs    null when nothing has closed yet
     */
    record Client360Data(
            ClientDtos.Client client,
            List<TicketWire.Ticket> tickets,
            long openCount,
            long closedCount,
            BigDecimal slaCompliancePct,
            BigDecimal avgResolutionHrs) {
    }

    record Client360Response(Client360Data data, PageMeta meta) {
    }
}
