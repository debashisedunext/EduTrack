package com.edunext.edutrack.domain.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ObJourneyStepRepository extends JpaRepository<ObJourneyStep, Long> {

    List<ObJourneyStep> findByJourneyIdOrderBySequenceAsc(Long journeyId);

    /**
     * The Manager's unassigned list (C-103): every step instantiation could
     * not resolve an owner for, oldest first. {@code ix_ob_journey_steps_owner
     * (owner_user_id, status)} is shaped for exactly this scan.
     */
    List<ObJourneyStep> findByOwnerUserIdIsNullOrderByIdAsc();

    /**
     * B-131 · every live step snapshotted from one template step — what a
     * checklist item added to a service already in use has to reach.
     *
     * <p><b>Matched on {@code templateStepId}, not by resemblance.</b> That
     * column is provenance and is the only exact answer to "which running
     * steps came from this one". Matching on name instead would reach steps
     * of other versions of the same service, which is precisely the set that
     * must not be touched: those journeys pinned an older version and it is
     * frozen for as long as they exist.
     *
     * <p><b>Archived journeys are excluded.</b> A closed client is history in
     * the same way a retired version is; adding an item to one would put an
     * unanswered row on a journey nobody will ever answer it on, and would
     * make the count reported back to the admin a number of clients larger
     * than the number actually onboarding.
     *
     * <p>{@code ix_ob_journey_steps_template_step} serves the scan.
     */
    @Query("""
            select s
              from ObJourneyStep s
             where s.templateStepId = :templateStepId
               and exists (select 1
                             from ObJourney j
                            where j.id = s.journeyId
                              and j.archivedAt is null)
             order by s.id asc
            """)
    List<ObJourneyStep> findLiveByTemplateStepId(@Param("templateStepId") Long templateStepId);
}
