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
 * C-103 · the Task List on a running step — {@code ob_journey_step_items}
 * (A-104, {@code V20260903_1600}). Snapshotted from
 * {@link ObJourneyTemplateStepItem} the same way {@link ObJourneyStep} is
 * snapshotted from its template step.
 *
 * <p><b>No {@code mandatory} column here, deliberately</b> — it never
 * existed on this table. {@link ObJourneyTemplateStepItem}'s own javadoc
 * says the instance-side completion gate (C-106) reads mandatory-ness by
 * joining back through {@link #templateItemId}, not from a copy on this
 * row. {@link #templateItemId} is nullable because an admin may add an
 * ad-hoc item to one client's step that no template ever carried — C-106's
 * problem to resolve for that case, not this one's.
 *
 * <p>{@link #answer}/{@link #remark}/{@link #answeredBy}/{@link #answeredAt}
 * all start {@code null}: instantiation only creates the row, answering it
 * is the step owner's job later.
 */
@Entity
@Table(name = "ob_journey_step_items")
public class ObJourneyStepItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "step_id", nullable = false)
    private Long stepId;

    @Column(name = "template_item_id")
    private Long templateItemId;

    @Column(name = "sequence", nullable = false)
    private int sequence;

    @Column(name = "label", nullable = false, length = 300)
    private String label;

    /** {@code null} = unanswered, {@code true} = True, {@code false} = False. */
    @Column(name = "answer")
    private Boolean answer;

    @Column(name = "remark", length = 500)
    private String remark;

    @Column(name = "answered_by")
    private Long answeredBy;

    @Column(name = "answered_at")
    private Instant answeredAt;

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

    public Long getTemplateItemId() {
        return templateItemId;
    }

    public void setTemplateItemId(Long templateItemId) {
        this.templateItemId = templateItemId;
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

    public Boolean getAnswer() {
        return answer;
    }

    public void setAnswer(Boolean answer) {
        this.answer = answer;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public Long getAnsweredBy() {
        return answeredBy;
    }

    public void setAnsweredBy(Long answeredBy) {
        this.answeredBy = answeredBy;
    }

    public Instant getAnsweredAt() {
        return answeredAt;
    }

    public void setAnsweredAt(Instant answeredAt) {
        this.answeredAt = answeredAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
