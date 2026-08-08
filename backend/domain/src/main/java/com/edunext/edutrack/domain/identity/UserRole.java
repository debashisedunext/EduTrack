package com.edunext.edutrack.domain.identity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;

/**
 * The many-to-many user↔role edge from the blueprint §8.1 ERD.
 *
 * <p><b>Dormant. Not read by the authentication path.</b>
 * {@link User#getRole()} is authoritative — A-022's JWT carries one
 * {@code role} claim and A-034 switches on one role. This table is the seam for
 * the multi-role extension in blueprint §16 and is expected to hold no rows
 * until then. A permission check that consults it would give a user
 * capabilities their JWT does not claim.
 *
 * <p>A-003 flagged it with Stream A as schema arbiter: drop it if we would
 * rather not carry two places a role could live.
 */
@Entity
@Table(name = "user_roles")
public class UserRole {

    @EmbeddedId
    private UserRoleId id;

    @MapsId("userId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @MapsId("roleId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    @Generated(event = EventType.INSERT)
    @Column(name = "assigned_at", insertable = false, updatable = false)
    private Instant assignedAt;

    /** Audit actor — scalar, per the package-info rule. */
    @Column(name = "assigned_by")
    private Long assignedBy;

    public UserRoleId getId() {
        return id;
    }

    public void setId(UserRoleId id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }

    public Long getAssignedBy() {
        return assignedBy;
    }

    public void setAssignedBy(Long assignedBy) {
        this.assignedBy = assignedBy;
    }
}
