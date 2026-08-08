package com.edunext.edutrack.domain.tickets;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;

/**
 * A subscriber to a ticket's notifications. Stream D reads this to build the
 * recipient list for a mail or a bell.
 *
 * <p><b>Watching is not assignment, and it grants no row scope.</b> A PM may
 * watch a ticket they do not own; visibility still comes from A-034's
 * {@code ScopeResolver}. Treating a watch as access would be a quiet way to
 * hand out exactly the row visibility the scope guard exists to control.
 */
@Entity
@Table(name = "ticket_watchers")
public class TicketWatcher {

    @EmbeddedId
    private TicketWatcherId id;

    @Column(name = "added_by")
    private Long addedBy;

    @Generated(event = EventType.INSERT)
    @Column(name = "added_at", insertable = false, updatable = false)
    private Instant addedAt;

    public TicketWatcherId getId() {
        return id;
    }

    public void setId(TicketWatcherId id) {
        this.id = id;
    }

    public Long getAddedBy() {
        return addedBy;
    }

    public void setAddedBy(Long addedBy) {
        this.addedBy = addedBy;
    }

    public Instant getAddedAt() {
        return addedAt;
    }
}
