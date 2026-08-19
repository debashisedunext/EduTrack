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
 * A relationship between two tickets — C-064, blueprint §16 item 17.
 *
 * <p>{@code linkType} is <b>directional</b> and reads source → target. One
 * row per relationship, not two: {@code ticket_links} is ordinary mutable
 * data (unlike {@code ticket_history}), so removing a link is a single
 * {@code DELETE} rather than a pair that could go out of sync mid-write. The
 * <em>inverse</em> — how this same row reads from the target's side — is
 * computed at read time by {@code TicketLinkService}, not stored as a second
 * row.
 *
 * <p>The stored values are the four blueprint §7.5 names —
 * {@code BLOCKS|BLOCKED_BY|DUPLICATE_OF|RELATES_TO} — plus
 * {@code DUPLICATED_BY}, which only ever appears as a *computed* inverse
 * label and is refused as an input by {@code createTicketLink}. See
 * {@code TicketLinkType}'s javadoc, which carries the full argument; this
 * column has no {@code CHECK} constraint, so the vocabulary lives there and
 * not here.
 *
 * <p>This corrects an earlier version of this comment, written before the
 * feature existed, that described a two-row-per-relationship design and a
 * six-value vocabulary including {@code DUPLICATES} and {@code CAUSED_BY}.
 * Neither shipped.
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
