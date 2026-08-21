package com.edunext.edutrack.domain.workflow;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * B-041 · the routing rules of §4A.9.
 *
 * <p>Derived queries where the shape allows and one {@code @Query} where it does
 * not — see {@link #findCandidates}, which is the only interesting method here.
 */
public interface WorkflowTemplateMappingRepository
        extends JpaRepository<WorkflowTemplateMapping, Long> {

    /** Every rule belonging to one template — the read S-13 tab 3 makes. */
    List<WorkflowTemplateMapping> findByTemplateIdOrderByIdAsc(long templateId);

    /**
     * The rule occupying one pair, whichever template owns it.
     *
     * <p>Spring Data cannot express "column IS NULL when the parameter is null"
     * in a derived name — {@code findByProjectIdAndTaskTypeId(null, 3)} generates
     * {@code project_id = ?} and matches nothing, silently. That is the whole
     * reason this is a {@code @Query} with an explicit null test, and the reason
     * a derived variant is deliberately absent rather than left available to be
     * picked up by mistake.
     */
    @Query("""
            SELECT m FROM WorkflowTemplateMapping m
             WHERE ((:projectId IS NULL AND m.projectId IS NULL)
                     OR m.projectId = :projectId)
               AND ((:taskTypeId IS NULL AND m.taskTypeId IS NULL)
                     OR m.taskTypeId = :taskTypeId)
            """)
    Optional<WorkflowTemplateMapping> findByPair(@Param("projectId") Long projectId,
                                                 @Param("taskTypeId") Integer taskTypeId);

    /**
     * Every rule that could apply to one project × task type, in one statement.
     *
     * <p>Returns rungs 1, 2 and 3 together and leaves the choice between them to
     * {@code TemplateResolver}. <b>Three round trips would have been the obvious
     * shape and it is the wrong one</b>: they are not independent reads, they are
     * one decision, and evaluating them in sequence means a rule inserted between
     * the first and the third can be seen by one and not the other — a resolution
     * that matches no state the table was ever in.
     *
     * <p>Ordering is by specificity descending, so the caller takes the head. It
     * is expressed as a {@code CASE} rather than left to the caller to sort
     * because "most specific wins" is the rule itself, and a caller that sorted it
     * differently would not be using this method wrongly, it would be resolving
     * differently.
     *
     * <p>{@code (null, null)} is included by the same predicate that admits rungs
     * 2 and 3 — it ranks 0, below all of them, and above the default template.
     */
    @Query("""
            SELECT m FROM WorkflowTemplateMapping m
             WHERE (m.projectId IS NULL OR m.projectId = :projectId)
               AND (m.taskTypeId IS NULL OR m.taskTypeId = :taskTypeId)
             ORDER BY (CASE WHEN m.projectId IS NULL THEN 0 ELSE 1 END
                     + CASE WHEN m.taskTypeId IS NULL THEN 0 ELSE 1 END) DESC,
                      m.id ASC
            """)
    List<WorkflowTemplateMapping> findCandidates(@Param("projectId") Long projectId,
                                                 @Param("taskTypeId") Integer taskTypeId);

    /** Whether a project may still be deleted, and how many rules a task type carries. */
    long countByProjectId(long projectId);

    long countByTaskTypeId(int taskTypeId);

    long countByTemplateId(long templateId);
}
