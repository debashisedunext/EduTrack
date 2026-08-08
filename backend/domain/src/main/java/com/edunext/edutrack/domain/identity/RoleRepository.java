package com.edunext.edutrack.domain.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, Integer> {

    Optional<Role> findByCode(String code);

    /** The role picker on every resource form. */
    List<Role> findByIsActiveTrueOrderByNameAsc();

    /** Uniqueness check for the Role Master, ahead of the unique key. */
    boolean existsByCode(String code);
}
