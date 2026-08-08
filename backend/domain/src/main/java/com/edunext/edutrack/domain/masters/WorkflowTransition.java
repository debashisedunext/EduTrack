package com.edunext.edutrack.domain.masters;

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
 * One legal status move, for one role — the transition matrix seeded by B-003.
 *
 * <p><b>This table is a whitelist: a missing row means forbidden.</b> Governance
 * decision G-3 (PLAN.md §5) — may a Developer close a ticket? — is therefore
 * data, not code: there is simply no {@code (RESOLVED, CLOSED, DEVELOPER)} row.
 * Changing that policy is a seed edit, not a deploy.
 *
 * <p>Distinct from {@link com.edunext.edutrack.domain.workflow.WorkflowStage},
 * which governs the ribbon. §3 keeps status and stage apart on purpose.
 *
 * <p>{@code fromStatus} is null for "on creation", which is how NEW is reached.
 */
@Entity
@Table(name = "workflow_transitions")
public class WorkflowTransition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    /** Null means "on create" — the only way into NEW. */
    @Column(name = "from_status", length = 20)
    private String fromStatus;

    @Column(name = "to_status", nullable = false, length = 20)
    private String toStatus;

    @Column(name = "role_code", nullable = false, length = 20)
    private String roleCode;

    /** Forces a comment on the move, e.g. into ON_HOLD. */
    @Column(name = "requires_reason", nullable = false)
    private boolean requiresReason;

    /** G-1: effort must be logged before the ticket leaves the current hand. */
    @Column(name = "requires_effort", nullable = false)
    private boolean requiresEffort;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getFromStatus() {
        return fromStatus;
    }

    public void setFromStatus(String fromStatus) {
        this.fromStatus = fromStatus;
    }

    public String getToStatus() {
        return toStatus;
    }

    public void setToStatus(String toStatus) {
        this.toStatus = toStatus;
    }

    public String getRoleCode() {
        return roleCode;
    }

    public void setRoleCode(String roleCode) {
        this.roleCode = roleCode;
    }

    public boolean isRequiresReason() {
        return requiresReason;
    }

    public void setRequiresReason(boolean requiresReason) {
        this.requiresReason = requiresReason;
    }

    public boolean isRequiresEffort() {
        return requiresEffort;
    }

    public void setRequiresEffort(boolean requiresEffort) {
        this.requiresEffort = requiresEffort;
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
}
