package com.edunext.edutrack.domain.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;

/**
 * A user's membership of a project — and therefore the PM/Support row scope.
 *
 * <p>A-034 resolves "project_id IN (my projects)" from these rows on every
 * ticket query a PM or Support user makes, which is why membership is
 * deactivated ({@code isActive = false}) rather than deleted: removing the row
 * would also remove the audit of who could once see what.
 */
@Entity
@Table(name = "project_members")
public class ProjectMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Ownership — a membership row is meaningless without its project, and the
     * Project Master's members tab walks exactly this edge.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    /** Scalar, per the package-info rule — see {@link User#getReportingManagerId()}. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * Per-project role (S-07), which may differ from {@link User#getRole()}: a
     * Developer globally can be mapped as QA on one project. It does not widen
     * scope — A-034 still switches on the user's authoritative role.
     */
    @Column(name = "role_in_project", length = 30)
    private String roleInProject;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Generated(event = EventType.INSERT)
    @Column(name = "added_at", insertable = false, updatable = false)
    private Instant addedAt;

    /** Audit actor — scalar. */
    @Column(name = "added_by")
    private Long addedBy;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Project getProject() {
        return project;
    }

    public void setProject(Project project) {
        this.project = project;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getRoleInProject() {
        return roleInProject;
    }

    public void setRoleInProject(String roleInProject) {
        this.roleInProject = roleInProject;
    }

    public boolean isActive() {
        return isActive;
    }

    public void setActive(boolean active) {
        this.isActive = active;
    }

    public Instant getAddedAt() {
        return addedAt;
    }

    public Long getAddedBy() {
        return addedBy;
    }

    public void setAddedBy(Long addedBy) {
        this.addedBy = addedBy;
    }
}
