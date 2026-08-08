package com.edunext.edutrack.domain.tickets;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TicketLinkRepository extends JpaRepository<TicketLink, Long> {

    List<TicketLink> findBySourceTicketId(Long sourceTicketId);

    /**
     * Links are directional and stored one way, so the "Linked tickets" panel
     * has to read both ends — a ticket that BLOCKS another has no row of its
     * own on the blocked side.
     */
    List<TicketLink> findByTargetTicketId(Long targetTicketId);
}
