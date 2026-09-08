package com.edunext.edutrack.domain.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ObClientPrereqTaskRepository extends JpaRepository<ObClientPrereqTask, Long> {

    /**
     * The whole checklist, in display order. Not paginated, and the contract
     * says why: the instance is a snapshot of one master version, so the
     * bound is the master's own, and both the progress bar and the gate
     * arithmetic need the complete set. A page-two task nobody fetched is a
     * mandatory task nobody knows is outstanding.
     */
    List<ObClientPrereqTask> findByHeaderIdOrderBySequenceAsc(Long headerId);

    /**
     * The gate's own read — {@code ix_ob_client_prereq_tasks_gate}. Keyed by
     * client rather than by header because that is the column A-112's scope
     * rule and C-118's evaluation both speak.
     */
    List<ObClientPrereqTask> findByObClientIdOrderBySequenceAsc(Long obClientId);

    Optional<ObClientPrereqTask> findTopByHeaderIdOrderBySequenceDesc(Long headerId);
}
