package com.edunext.edutrack.domain.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RolePermissionRepository
        extends JpaRepository<RolePermission, RolePermissionId> {

    /**
     * Every capability a role holds — what A-033 loads to build the granted
     * authorities. The {@code id_} prefix targets the embedded key rather than
     * the association, so no join is emitted for the lookup itself.
     */
    List<RolePermission> findById_RoleId(Integer roleId);

    /** The other axis of the S-09 matrix: which roles hold this capability. */
    List<RolePermission> findById_PermissionId(Integer permissionId);

    /**
     * The matrix saves as replace-all: clear a role's grants, insert the new
     * set. Delete is legitimate here — {@code role_permissions} is current
     * state, not the append-only audit.
     */
    void deleteById_RoleId(Integer roleId);
}
