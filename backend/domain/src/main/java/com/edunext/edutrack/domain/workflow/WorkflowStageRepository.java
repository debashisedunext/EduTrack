package com.edunext.edutrack.domain.workflow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkflowStageRepository extends JpaRepository<WorkflowStage, Long> {

    /** The ribbon definition, left to right. */
    List<WorkflowStage> findByTemplateIdOrderBySeqAsc(Long templateId);

    Optional<WorkflowStage> findByTemplateIdAndStageCode(Long templateId, String stageCode);

    /**
     * Every stage a role owns, across templates — the input to "what is waiting
     * on QA" without joining through tickets first.
     */
    List<WorkflowStage> findByOwnerRole(String ownerRole);
}
