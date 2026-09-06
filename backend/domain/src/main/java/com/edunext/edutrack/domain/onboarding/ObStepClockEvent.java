package com.edunext.edutrack.domain.onboarding;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.Immutable;
import org.hibernate.generator.EventType;

import java.time.Instant;

/**
 * C-105 · one transition of a step's TAT clock — {@code
 * ob_step_clock_events} (A-105, {@code V20260903_1730}). <b>APPEND ONLY,
 * NOT HASH-CHAINED</b> — see the migration header for why this table
 * deliberately skips the chain {@code ObStepHistory} carries: there is no
 * per-parent lock to take because there is no chain tail to fork.
 *
 * <p>{@code @Immutable} on the same precedent as {@link ObStepHistory} —
 * without it, a service that loads a row and touches a field hands
 * Hibernate a dirty instance the flush would try to {@code UPDATE}, which
 * {@code trg_ob_clock_no_update} refuses at the database rather than the
 * method never having existed.
 *
 * <p><b>Written through raw SQL, not Hibernate.</b> {@code
 * ObStepClockRecorder} inserts with a hand-written statement rather than
 * {@code EntityManager#persist} — this class exists to read the table back
 * (the last {@code PAUSED} row {@code resume} needs, and whatever a future
 * roll-up reads), on {@code AuditLog}/{@code AuditTrail}'s own precedent for
 * an append-only table with nothing to chain: an entity for reads, a
 * dedicated writer for the one permitted write.
 */
@Entity
@Immutable
@Table(name = "ob_step_clock_events")
public class ObStepClockEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "step_id", nullable = false)
    private Long stepId;

    @Column(name = "journey_id", nullable = false)
    private Long journeyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 20)
    private ObStepClockEventType eventType;

    /**
     * Mandatory on {@link ObStepClockEventType#PAUSED}, forbidden otherwise —
     * {@code ck_ob_clock_pause_reason}/{@code ck_ob_clock_reason_only_on_pause}.
     * A plain string, not an enum: the column is deliberately wider than the
     * one reason written today ({@code WAITING_ON_CLIENT}) so a second
     * pausing reason does not need a migration to name — see the migration
     * header.
     */
    @Column(name = "pause_reason", length = 30)
    private String pauseReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "attributed_to", nullable = false, length = 10)
    private ObStepClockAttribution attributedTo = ObStepClockAttribution.INTERNAL;

    /** When the clock actually moved — distinct from {@link #createdAt}. See the migration header. */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /** {@code null} = SYSTEM. */
    @Column(name = "actor_id")
    private Long actorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 10)
    private ObStepClockActorType actorType = ObStepClockActorType.USER;

    @Column(name = "note", length = 500)
    private String note;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getStepId() {
        return stepId;
    }

    public void setStepId(Long stepId) {
        this.stepId = stepId;
    }

    public Long getJourneyId() {
        return journeyId;
    }

    public void setJourneyId(Long journeyId) {
        this.journeyId = journeyId;
    }

    public ObStepClockEventType getEventType() {
        return eventType;
    }

    public void setEventType(ObStepClockEventType eventType) {
        this.eventType = eventType;
    }

    public String getPauseReason() {
        return pauseReason;
    }

    public void setPauseReason(String pauseReason) {
        this.pauseReason = pauseReason;
    }

    public ObStepClockAttribution getAttributedTo() {
        return attributedTo;
    }

    public void setAttributedTo(ObStepClockAttribution attributedTo) {
        this.attributedTo = attributedTo;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    public Long getActorId() {
        return actorId;
    }

    public void setActorId(Long actorId) {
        this.actorId = actorId;
    }

    public ObStepClockActorType getActorType() {
        return actorType;
    }

    public void setActorType(ObStepClockActorType actorType) {
        this.actorType = actorType;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
