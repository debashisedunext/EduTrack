package com.edunext.edutrack.domain.tickets;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

/** Composite key for {@link TicketWatcher} — the pair is the identity. */
@Embeddable
public class TicketWatcherId implements Serializable {

    @Column(name = "ticket_id", nullable = false)
    private Long ticketId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    protected TicketWatcherId() {
    }

    public TicketWatcherId(Long ticketId, Long userId) {
        this.ticketId = ticketId;
        this.userId = userId;
    }

    public Long getTicketId() {
        return ticketId;
    }

    public Long getUserId() {
        return userId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TicketWatcherId other)) {
            return false;
        }
        return Objects.equals(ticketId, other.ticketId) && Objects.equals(userId, other.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ticketId, userId);
    }
}
