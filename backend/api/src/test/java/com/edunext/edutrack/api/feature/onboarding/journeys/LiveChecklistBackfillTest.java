package com.edunext.edutrack.api.feature.onboarding.journeys;

import com.edunext.edutrack.domain.onboarding.ObJourneyStep;
import com.edunext.edutrack.domain.onboarding.ObJourneyStepItem;
import com.edunext.edutrack.domain.onboarding.ObJourneyStepItemRepository;
import com.edunext.edutrack.domain.onboarding.ObJourneyStepRepository;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStepItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B-131 · {@link LiveChecklistBackfill} — the half of "add a checklist item to
 * a service already in use" that reaches the clients using it.
 */
class LiveChecklistBackfillTest {

    private static final long TEMPLATE_STEP = 500L;

    private final ObJourneyStepRepository journeySteps = mock(ObJourneyStepRepository.class);
    private final ObJourneyStepItemRepository journeyStepItems = mock(ObJourneyStepItemRepository.class);

    private final LiveChecklistBackfill backfill =
            new LiveChecklistBackfill(journeySteps, journeyStepItems);

    /** Every row the back-fill wrote, in the order it wrote them. */
    private final List<ObJourneyStepItem> saved = new ArrayList<>();
    private final AtomicLong itemIds = new AtomicLong(9000);

    private void givenLiveSteps(ObJourneyStep... steps) {
        when(journeySteps.findLiveByTemplateStepId(TEMPLATE_STEP)).thenReturn(List.of(steps));
        when(journeyStepItems.save(any(ObJourneyStepItem.class))).thenAnswer(call -> {
            ObJourneyStepItem row = call.getArgument(0);
            row.setId(itemIds.incrementAndGet());
            saved.add(row);
            return row;
        });
    }

    private void givenExistingItems(ObJourneyStepItem... existing) {
        when(journeyStepItems.findByStepIdInOrderByStepIdAscSequenceAsc(anyCollection()))
                .thenReturn(List.of(existing));
    }

    private static ObJourneyStep step(long id) {
        ObJourneyStep step = new ObJourneyStep();
        step.setId(id);
        step.setJourneyId(id * 10);
        step.setTemplateStepId(TEMPLATE_STEP);
        return step;
    }

    private static ObJourneyStepItem existingItem(long stepId, int sequence) {
        ObJourneyStepItem item = new ObJourneyStepItem();
        item.setStepId(stepId);
        item.setSequence(sequence);
        return item;
    }

    private static ObJourneyTemplateStepItem templateItem() {
        ObJourneyTemplateStepItem item = new ObJourneyTemplateStepItem();
        item.setId(77L);
        item.setStepId(TEMPLATE_STEP);
        item.setSequence(4);
        item.setLabel("Firewall exception approved");
        item.setMandatory(true);
        return item;
    }

    @Test
    @DisplayName("the item lands on every live step, and the count is what is reported back")
    void landsOnEveryLiveStep() {
        givenLiveSteps(step(1L), step(2L), step(3L));
        givenExistingItems(existingItem(1L, 1), existingItem(2L, 1), existingItem(3L, 1));

        int reached = backfill.addToLiveJourneys(TEMPLATE_STEP, templateItem());

        assertThat(reached).isEqualTo(3);
        assertThat(saved).hasSize(3);
        assertThat(saved).extracting(ObJourneyStepItem::getStepId).containsExactly(1L, 2L, 3L);
        assertThat(saved).allSatisfy(row -> {
            assertThat(row.getLabel()).isEqualTo("Firewall exception approved");
            // The join the completion gate reads mandatory-ness back through.
            assertThat(row.getTemplateItemId()).isEqualTo(77L);
        });
    }

    /**
     * The gate would otherwise pass an item nobody has answered. A back-filled
     * row is a new obligation, not a satisfied one.
     */
    @Test
    @DisplayName("a back-filled row arrives unanswered")
    void arrivesUnanswered() {
        givenLiveSteps(step(1L));
        givenExistingItems();

        backfill.addToLiveJourneys(TEMPLATE_STEP, templateItem());

        assertThat(saved).singleElement().satisfies(row -> {
            assertThat(row.getAnswer()).isNull();
            assertThat(row.getRemark()).isNull();
            assertThat(row.getAnsweredBy()).isNull();
            assertThat(row.getAnsweredAt()).isNull();
        });
    }

    /**
     * {@code uq_ob_journey_step_items_seq (step_id, sequence)} is what makes
     * this matter: two clients on the same service can have different numbers
     * of items, because an admin may have added an ad-hoc one to a single
     * journey. Copying the template item's own sequence — 4 here — would
     * collide on the step that already has four rows and pass on the one that
     * has one, so it would fail for some clients and not others.
     */
    @Test
    @DisplayName("sequence is the next free one per step, not the template item's")
    void sequencesPerStep() {
        givenLiveSteps(step(1L), step(2L));
        givenExistingItems(
                existingItem(1L, 1), existingItem(1L, 2), existingItem(1L, 3), existingItem(1L, 4),
                existingItem(2L, 1));

        backfill.addToLiveJourneys(TEMPLATE_STEP, templateItem());

        assertThat(saved).extracting(ObJourneyStepItem::getStepId, ObJourneyStepItem::getSequence)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple(1L, 5),
                        org.assertj.core.api.Assertions.tuple(2L, 2));
    }

    /** The emptiest case, and the one a map lookup would return null on. */
    @Test
    @DisplayName("a live step with no items yet starts at sequence 1")
    void firstItemOnAnEmptyStep() {
        givenLiveSteps(step(1L));
        givenExistingItems();

        backfill.addToLiveJourneys(TEMPLATE_STEP, templateItem());

        assertThat(saved).singleElement()
                .extracting(ObJourneyStepItem::getSequence).isEqualTo(1);
    }

    @Test
    @DisplayName("nobody on the service is 0 and writes nothing")
    void noLiveJourneys() {
        when(journeySteps.findLiveByTemplateStepId(TEMPLATE_STEP)).thenReturn(List.of());

        int reached = backfill.addToLiveJourneys(TEMPLATE_STEP, templateItem());

        assertThat(reached).isZero();
        assertThat(saved).isEmpty();
    }
}
