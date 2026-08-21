package com.edunext.edutrack.domain.workflow;

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
 * One routing rule: which workflow template a ticket gets, given its project and
 * task type. Blueprint §4A.9 — "define a template per project and per task type".
 * B-041.
 *
 * <p><b>NULL means "any".</b> Both {@link #projectId} and {@link #taskTypeId} are
 * nullable, and that is what lets four rules be expressed in rows rather than in
 * code:
 *
 * <ol>
 *   <li>{@code (project, taskType)} — this project, this task type</li>
 *   <li>{@code (project, null)} — this project, whatever the task type</li>
 *   <li>{@code (null, taskType)} — this task type, whatever the project</li>
 *   <li>no row at all — the template carrying
 *       {@link WorkflowTemplate#isDefault()}</li>
 * </ol>
 *
 * <p>The ladder itself is {@code TemplateResolver}'s, not this entity's and not
 * the database's. A view could express the precedence and could not tell a caller
 * <em>which</em> rung answered, which is the one thing S-13 tab 3 has to show:
 * an Admin looking at a pair needs to know whether they are reading a rule
 * somebody wrote or a fallback nobody chose.
 *
 * <h2>The two columns that are not mapped here</h2>
 *
 * <p>{@code project_key} and {@code task_type_key} are {@code STORED} generated
 * columns holding {@code IFNULL(x, 0)}, and the unique key is over them rather
 * than over the two nullable columns above. They are deliberately absent from
 * this class: Hibernate would have to be told they are read-only on every path,
 * and nothing in Java should ever set them. The migration header carries the
 * reason they exist — MySQL treats every NULL inside a unique index as distinct,
 * so {@code UNIQUE (project_id, task_type_id)} would accept {@code (5, NULL)}
 * twice and leave rung 2 with two answers.
 *
 * <p>What that means for a caller of this class: a duplicate pair arrives as a
 * constraint violation from the database rather than as a field this code can
 * inspect. {@code TemplateService} checks for the collision itself before
 * writing, so the violation is the backstop rather than the mechanism.
 */
@Entity
@Table(name = "workflow_template_mappings")
public class WorkflowTemplateMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "template_id", nullable = false)
    private Long templateId;

    /** {@code null} = any project. */
    @Column(name = "project_id")
    private Long projectId;

    /** {@code null} = any task type. */
    @Column(name = "task_type_id")
    private Integer taskTypeId;

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

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public Integer getTaskTypeId() {
        return taskTypeId;
    }

    public void setTaskTypeId(Integer taskTypeId) {
        this.taskTypeId = taskTypeId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * How specific this rule is — 2 for an exact pair, 1 for a half-wildcard, 0
     * for the pair that matches everything.
     *
     * <p>Here rather than in the resolver because it is a property of the row, and
     * because the screen sorts by it too: a list showing the wildcard rules above
     * the exact ones reads as though the wildcards win.
     *
     * <p><b>A rank of 0 is legal and is not the same thing as the default
     * template.</b> {@code (null, null)} is an explicit "everything routes here"
     * an Admin wrote; the default is what applies when nobody wrote anything. They
     * usually name the same template and the difference shows the day somebody
     * changes one of them.
     */
    public int specificity() {
        return (projectId != null ? 1 : 0) + (taskTypeId != null ? 1 : 0);
    }
}
