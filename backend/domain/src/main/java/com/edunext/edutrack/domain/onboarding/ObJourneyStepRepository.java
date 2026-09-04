package com.edunext.edutrack.domain.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ObJourneyStepRepository extends JpaRepository<ObJourneyStep, Long> {

    List<ObJourneyStep> findByJourneyIdOrderBySequenceAsc(Long journeyId);

    /**
     * The Manager's unassigned list (C-103): every step instantiation could
     * not resolve an owner for, oldest first. {@code ix_ob_journey_steps_owner
     * (owner_user_id, status)} is shaped for exactly this scan.
     */
    List<ObJourneyStep> findByOwnerUserIdIsNullOrderByIdAsc();
}
