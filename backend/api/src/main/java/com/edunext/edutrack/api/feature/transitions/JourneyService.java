package com.edunext.edutrack.api.feature.transitions;

import com.edunext.edutrack.api.security.scope.ScopedTickets;
import com.edunext.edutrack.domain.tickets.Ticket;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * C-058 · the Journey roll-up, blueprint §4A.4.
 *
 * <p><b>Scoping is {@link ScopedTickets}' job and is done first.</b> An
 * out-of-scope ticket comes back as 404 from {@code requireByCode} before any
 * roll-up runs, which is the same answer a ticket that does not exist gets —
 * a 403 here would confirm the ticket is real, and §10.2's whole point is that
 * it must not.
 *
 * <p><b>One cycle, defaulting to the current one.</b> §4A.4's grid is a single
 * cycle's journey because the ribbon above it is per cycle (C-053) and
 * {@code iterationNo} restarts at 1 on a reopen — interleaving two cycles would
 * make the iteration column ambiguous. An explicit {@code ?cycle=} may name an
 * earlier, sealed cycle; it is read-only either way, since nothing here writes.
 */
@Service
class JourneyService {

    private final ScopedTickets tickets;
    private final JourneyRepository journey;

    JourneyService(ScopedTickets tickets, JourneyRepository journey) {
        this.tickets = tickets;
        this.journey = journey;
    }

    @Transactional(readOnly = true)
    JourneyDtos.JourneyResponse get(Authentication caller, String ticketCode, Integer requestedCycle) {
        Ticket ticket = tickets.requireByCode(caller, ticketCode);
        long ticketId = ticket.getId();

        // A cycle beyond what the ticket has is not an error: the roll-up for a
        // cycle that never happened is legitimately empty, and 404 here would
        // be indistinguishable from "no such ticket", which it is not.
        int cycleNo = requestedCycle == null ? ticket.getCurrentCycleNo() : requestedCycle;

        return new JourneyDtos.JourneyResponse(new JourneyDtos.JourneyData(
                journey.hops(ticketId, cycleNo),
                journey.perResource(ticketId, cycleNo),
                journey.cycleTotalHrs(ticketId, cycleNo),
                journey.allCyclesTotalHrs(ticketId)));
    }
}
