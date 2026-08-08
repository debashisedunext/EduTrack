package com.edunext.edutrack.domain.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, Long> {

    /**
     * The PM/Support row scope: the projects this user may see tickets in.
     * A-034 runs this on every scoped query, which is what
     * {@code ix_project_members_user} exists for.
     */
    List<ProjectMember> findByUserIdAndIsActiveTrue(Long userId);

    /** The members tab of the Project Master. */
    List<ProjectMember> findByProject_IdAndIsActiveTrue(Long projectId);

    /**
     * The {@code uq_project_members} pair. Re-adding a removed member has to
     * reactivate this row rather than insert a second one.
     */
    Optional<ProjectMember> findByProject_IdAndUserId(Long projectId, Long userId);
}
