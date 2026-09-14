package com.edunext.edutrack.domain.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ObJourneyStepItemRepository extends JpaRepository<ObJourneyStepItem, Long> {

    List<ObJourneyStepItem> findByStepIdOrderBySequenceAsc(Long stepId);

    /**
     * B-131 · every existing item across a set of steps, in one round trip.
     *
     * <p>The back-fill needs the next free {@code sequence} per step —
     * {@code uq_ob_journey_step_items_seq (step_id, sequence)} refuses a
     * collision — and a step's instance sequences cannot be assumed to match
     * its template's: an admin may have added an ad-hoc item to one client's
     * step that no template ever carried. So the highest in use is read per
     * step rather than derived from the template item's own sequence.
     *
     * <p>Fetched as rows and reduced in memory rather than as a
     * {@code group by} projection: the set is one step per live journey on a
     * single service, and the rows are wanted anyway by nothing else here —
     * a second query shape to save a few objects is not worth the second
     * query shape.
     */
    List<ObJourneyStepItem> findByStepIdInOrderByStepIdAscSequenceAsc(Collection<Long> stepIds);
}
