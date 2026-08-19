package com.edunext.edutrack.domain.masters;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

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

    /**
     * The whole matrix, retired rows included — B-039's S-13 tab 1.
     *
     * <p><b>Inactive rows are returned rather than filtered.</b> Every other read
     * on this interface is the engine asking "may this move happen?", where a
     * retired row and an absent row are the same answer. The S-13 grid is asking
     * a different question: it has to render a cell an Admin <em>cleared</em>
     * differently from one nobody ever configured, because restoring the first is
     * a click and authoring the second is a decision.
     *
     * <p>Ordered by id so the grid's row order is stable across saves — the
     * upsert keeps ids, so a cell does not move when its neighbour is edited.
     */
    List<WorkflowTransition> findAllByOrderByIdAsc();

    List<WorkflowTransition> findByRoleCodeOrderByIdAsc(String roleCode);

    /**
     * One cell, active or not — the upsert's lookup.
     *
     * <p>Matches {@code uq_workflow_transitions (from_status, to_status,
     * role_code)} exactly, which is what makes the upsert an upsert rather than
     * an insert that sometimes violates a unique key. As on
     * {@link #findByFromStatusAndRoleCodeAndIsActiveTrue}, a null
     * {@code fromStatus} renders to {@code is null} rather than {@code = null},
     * so the on-create rows resolve through the same method.
     */
    Optional<WorkflowTransition> findByFromStatusAndToStatusAndRoleCode(
            String fromStatus, String toStatus, String roleCode);
}
