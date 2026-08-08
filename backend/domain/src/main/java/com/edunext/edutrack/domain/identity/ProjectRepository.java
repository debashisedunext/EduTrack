package com.edunext.edutrack.domain.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    /** Resolves a ticket code's prefix back to its project. */
    Optional<Project> findByProjectCode(String projectCode);

    /** Guards the create path; the edit path may not change the code at all
     *  once a ticket exists (PLAN.md §3.2). */
    boolean existsByProjectCode(String projectCode);

    List<Project> findByStatusOrderByNameAsc(String status);

    List<Project> findByManagerId(Long managerId);
}
