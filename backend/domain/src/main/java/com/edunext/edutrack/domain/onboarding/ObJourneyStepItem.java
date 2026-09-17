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

    /**
     * The manager's verdict — {@link ObStepReviewState}, never null.
     *
     * <p>{@code NOT_REVIEWED} rather than a null for "no verdict yet", unlike
     * {@link #answer}, which genuinely is nullable. The difference is that an
     * answer has three states one of which is absence, while a verdict has
     * three states all of which are positions the manager can be in — and a
     * NOT NULL column with a default is what lets the index on
     * {@code (step_id, review_state)} count them without a special case.
     */
    @Column(name = "review_state", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private ObStepReviewState reviewState = ObStepReviewState.NOT_REVIEWED;

    /**
     * Whose desk this row is on — {@link ObStepRowState}, never null.
     *
     * <p>The companion to {@link #reviewState}, and the reason there are two
     * columns rather than one: this says where the row <em>is</em>, that says
     * what the reviewer <em>decided</em>. While a row is {@code SENT} the
     * verdict is a draft the reviewer may still cycle; releasing the row is
     * what makes it final and moves this.
     */
    @Column(name = "row_state", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private ObStepRowState rowState = ObStepRowState.DRAFT;

    /** When this row went out for review — per row, not per task. */
    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "submitted_by")
    private Long submittedBy;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    /**
     * When the implementor last opened this row's outcome.
     *
     * <p>What makes "2 rows came back" a signal rather than a permanent
     * decoration: an outcome is new until its row has been looked at. Null
     * with a decided {@link #rowState} is an outcome nobody has seen yet, and
     * that is what both the banner and the My Tasks highlight count.
     */
    @Column(name = "outcome_seen_at")
    private Instant outcomeSeenAt;

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

    public ObStepReviewState getReviewState() {
        return reviewState;
    }

    public void setReviewState(ObStepReviewState reviewState) {
        this.reviewState = reviewState;
    }

    public Long getReviewedBy() {
        return reviewedBy;
    }

    public void setReviewedBy(Long reviewedBy) {
        this.reviewedBy = reviewedBy;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(Instant reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public ObStepRowState getRowState() {
        return rowState;
    }

    public void setRowState(ObStepRowState rowState) {
        this.rowState = rowState;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(Instant submittedAt) {
        this.submittedAt = submittedAt;
    }

    public Long getSubmittedBy() {
        return submittedBy;
    }

    public void setSubmittedBy(Long submittedBy) {
        this.submittedBy = submittedBy;
    }

    public Instant getOutcomeSeenAt() {
        return outcomeSeenAt;
    }

    public void setOutcomeSeenAt(Instant outcomeSeenAt) {
        this.outcomeSeenAt = outcomeSeenAt;
    }

    /** Shut to everybody — see {@link ObStepReviewState#VERIFIED}. */
    public boolean isVerifiedAndLocked() {
        return reviewState == ObStepReviewState.VERIFIED;
    }

    /**
     * May its implementor write to this row right now?
     *
     * <p>The per-row replacement for "is the task under review". A row out
     * with the manager is frozen and an approved one is shut for good; a row
     * that is still theirs, or one that has come back, is open — however busy
     * its neighbours are. This is what lets somebody carry on with rows three
     * to five while rows one and two are being read.
     */
    public boolean isOpenToImplementor() {
        return rowState.isWithImplementor();
    }

    /** An outcome has come back and nobody has opened it yet. */
    public boolean isUnseenOutcome() {
        return rowState.isOutcome() && outcomeSeenAt == null;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
