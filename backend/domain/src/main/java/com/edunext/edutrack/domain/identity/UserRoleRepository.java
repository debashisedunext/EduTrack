package com.edunext.edutrack.domain.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Repository for the dormant multi-role seam. Nothing on the authentication
 * path calls it — see {@link UserRole}.
 */
public interface UserRoleRepository extends JpaRepository<UserRole, UserRoleId> {

    List<UserRole> findById_UserId(Long userId);

    List<UserRole> findById_RoleId(Integer roleId);
}
