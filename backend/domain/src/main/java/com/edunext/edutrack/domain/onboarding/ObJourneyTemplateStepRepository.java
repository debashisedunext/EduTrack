package com.edunext.edutrack.domain.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ObJourneyTemplateStepRepository extends JpaRepository<ObJourneyTemplateStep, Long> {

    List<ObJourneyTemplateStep> findByTemplateIdOrderBySequenceAsc(Long templateId);

    /** Next sequence for a new step is this row's {@code sequence + 1}. */
    Optional<ObJourneyTemplateStep> findTopByTemplateIdOrderBySequenceDesc(Long templateId);

    long countByTemplateId(Long templateId);

    /** Every step inside the template that would be orphaned by deleting {@code stepId}. */
    List<ObJourneyTemplateStep> findByTemplateIdAndDependsOnStepId(Long templateId, Long dependsOnStepId);
}
