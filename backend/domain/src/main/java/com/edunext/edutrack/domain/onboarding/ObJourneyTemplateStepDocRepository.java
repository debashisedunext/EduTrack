package com.edunext.edutrack.domain.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ObJourneyTemplateStepDocRepository extends JpaRepository<ObJourneyTemplateStepDoc, Long> {

    List<ObJourneyTemplateStepDoc> findByStepIdOrderBySequenceAsc(Long stepId);

    /** Next sequence for a new doc is this row's {@code sequence + 1}. */
    Optional<ObJourneyTemplateStepDoc> findTopByStepIdOrderBySequenceDesc(Long stepId);
}
