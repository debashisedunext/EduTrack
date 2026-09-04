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
 *
 * <p>{@link #mandatory} (C-102, {@code V20260903_2000}) defaults to
 * {@code true} — every item predates this column and was already being
 * treated as mandatory by the instance-side completion gate, so {@code true}
 * is the value that preserves existing behaviour rather than a neutral
 * placeholder. The instance-side gate actually reading this flag is C-106's
 * job; this is only where the OB-07 designer authors it.
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

    @Column(name = "is_mandatory", nullable = false)
    private boolean mandatory = true;

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

    public boolean isMandatory() {
        return mandatory;
    }

    public void setMandatory(boolean mandatory) {
        this.mandatory = mandatory;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
