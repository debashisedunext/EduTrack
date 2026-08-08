package com.edunext.edutrack.domain.masters;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The response and resolution targets for a project × task type × level —
 * blueprint §6, and the table Stream D's SLA scanner reads on every pass.
 *
 * <p>Rows are layered rather than exhaustive. A null {@code projectId} is the
 * org-wide default and a null {@code taskTypeId} means "any type", so resolution
 * runs most-specific-first:
 * {@code (project, taskType, level) -> (project, level) -> (null, level)}.
 * See {@link SlaPolicyRepository} for the three lookups that implement it.
 *
 * <p><b>{@code responseHrs} and {@code resolutionHrs} are working hours.</b>
 * They are consumed through the calendar service (B-024) — weekends, org
 * holidays and resource leave — never as wall-clock. {@link Holiday} and
 * {@link ResourceLeave} are the two halves of that calendar.
 */
@Entity
@Table(name = "sla_policies")
public class SlaPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Null = the org-wide default.
     *
     * <p>Kept a scalar id rather than a {@code @ManyToOne}: this is a scoping
     * key, not ownership, and a policy is looked up by project id — never
     * navigated to one. Mapping it as an association would hang a lazy proxy off
     * every row an SLA scan touches. Same reasoning for {@link #taskTypeId},
     * {@link Holiday#getProjectId()} and {@link ResourceLeave#getUserId()}.
     */
    @Column(name = "project_id")
    private Long projectId;

    /** Null = any task type. */
    @Column(name = "task_type_id")
    private Integer taskTypeId;

    /** The {@link Priority} code, matching {@code tickets.level}. */
    @Column(name = "level", nullable = false, length = 10)
    private String level;

    /** Time to first response. Null where the policy only targets resolution. */
    @Column(name = "response_hrs", precision = 6, scale = 2)
    private BigDecimal responseHrs;

    @Column(name = "resolution_hrs", nullable = false, precision = 6, scale = 2)
    private BigDecimal resolutionHrs;

    /** Escalate to the assignee's reporting manager on breach. */
    @Column(name = "escalate_to_l1", nullable = false)
    private boolean escalateToL1 = true;

    /** And to that manager's manager — the §6 48-hour rule. */
    @Column(name = "escalate_to_l2", nullable = false)
    private boolean escalateToL2;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

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

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public BigDecimal getResponseHrs() {
        return responseHrs;
    }

    public void setResponseHrs(BigDecimal responseHrs) {
        this.responseHrs = responseHrs;
    }

    public BigDecimal getResolutionHrs() {
        return resolutionHrs;
    }

    public void setResolutionHrs(BigDecimal resolutionHrs) {
        this.resolutionHrs = resolutionHrs;
    }

    public boolean isEscalateToL1() {
        return escalateToL1;
    }

    public void setEscalateToL1(boolean escalateToL1) {
        this.escalateToL1 = escalateToL1;
    }

    public boolean isEscalateToL2() {
        return escalateToL2;
    }

    public void setEscalateToL2(boolean escalateToL2) {
        this.escalateToL2 = escalateToL2;
    }

    public boolean isActive() {
        return isActive;
    }

    public void setActive(boolean active) {
        this.isActive = active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
