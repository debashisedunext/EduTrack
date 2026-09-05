package com.edunext.edutrack.domain.onboarding;

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
 * C-107 · {@code ob_journey_steps} narrative record — {@code ob_step_history}
 * (A-106, {@code V20260903_1745}). <b>APPEND ONLY · HASH CHAINED, PER
 * JOURNEY.</b>
 *
 * <p>The first consumer of this table: A-106's migration created it, and
 * every column below, but nothing has read or written it until this task's
 * {@code skip} transition. {@code ticket_history} (A-004/A-008) is the
 * platform's own precedent this entity is "column for column" against, per
 * the migration header — {@link Immutable} for the identical reason: without
 * it, a service that loads a row and touches a field hands Hibernate a dirty
 * instance, and the flush emits an {@code UPDATE} the {@code
 * trg_ob_history_no_update} trigger would reject at runtime rather than the
 * method simply not existing.
 *
 * <p><b>The chain is per journey, not per step.</b> The migration header
 * explains why: a step completion can activate several sibling steps in one
 * transaction, and per-step chains would mean taking N locks in an order
 * nothing guarantees. Every append here locks the parent {@code ob_journeys}
 * row first — see {@code ObJourneyStepLifecycleService#skip}.
 */
@Entity
@Immutable
@Table(name = "ob_step_history")
public class ObStepHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "journey_id", nullable = false)
    private Long journeyId;

    /** {@code null} = a journey-level event, not tied to one step. */
    @Column(name = "step_id")
    private Long stepId;

    @Column(name = "ob_client_id", nullable = false)
    private Long obClientId;

    /** STEP_ACTIVATED | STATUS_CHANGED | BLOCKED | UNBLOCKED | ... | SKIPPED | ... */
    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Column(name = "field_name", length = 60)
    private String fieldName;

    @Column(name = "old_value", columnDefinition = "text")
    private String oldValue;

    @Column(name = "new_value", columnDefinition = "text")
    private String newValue;

    /** {@code null} = SYSTEM — an escalation or a scanner, not a person. */
    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "actor_type", nullable = false, length = 10)
    private String actorType = "USER";

    /** Set only when {@code actorType} is {@code CLIENT}. */
    @Column(name = "actor_contact_id")
    private Long actorContactId;

    @Column(name = "remarks", columnDefinition = "text")
    private String remarks;

    @Column(name = "is_correction", nullable = false)
    private boolean isCorrection;

    /** The entry this one corrects. Never the other way round. */
    @Column(name = "corrects_entry_id")
    private Long correctsEntryId;

    /**
     * Hex SHA-256, {@code CHAR(64)} with {@code ascii_bin}, on {@code
     * TicketHistory}'s own precedent — see that entity's identical field for
     * why {@link SqlTypes#CHAR} is required for {@code ddl-auto=validate}.
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

    public Long getJourneyId() {
        return journeyId;
    }

    public void setJourneyId(Long journeyId) {
        this.journeyId = journeyId;
    }

    public Long getStepId() {
        return stepId;
    }

    public void setStepId(Long stepId) {
        this.stepId = stepId;
    }

    public Long getObClientId() {
        return obClientId;
    }

    public void setObClientId(Long obClientId) {
        this.obClientId = obClientId;
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

    public Long getActorContactId() {
        return actorContactId;
    }

    public void setActorContactId(Long actorContactId) {
        this.actorContactId = actorContactId;
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
