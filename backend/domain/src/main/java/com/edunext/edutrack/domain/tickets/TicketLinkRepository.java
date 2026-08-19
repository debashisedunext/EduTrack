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

    /**
     * C-064 · the pre-flight for a clean {@code 409} rather than an unlabelled
     * constraint violation from {@code uq_ticket_links}. Checked against the
     * already-canonical {@code (source, target, linkType)} triple —
     * {@code TicketLinkService} resolves {@code BLOCKED_BY} to its canonical
     * {@code BLOCKS} row before this is called, so the two are never asked to
     * agree independently.
     */
    boolean existsBySourceTicketIdAndTargetTicketIdAndLinkType(
            Long sourceTicketId, Long targetTicketId, String linkType);
}
