package com.edunext.edutrack.domain.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PermissionRepository extends JpaRepository<Permission, Integer> {

    Optional<Permission> findByCode(String code);

    List<Permission> findByCategoryOrderByCodeAsc(String category);

    /** The S-09 matrix, already in the order the screen groups it. */
    List<Permission> findAllByOrderByCategoryAscCodeAsc();
}
