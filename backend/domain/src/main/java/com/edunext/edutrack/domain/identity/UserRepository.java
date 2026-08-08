package com.edunext.edutrack.domain.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    /** The login lookup (A-020). Inactive users are rejected after the hash
     *  comparison, not by this query — filtering here would make the failure
     *  timing distinguishable. */
    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    Optional<User> findByEmpCode(String empCode);

    /**
     * Direct reports. The Resource Master walks this upward from a candidate
     * manager to detect a reporting cycle at any depth before saving — the
     * A-003 trigger only catches the depth-1 self-reference.
     */
    List<User> findByReportingManagerId(Long reportingManagerId);

    /** Assignee pickers and the capacity heatmap. */
    List<User> findByIsActiveTrueOrderByFullNameAsc();

    /** Both are unique keys; the import dry run (B-034) tests them per row
     *  without attempting the insert. */
    boolean existsByEmpCode(String empCode);

    boolean existsByEmail(String email);
}
