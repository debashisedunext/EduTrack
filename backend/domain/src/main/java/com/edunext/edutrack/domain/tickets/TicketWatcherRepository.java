package com.edunext.edutrack.domain.tickets;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Watchers are genuinely removable — unsubscribing is the point — so unlike the
 * append-only tables this one keeps the full {@code JpaRepository} surface.
 */
public interface TicketWatcherRepository extends JpaRepository<TicketWatcher, TicketWatcherId> {

    /** The recipient list Stream D fans a notification out to. */
    List<TicketWatcher> findByIdTicketId(Long ticketId);

    List<TicketWatcher> findByIdUserId(Long userId);
}
