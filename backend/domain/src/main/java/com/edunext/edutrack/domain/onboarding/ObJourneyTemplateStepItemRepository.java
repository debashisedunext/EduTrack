package com.edunext.edutrack.domain.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ObJourneyTemplateStepItemRepository extends JpaRepository<ObJourneyTemplateStepItem, Long> {

    List<ObJourneyTemplateStepItem> findByStepIdOrderBySequenceAsc(Long stepId);

    /** Next sequence for a new item is this row's {@code sequence + 1}. */
    Optional<ObJourneyTemplateStepItem> findTopByStepIdOrderBySequenceDesc(Long stepId);
}
