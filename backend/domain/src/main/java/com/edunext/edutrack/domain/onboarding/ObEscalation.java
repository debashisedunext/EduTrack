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
import org.hibernate.generator.EventType;

import java.time.Instant;

/**
 * C-115 · {@code ob_escalations} — one rung of the internal escalation
 * ladder (plan §5.11, migration {@code V20260903_2030}). Raised by C's
 * scanner when a step's TAT breaches: L1 at breach, L2 at +4 working hours,
 * L3 at +8. Never visible to a client — see the migration header for why
 * this is not the same thing as {@code ob_client_escalations}.
 *
 * <p><b>Plain and mutable, unlike {@code ob_step_history}.</b> A rung's life
 * is exactly two optional state changes — acknowledged, then resolved — and
 * both are ordinary {@code UPDATE}s guarded by their own {@code CHECK}
 * constraints, not append-only writes. There is nothing here for a journal
 * to protect.
 */
@Entity
@Table(name = "ob_escalations")
public class ObEscalation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ob_client_id", nullable = false)
    private Long obClientId;

    @Column(name = "journey_id", nullable = false)
    private Long journeyId;

    @Column(name = "step_id", nullable = false)
    private Long stepId;

    @Enumerated(EnumType.STRING)
    @Column(name = "level", nullable = false, length = 4)
    private ObEscalationLevel level;

    /** {@code TAT_BREACH} today — see {@link #reason}'s own contract note on why this stays a string. */
    @Column(name = "reason", nullable = false, length = 30)
    private String reason;

    /** {@code null} when the matrix resolved nobody — a configuration gap, not hidden. */
    @Column(name = "escalated_to")
    private Long escalatedTo;

    @Column(name = "escalated_at", nullable = false)
    private Instant escalatedAt;

    @Column(name = "acknowledged_by")
    private Long acknowledgedBy;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "resolved_by")
    private Long resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolution_note", length = 2000)
    private String resolutionNote;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getObClientId() {
        return obClientId;
    }

    public void setObClientId(Long obClientId) {
        this.obClientId = obClientId;
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

    public ObEscalationLevel getLevel() {
        return level;
    }

    public void setLevel(ObEscalationLevel level) {
        this.level = level;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Long getEscalatedTo() {
        return escalatedTo;
    }

    public void setEscalatedTo(Long escalatedTo) {
        this.escalatedTo = escalatedTo;
    }

    public Instant getEscalatedAt() {
        return escalatedAt;
    }

    public void setEscalatedAt(Instant escalatedAt) {
        this.escalatedAt = escalatedAt;
    }

    public Long getAcknowledgedBy() {
        return acknowledgedBy;
    }

    public void setAcknowledgedBy(Long acknowledgedBy) {
        this.acknowledgedBy = acknowledgedBy;
    }

    public Instant getAcknowledgedAt() {
        return acknowledgedAt;
    }

    public void setAcknowledgedAt(Instant acknowledgedAt) {
        this.acknowledgedAt = acknowledgedAt;
    }

    public Long getResolvedBy() {
        return resolvedBy;
    }

    public void setResolvedBy(Long resolvedBy) {
        this.resolvedBy = resolvedBy;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public String getResolutionNote() {
        return resolutionNote;
    }

    public void setResolutionNote(String resolutionNote) {
        this.resolutionNote = resolutionNote;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
