package com.edunext.edutrack.domain.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

/**
 * Identity of a {@link RolePermission}: the pair <em>is</em> the key, so A-003
 * gave the table a composite primary key and no surrogate.
 *
 * <p>{@code equals}/{@code hashCode} are not decoration — Hibernate uses them
 * to key the persistence context, and an embeddable id without them silently
 * loads the same row twice.
 */
@Embeddable
public class RolePermissionId implements Serializable {

    @Column(name = "role_id", nullable = false)
    private Integer roleId;

    @Column(name = "permission_id", nullable = false)
    private Integer permissionId;

    public Integer getRoleId() {
        return roleId;
    }

    public void setRoleId(Integer roleId) {
        this.roleId = roleId;
    }

    public Integer getPermissionId() {
        return permissionId;
    }

    public void setPermissionId(Integer permissionId) {
        this.permissionId = permissionId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RolePermissionId that)) {
            return false;
        }
        return Objects.equals(roleId, that.roleId)
                && Objects.equals(permissionId, that.permissionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(roleId, permissionId);
    }
}
