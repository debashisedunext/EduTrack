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
 * A stage group inside a Module Service — one row of
 * {@code ob_journey_template_stages}, and the second of the four levels
 * (Module Service → <b>Stage</b> → Task → Task list).
 *
 * <h2>It carries nothing, and that is the point</h2>
 *
 * <p>No TAT, no owner, no sign-off flag, no dependency. Those are facts about
 * a <em>task</em>, and a group that also held them would be a second place to
 * answer "who is on the hook for this and when is it due" — which is how two
 * screens end up disagreeing. What a stage has is a name, a position, and the
 * tasks that point at it. Its TAT on the designer is derived by summing its
 * tasks, never stored.
 *
 * <h2>The name is a copy, not a join</h2>
 *
 * <p>{@link #implementationStageId} says which OB-15 stage this is;
 * {@link #name} is that stage's name <em>at the moment the group was
 * created</em>. Renaming a stage on the master therefore leaves every
 * published template reading exactly as it was published, which is the same
 * call {@code ObJourneyTemplateStep} already makes and the migration
 * ({@code V20260911_1630}) records in full.
 *
 * <h2>A null stage id is the Ungrouped group</h2>
 *
 * <p>Steps written before {@code V20260911_1600} were hand-named and belong to
 * no stage. Rather than guess one for them, the migration collects them into a
 * single group per template with a null {@link #implementationStageId}. MySQL
 * treats nulls as distinct in a unique index, so {@code uq_ob_template_stages}
 * does not hold that group to one per template —
 * {@code ObJourneyTemplateService} does, being its only writer.
 */
@Entity
@Table(name = "ob_journey_template_stages")
public class ObJourneyTemplateStage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "template_id", nullable = false)
    private Long templateId;

    /** {@code ob_implementation_stages.id}, or null for the Ungrouped group. */
    @Column(name = "implementation_stage_id")
    private Long implementationStageId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    /**
     * Position within the template, copied from the stage master and pinned.
     *
     * <p>Not unique, on the same reasoning the master's own {@code sequence}
     * carries: renumbering is a set of UPDATEs and every ordering of them
     * passes through a moment where two rows share a slot.
     */
    @Column(name = "sequence", nullable = false)
    private int sequence;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    protected ObJourneyTemplateStage() {
    }

    public ObJourneyTemplateStage(Long templateId, Long implementationStageId, String name, int sequence) {
        this.templateId = templateId;
        this.implementationStageId = implementationStageId;
        this.name = name;
        this.sequence = sequence;
    }

    public Long getId() {
        return id;
    }

    /**
     * For the in-memory repository fakes, which have no database to hand out
     * identities — {@link ObJourneyTemplateStep} exposes one for the same
     * reason. Nothing in production calls it; the column is
     * {@code GenerationType.IDENTITY}.
     */
    public void setId(Long id) {
        this.id = id;
    }

    public Long getTemplateId() {
        return templateId;
    }

    public void setTemplateId(Long templateId) {
        this.templateId = templateId;
    }

    public Long getImplementationStageId() {
        return implementationStageId;
    }

    public void setImplementationStageId(Long implementationStageId) {
        this.implementationStageId = implementationStageId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getSequence() {
        return sequence;
    }

    public void setSequence(int sequence) {
        this.sequence = sequence;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
