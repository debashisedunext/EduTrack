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
 * C-103 · a Service on a running journey — {@code ob_journey_steps} (A-104,
 * {@code V20260903_1600}).
 *
 * <p><b>Snapshotted, not read live.</b> {@link #name}, {@link #tatDays},
 * {@link #requiresSignoff} and the rest are copied from
 * {@link ObJourneyTemplateStep} at instantiation; {@link #templateStepId} is
 * provenance only, exactly as the migration header describes. An admin
 * editing the template later must never change what this row says.
 *
 * <p><b>Owner resolution.</b> A template step carries one owner — a pinned
 * {@code ownerUserId}, the <em>implementor</em> — and usually carries none,
 * because a Module Service is authored once and boarded for every client that
 * buys the product. A step the template pins nobody to takes the
 * <b>project's</b> implementor ({@code ob_projects.implementor_user_id}, then
 * its creator), which is where "who, for this client" is actually known. See
 * {@code ObJourneyInstantiationService#defaultImplementorOf}.
 *
 * <p>{@link #ownerUserId} {@code null} therefore means what it says —
 * genuinely nobody to hand it to, a project with neither an implementor nor a
 * recorded creator — and is findable on the Manager's unassigned list via
 * {@code ix_ob_journey_steps_owner (owner_user_id, status)}; see
 * {@code ObJourneyStepRepository#findByOwnerUserIdIsNull}. Until
 * {@code V20260914_1830} it meant something much broader: a template step
 * could name an {@code owner_role} instead of a person, nothing ever resolved
 * one — no per-client role→user resolver exists, that being OB-08's
 * "Responsibility" admin, not built — so most of a journey landed on the
 * unassigned list at birth. The role column is gone and the project answers
 * instead.
 *
 * <p>Every step is born {@link ObJourneyStepStatus#PENDING}, {@code due_at}
 * {@code null} — nothing instantiated here has started, and the clock is
 * stamped by whatever starts it. Even a journey instantiated already
 * {@link ObGateStatus#OPEN}
 * (a product bought after the client's gate cleared, plan §5.3 item 3)
 * still gets PENDING steps here: activating the first wave of
 * dependency-free steps is C-119's job, evaluated the same way a step
 * completion re-evaluates the journey, not duplicated in this constructor.
 */
@Entity
@Table(name = "ob_journey_steps")
public class ObJourneyStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "journey_id", nullable = false)
    private Long journeyId;

    /** Provenance only. What the step says today is in this row. */
    @Column(name = "template_step_id")
    private Long templateStepId;

    @Column(name = "sequence", nullable = false)
    private int sequence;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "tat_days", nullable = false)
    private int tatDays;

    /** {@code null} = unresolved. See the class javadoc. */
    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Column(name = "backup_owner_user_id")
    private Long backupOwnerUserId;

    @Column(name = "requires_signoff", nullable = false)
    private boolean requiresSignoff;

    /** {@code null} = parallel (plan §5.6), not "first". */
    @Column(name = "depends_on_step_id")
    private Long dependsOnStepId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ObJourneyStepStatus status = ObJourneyStepStatus.PENDING;

    @Column(name = "blocked_reason_code", length = 40)
    private String blockedReasonCode;

    @Column(name = "blocked_note", length = 500)
    private String blockedNote;

    @Column(name = "skip_reason", length = 500)
    private String skipReason;

    @Column(name = "skipped_by")
    private Long skippedBy;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /** Working-calendar aware, computed by C-105 when the step activates. */
    @Column(name = "due_at")
    private Instant dueAt;

    /**
     * C-113 · when the TAT scanner flagged {@link #dueAt} as passed.
     * {@code null} = not yet flagged, whatever {@link #dueAt} says — a step
     * still {@code PENDING} or {@code WAITING_ON_CLIENT} has a dead or
     * frozen clock and is never a candidate (see {@code ObTatRepository}).
     * A plain fact, not a colour: {@code null → timestamp} once, guarded by
     * the same column in its own {@code WHERE}, on {@code tickets.escalate}'s
     * precedent — see the migration header.
     */
    @Column(name = "tat_breached_at")
    private Instant tatBreachedAt;

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

    public Long getJourneyId() {
        return journeyId;
    }

    public void setJourneyId(Long journeyId) {
        this.journeyId = journeyId;
    }

    public Long getTemplateStepId() {
        return templateStepId;
    }

    public void setTemplateStepId(Long templateStepId) {
        this.templateStepId = templateStepId;
    }

    public int getSequence() {
        return sequence;
    }

    public void setSequence(int sequence) {
        this.sequence = sequence;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public int getTatDays() {
        return tatDays;
    }

    public void setTatDays(int tatDays) {
        this.tatDays = tatDays;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(Long ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public Long getBackupOwnerUserId() {
        return backupOwnerUserId;
    }

    public void setBackupOwnerUserId(Long backupOwnerUserId) {
        this.backupOwnerUserId = backupOwnerUserId;
    }

    public boolean isRequiresSignoff() {
        return requiresSignoff;
    }

    public void setRequiresSignoff(boolean requiresSignoff) {
        this.requiresSignoff = requiresSignoff;
    }

    public Long getDependsOnStepId() {
        return dependsOnStepId;
    }

    public void setDependsOnStepId(Long dependsOnStepId) {
        this.dependsOnStepId = dependsOnStepId;
    }

    public ObJourneyStepStatus getStatus() {
        return status;
    }

    public void setStatus(ObJourneyStepStatus status) {
        this.status = status;
    }

    public String getBlockedReasonCode() {
        return blockedReasonCode;
    }

    public void setBlockedReasonCode(String blockedReasonCode) {
        this.blockedReasonCode = blockedReasonCode;
    }

    public String getBlockedNote() {
        return blockedNote;
    }

    public void setBlockedNote(String blockedNote) {
        this.blockedNote = blockedNote;
    }

    public String getSkipReason() {
        return skipReason;
    }

    public void setSkipReason(String skipReason) {
        this.skipReason = skipReason;
    }

    public Long getSkippedBy() {
        return skippedBy;
    }

    public void setSkippedBy(Long skippedBy) {
        this.skippedBy = skippedBy;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public Instant getDueAt() {
        return dueAt;
    }

    public void setDueAt(Instant dueAt) {
        this.dueAt = dueAt;
    }

    public Instant getTatBreachedAt() {
        return tatBreachedAt;
    }

    public void setTatBreachedAt(Instant tatBreachedAt) {
        this.tatBreachedAt = tatBreachedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
