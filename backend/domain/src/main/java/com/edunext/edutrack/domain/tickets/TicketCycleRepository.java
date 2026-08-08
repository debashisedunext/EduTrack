package com.edunext.edutrack.domain.tickets;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TicketCycleRepository extends JpaRepository<TicketCycle, Long> {

    List<TicketCycle> findByTicketIdOrderByCycleNoAsc(Long ticketId);

    Optional<TicketCycle> findByTicketIdAndCycleNo(Long ticketId, short cycleNo);

    /** The open cycle. Exactly one per ticket until it closes. */
    Optional<TicketCycle> findByTicketIdAndIsSealedFalse(Long ticketId);
}
