package com.edunext.edutrack.domain.masters;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * The three lookups of the §6 resolution ladder, most specific first:
 * {@code (project, taskType, level)} → {@code (project, level)} →
 * {@code (null, level)}. A caller stops at the first hit; missing all three
 * means the ticket has no SLA and the scanner leaves it alone.
 *
 * <p>Only the first rung is protected by {@code uq_sla_policies}. MySQL treats
 * NULLs in a unique index as distinct, so the other two can legitimately match
 * more than one row — they return lists ordered by id, so "first wins" is at
 * least stable across scans rather than whatever the optimiser hands back
 * today. Tightening that is a schema change and Stream A's call.
 */
public interface SlaPolicyRepository extends JpaRepository<SlaPolicy, Long> {

    /** Rung 1 — exact project and task type. Unique-enforced. */
    Optional<SlaPolicy> findByProjectIdAndTaskTypeIdAndLevelAndIsActiveTrue(
            Long projectId, Integer taskTypeId, String level);

    /** Rung 2 — the project's default for a level, any task type. */
    List<SlaPolicy> findByProjectIdAndTaskTypeIdIsNullAndLevelAndIsActiveTrueOrderByIdAsc(
            Long projectId, String level);

    /** Rung 3 — the org-wide default. */
    List<SlaPolicy> findByProjectIdIsNullAndTaskTypeIdIsNullAndLevelAndIsActiveTrueOrderByIdAsc(
            String level);
}
