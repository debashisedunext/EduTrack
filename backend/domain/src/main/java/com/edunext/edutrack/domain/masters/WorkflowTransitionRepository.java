package com.edunext.edutrack.domain.masters;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkflowTransitionRepository extends JpaRepository<WorkflowTransition, Integer> {

    /** Everything a role may ever do — the input to a permission matrix screen. */
    List<WorkflowTransition> findByRoleCodeAndIsActiveTrue(String roleCode);

    /**
     * The moves offered on a ticket detail page: what this role may do from the
     * status the ticket is in.
     *
     * <p>Pass {@code null} for {@code fromStatus} to get the on-create
     * transitions. Spring Data renders a null argument to a derived
     * {@code SIMPLE_PROPERTY} predicate as {@code is null} rather than
     * {@code = null}, which is the only reason this one method covers both
     * cases.
     */
    List<WorkflowTransition> findByFromStatusAndRoleCodeAndIsActiveTrue(String fromStatus, String roleCode);

    /**
     * The gate. This table is a whitelist, so absence is the answer: no row
     * means the move is forbidden for that role, and there is nothing else to
     * consult.
     */
    boolean existsByFromStatusAndToStatusAndRoleCodeAndIsActiveTrue(
            String fromStatus, String toStatus, String roleCode);
}
