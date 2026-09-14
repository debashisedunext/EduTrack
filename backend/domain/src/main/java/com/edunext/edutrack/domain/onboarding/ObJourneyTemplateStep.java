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
 * A Service within a Module Service (product vocabulary) — one row of
 * {@code ob_journey_template_steps}. See the migration for the full
 * dependency-graph reasoning; the summary that matters here:
 *
 * <p>{@link #dependsOnStepId} {@code == null} means the step runs in
 * <b>parallel</b> from journey start, not "first". The database enforces
 * only that a dependency stays inside the same template — that an earlier
 * step is named is C-119's job, evaluated by the designer and the service
 * on every reorder and delete.
 */
@Entity
@Table(name = "ob_journey_template_steps")
public class ObJourneyTemplateStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "template_id", nullable = false)
    private Long templateId;

    @Column(name = "sequence", nullable = false)
    private int sequence;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    /**
     * {@code ob_journey_template_stages.id} — the stage group this task sits
     * inside, and the third level of Module Service → Stage → <b>Task</b> →
     * Task list.
     *
     * <p>Never null: a task outside a stage is not a state this model has, and
     * the column says so ({@code V20260911_1630}).
     *
     * <p>This replaces the {@code implementation_stage_id} that
     * {@code V20260911_1600} put here six hours earlier. Which OB-15 stage a
     * task belongs to is still exactly one fact — it just lives on the group
     * now, because a stage holds many tasks and the old column could only say
     * "this step <em>is</em> that stage".
     */
    @Column(name = "template_stage_id", nullable = false)
    private Long templateStageId;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    /** Working days (v1.2) — never hours. See CLAUDE.md's working-calendar rule. */
    @Column(name = "tat_days", nullable = false)
    private int tatDays;

    /**
     * The <b>implementor</b> — who this task is put on when a client's journey
     * is created from this service.
     *
     * <p>Null is the ordinary case and not a gap: a task nobody is named on
     * falls back at instantiation to the project's own
     * {@link ObProject#getImplementorUserId()}, and to its creator after that.
     * A service is authored once and boarded for many projects, so naming
     * somebody here is for the task that must always go to one particular
     * person, not for the usual one.
     *
     * <p><b>There is no {@code owner_role} or {@code backup_owner_user_id}
     * beside it any more</b> ({@code V20260914_1830}). The role was a fallback
     * nothing ever resolved — {@code ObJourneyInstantiationService} never
     * consulted it, because no per-client role→user resolver exists — and the
     * project implementor answers the same question with a person rather than
     * an intention. The backup was leave coverage, which is a fact about a
     * live journey and stayed on {@link ObJourneyStep}, where it can be set
     * per client and is what the scope queries already read.
     */
    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Column(name = "requires_signoff", nullable = false)
    private boolean requiresSignoff;

    /** {@code null} = parallel. See the class javadoc. */
    @Column(name = "depends_on_step_id")
    private Long dependsOnStepId;

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

    public Long getTemplateId() {
        return templateId;
    }

    public void setTemplateId(Long templateId) {
        this.templateId = templateId;
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

    public Long getTemplateStageId() {
        return templateStageId;
    }

    public void setTemplateStageId(Long templateStageId) {
        this.templateStageId = templateStageId;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
