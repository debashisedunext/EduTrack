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
 * One granted capability — a single cell of the blueprint §2 matrix, rendered
 * by S-09 and read by A-033 when it resolves {@code @PreAuthorize}.
 *
 * <p>Both sides are {@code @MapsId} associations rather than scalar ids: this
 * row has no meaning apart from the pair it joins, and the matrix screen walks
 * both edges to label its axes. The cascade to {@code ON DELETE CASCADE} lives
 * in the schema, so removing a permission code takes its grants with it.
 */
@Entity
@Table(name = "role_permissions")
public class RolePermission {

    @EmbeddedId
    private RolePermissionId id;

    @MapsId("roleId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    @MapsId("permissionId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "permission_id", nullable = false)
    private Permission permission;

    @Generated(event = EventType.INSERT)
    @Column(name = "granted_at", insertable = false, updatable = false)
    private Instant grantedAt;

    public RolePermissionId getId() {
        return id;
    }

    public void setId(RolePermissionId id) {
        this.id = id;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public Permission getPermission() {
        return permission;
    }

    public void setPermission(Permission permission) {
        this.permission = permission;
    }

    public Instant getGrantedAt() {
        return grantedAt;
    }
}
