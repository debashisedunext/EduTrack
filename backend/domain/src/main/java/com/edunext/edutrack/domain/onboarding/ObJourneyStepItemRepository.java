package com.edunext.edutrack.domain.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ObJourneyStepItemRepository extends JpaRepository<ObJourneyStepItem, Long> {

    List<ObJourneyStepItem> findByStepIdOrderBySequenceAsc(Long stepId);
}
