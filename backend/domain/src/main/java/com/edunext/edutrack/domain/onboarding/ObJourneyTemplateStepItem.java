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
 * The Task List (product vocabulary, formerly "sub-categories") — one
 * checklist entry a Service completes against. Plan §5.8: the instance this
 * is snapshotted onto answers True/False per item, and a False answer needs
 * a remark; that gate lives on {@code ob_journey_step_items} (A-104), not
 * here. This table is only the versioned definition.
 */
@Entity
@Table(name = "ob_journey_template_step_items")
public class ObJourneyTemplateStepItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "step_id", nullable = false)
    private Long stepId;

    @Column(name = "sequence", nullable = false)
    private int sequence;

    @Column(name = "label", nullable = false, length = 300)
    private String label;

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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
