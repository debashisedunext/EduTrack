package com.edunext.edutrack.domain.tickets;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Every field change, assignment, status move and system action, forever.
 * <b>APPEND ONLY · HASH CHAINED.</b>
 *
 * <p>There are no setters for "correcting" a row because there is no
 * correcting a row. A mistake is reversed by a <b>new</b> row carrying
 * {@code isCorrection} and pointing at the wrong one through
 * {@code correctsEntryId} — the compensating-entry pattern, exactly as an
 * accounting reversal. The original stays exactly as written.
 *
 * <p>{@link Immutable} is the ORM half of that guarantee: without it, a
 * service that loads an entry and touches a field hands Hibernate a dirty
 * instance, and the flush emits an {@code UPDATE} nobody wrote. The A-008
 * trigger would reject it — but at runtime, in production, rather than at the
 * point where the method could simply not exist. See
 * {@link com.edunext.edutrack.domain.appendonly.AppendOnly} for the other
 * layers.
 *
 * <p>The chain is <b>per-ticket</b>, not global (PLAN.md §3.7). Every append
 * takes {@code SELECT … FOR UPDATE} on the parent ticket row first, so two
 * concurrent writes to one ticket cannot both read the same tail and fork it —
 * a fork would surface months later as the nightly verifier reporting
 * tampering that was really our own race.
 */
@Entity
@Immutable
@Table(name = "ticket_history")
public class TicketHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ticket_id", nullable = false)
    private Long ticketId;

    /** Nullable: a few events predate the ticket's first cycle. */
    @Column(name = "cycle_no")
    private Short cycleNo;

    /** CREATED | ASSIGNED | STATUS_CHANGED | … */
    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Column(name = "field_name", length = 60)
    private String fieldName;

    @Column(name = "old_value", columnDefinition = "text")
    private String oldValue;

    @Column(name = "new_value", columnDefinition = "text")
    private String newValue;

    /** NULL means SYSTEM — an escalation or a scanner, not a person. */
    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "actor_type", nullable = false, length = 10)
    private String actorType = "USER";

    @Column(name = "remarks", columnDefinition = "text")
    private String remarks;

    @Column(name = "is_correction", nullable = false)
    private boolean isCorrection;

    /** The entry this one reverses. Never the other way round. */
    @Column(name = "corrects_entry_id")
    private Long correctsEntryId;

    /**
     * Hex SHA-256, {@code CHAR(64)} with {@code ascii_bin} — always exactly 64
     * characters, compared exactly, with no utf8mb4 overhead (PLAN.md §3.1).
     * The {@link SqlTypes#CHAR} code is what makes {@code ddl-auto=validate}
     * agree; without it Hibernate expects {@code VARCHAR} and refuses to start.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "prev_hash", length = 64)
    private String prevHash;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "row_hash", length = 64)
    private String rowHash;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTicketId() {
        return ticketId;
    }

    public void setTicketId(Long ticketId) {
        this.ticketId = ticketId;
    }

    public Short getCycleNo() {
        return cycleNo;
    }

    public void setCycleNo(Short cycleNo) {
        this.cycleNo = cycleNo;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getFieldName() {
        return fieldName;
    }

    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    public String getOldValue() {
        return oldValue;
    }

    public void setOldValue(String oldValue) {
        this.oldValue = oldValue;
    }

    public String getNewValue() {
        return newValue;
    }

    public void setNewValue(String newValue) {
        this.newValue = newValue;
    }

    public Long getActorId() {
        return actorId;
    }

    public void setActorId(Long actorId) {
        this.actorId = actorId;
    }

    public String getActorType() {
        return actorType;
    }

    public void setActorType(String actorType) {
        this.actorType = actorType;
    }

    public String getRemarks() {
        return remarks;
    }

    public void setRemarks(String remarks) {
        this.remarks = remarks;
    }

    public boolean isCorrection() {
        return isCorrection;
    }

    public void setCorrection(boolean correction) {
        this.isCorrection = correction;
    }

    public Long getCorrectsEntryId() {
        return correctsEntryId;
    }

    public void setCorrectsEntryId(Long correctsEntryId) {
        this.correctsEntryId = correctsEntryId;
    }

    public String getPrevHash() {
        return prevHash;
    }

    public void setPrevHash(String prevHash) {
        this.prevHash = prevHash;
    }

    public String getRowHash() {
        return rowHash;
    }

    public void setRowHash(String rowHash) {
        this.rowHash = rowHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
