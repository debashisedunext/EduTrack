package com.edunext.edutrack.domain.tickets;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;

/**
 * A relationship between two tickets.
 *
 * <p>{@code linkType} is <b>directional</b> and reads source → target:
 * BLOCKS | BLOCKED_BY | DUPLICATES | DUPLICATE_OF | RELATES_TO | CAUSED_BY.
 * The inverse is a separate row, not an inferred view — which is why the
 * vocabulary contains both halves of each pair.
 */
@Entity
@Table(name = "ticket_links")
public class TicketLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_ticket_id", nullable = false)
    private Long sourceTicketId;

    @Column(name = "target_ticket_id", nullable = false)
    private Long targetTicketId;

    @Column(name = "link_type", nullable = false, length = 20)
    private String linkType;

    @Column(name = "created_by")
    private Long createdBy;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSourceTicketId() {
        return sourceTicketId;
    }

    public void setSourceTicketId(Long sourceTicketId) {
        this.sourceTicketId = sourceTicketId;
    }

    public Long getTargetTicketId() {
        return targetTicketId;
    }

    public void setTargetTicketId(Long targetTicketId) {
        this.targetTicketId = targetTicketId;
    }

    public String getLinkType() {
        return linkType;
    }

    public void setLinkType(String linkType) {
        this.linkType = linkType;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
