package com.edunext.edutrack.domain.onboarding;

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
 * The per-step required-document checklist — architect's addition 7 (plan
 * §1.1): "a step can't complete with required documents missing." Not every
 * row gates completion; only {@link #isRequired} ones do, per the migration.
 */
@Entity
@Table(name = "ob_journey_template_step_docs")
public class ObJourneyTemplateStepDoc {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "step_id", nullable = false)
    private Long stepId;

    @Column(name = "sequence", nullable = false)
    private int sequence;

    @Column(name = "label", nullable = false, length = 300)
    private String label;

    @Column(name = "is_required", nullable = false)
    private boolean isRequired;

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

    public Long getStepId() {
        return stepId;
    }

    public void setStepId(Long stepId) {
        this.stepId = stepId;
    }

    public int getSequence() {
        return sequence;
    }

    public void setSequence(int sequence) {
        this.sequence = sequence;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public boolean isRequired() {
        return isRequired;
    }

    public void setRequired(boolean required) {
        isRequired = required;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
