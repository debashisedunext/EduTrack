package com.edunext.edutrack.api.feature.onboarding.instances;

import com.edunext.edutrack.domain.onboarding.ObGateStatus;
import com.edunext.edutrack.domain.onboarding.ObJourney;
import com.edunext.edutrack.domain.onboarding.ObJourneyRepository;
import com.edunext.edutrack.domain.onboarding.ObJourneyStep;
import com.edunext.edutrack.domain.onboarding.ObJourneyStepItem;
import com.edunext.edutrack.domain.onboarding.ObJourneyStepItemRepository;
import com.edunext.edutrack.domain.onboarding.ObJourneyStepRepository;
import com.edunext.edutrack.domain.onboarding.ObJourneyStepStatus;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplate;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateRepository;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStep;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStepItem;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStepItemRepository;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStepRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * C-103 · {@link ObJourneyInstantiationService}. Same shape as
 * {@code ObJourneyTemplateServiceTest} — Mockito-backed repositories over a
 * small in-memory map, because {@code instantiate} saves a cloned step and
 * immediately queries its items by the id just assigned, which a
 * fixed-return-value stub cannot model.
 *
 * <p>{@code ownerRoleOnlyStepIsUnresolved} is the one every other test here
 * is measured against — the exact regression the service javadoc's "no
 * role→user resolution" boundary warns about. Falling back to any
 * placeholder user, or copying {@code ownerRole} verbatim onto a numeric FK,
 * would fail it.
 */
class ObJourneyInstantiationServiceTest {

    private static final long CLIENT = 900L;
    private static final long PRODUCT = 500L;
    private static final long TEMPLATE = 700L;

    private final Map<Long, ObJourney> journeyRows = new LinkedHashMap<>();
    private final Map<Long, ObJourneyStep> stepRows = new LinkedHashMap<>();
    private final Map<Long, ObJourneyStepItem> itemRows = new LinkedHashMap<>();

    private final AtomicLong journeyIds = new AtomicLong();
    private final AtomicLong stepIds = new AtomicLong();
    private final AtomicLong itemIds = new AtomicLong();

    private final ObJourneyRepository journeys = mock(ObJourneyRepository.class);
    private final ObJourneyStepRepository journeySteps = mock(ObJourneyStepRepository.class);
    private final ObJourneyStepItemRepository journeyStepItems = mock(ObJourneyStepItemRepository.class);
    private final ObJourneyTemplateRepository templates = mock(ObJourneyTemplateRepository.class);
    private final ObJourneyTemplateStepRepository templateSteps = mock(ObJourneyTemplateStepRepository.class);
    private final ObJourneyTemplateStepItemRepository templateStepItems = mock(ObJourneyTemplateStepItemRepository.class);
    private final PurchasedProductAccess purchasedProducts = mock(PurchasedProductAccess.class);

    private final ObJourneyInstantiationService service = new ObJourneyInstantiationService(
            journeys, journeySteps, journeyStepItems, templates, templateSteps, templateStepItems, purchasedProducts);

    @BeforeEach
    void wireFakes() {
        lenient().when(purchasedProducts.isPurchased(anyLong(), anyLong())).thenReturn(true);

        lenient().when(journeys.save(any())).thenAnswer(inv -> {
            ObJourney j = inv.getArgument(0);
            if (j.getId() == null) {
                j.setId(journeyIds.incrementAndGet());
            }
            journeyRows.put(j.getId(), j);
            return j;
        });
        lenient().when(journeys.existsByObClientIdAndProductIdAndArchivedAtIsNull(any(), any())).thenReturn(false);
        lenient().when(journeys.existsByObClientIdAndGateStatus(any(), any())).thenAnswer(inv ->
                journeyRows.values().stream().anyMatch(j ->
                        j.getObClientId().equals(inv.<Long>getArgument(0)) && j.getGateStatus() == inv.getArgument(1)));

        lenient().when(templates.findByProductIdAndIsActiveTrue(any())).thenAnswer(inv -> {
            ObJourneyTemplate t = new ObJourneyTemplate();
            t.setId(TEMPLATE);
            t.setProductId(inv.getArgument(0));
            t.setVersion(1);
            t.setActive(true);
            return Optional.of(t);
        });

        lenient().when(journeySteps.save(any())).thenAnswer(inv -> {
            ObJourneyStep s = inv.getArgument(0);
            if (s.getId() == null) {
                s.setId(stepIds.incrementAndGet());
            }
            stepRows.put(s.getId(), s);
            return s;
        });
        lenient().when(journeySteps.findById(any())).thenAnswer(inv -> Optional.ofNullable(stepRows.get(inv.<Long>getArgument(0))));
        lenient().when(journeySteps.findByJourneyIdOrderBySequenceAsc(any())).thenAnswer(inv -> stepsFor(inv.getArgument(0)));
        lenient().when(journeySteps.findByOwnerUserIdIsNullOrderByIdAsc()).thenAnswer(inv ->
                stepRows.values().stream().filter(s -> s.getOwnerUserId() == null).toList());

        lenient().when(journeyStepItems.save(any())).thenAnswer(inv -> {
            ObJourneyStepItem i = inv.getArgument(0);
            if (i.getId() == null) {
                i.setId(itemIds.incrementAndGet());
            }
            itemRows.put(i.getId(), i);
            return i;
        });
        lenient().when(journeyStepItems.findByStepIdOrderBySequenceAsc(any())).thenAnswer(inv -> itemsFor(inv.getArgument(0)));
    }

    private List<ObJourneyStep> stepsFor(Long journeyId) {
        List<ObJourneyStep> result = new ArrayList<>(stepRows.values().stream()
                .filter(s -> s.getJourneyId().equals(journeyId)).toList());
        result.sort(Comparator.comparingInt(ObJourneyStep::getSequence));
        return result;
    }

    private List<ObJourneyStepItem> itemsFor(Long stepId) {
        List<ObJourneyStepItem> result = new ArrayList<>(itemRows.values().stream()
                .filter(i -> i.getStepId().equals(stepId)).toList());
        result.sort(Comparator.comparingInt(ObJourneyStepItem::getSequence));
        return result;
    }

    private ObJourneyTemplateStep templateStep(long id, int sequence, String name, Long ownerUserId,
                                                String ownerRole, Long dependsOnStepId) {
        ObJourneyTemplateStep step = new ObJourneyTemplateStep();
        step.setId(id);
        step.setTemplateId(TEMPLATE);
        step.setSequence(sequence);
        step.setName(name);
        step.setTatDays(2);
        step.setOwnerUserId(ownerUserId);
        step.setOwnerRole(ownerRole);
        step.setDependsOnStepId(dependsOnStepId);
        return step;
    }

    @Nested
    @DisplayName("instantiate — one journey per purchased product")
    class Instantiate {

        @Test
        @DisplayName("LOCKED, pinned to the active template's exact version, when the client's gate has never opened")
        void locksAndPinsTheTemplate() {
            when(templateSteps.findByTemplateIdOrderBySequenceAsc(TEMPLATE)).thenReturn(List.of(
                    templateStep(1L, 1, "Kickoff", 42L, null, null)));
            when(templateStepItems.findByStepIdOrderBySequenceAsc(1L)).thenReturn(List.of());

            ObJourney journey = service.instantiate(CLIENT, PRODUCT);

            assertThat(journey.getGateStatus()).isEqualTo(ObGateStatus.LOCKED);
            assertThat(journey.getGateOpenedAt()).isNull();
            assertThat(journey.getTemplateId()).isEqualTo(TEMPLATE);
            assertThat(journey.getHeldByJourneyId()).isNull();
        }

        @Test
        @DisplayName("refused when the client never purchased the product")
        void refusedWhenNotPurchased() {
            when(purchasedProducts.isPurchased(CLIENT, PRODUCT)).thenReturn(false);

            assertThatThrownBy(() -> service.instantiate(CLIENT, PRODUCT))
                    .isInstanceOf(ProductNotPurchasedException.class);
        }

        @Test
        @DisplayName("refused when a live journey already exists for this client and product")
        void refusedWhenAlreadyInstantiated() {
            when(journeys.existsByObClientIdAndProductIdAndArchivedAtIsNull(CLIENT, PRODUCT)).thenReturn(true);

            assertThatThrownBy(() -> service.instantiate(CLIENT, PRODUCT))
                    .isInstanceOf(JourneyAlreadyExistsException.class);
        }

        @Test
        @DisplayName("refused when the product has no active template")
        void refusedWhenNoActiveTemplate() {
            when(templates.findByProductIdAndIsActiveTrue(PRODUCT)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.instantiate(CLIENT, PRODUCT))
                    .isInstanceOf(NoActiveTemplateForProductException.class);
        }

        @Test
        @DisplayName("born OPEN, with no acting user, when this client's gate has already cleared")
        void inheritsAnAlreadyOpenGate() {
            ObJourney priorJourney = new ObJourney();
            priorJourney.setId(1L);
            priorJourney.setObClientId(CLIENT);
            priorJourney.setProductId(PRODUCT + 1);
            priorJourney.setGateStatus(ObGateStatus.OPEN);
            journeyRows.put(1L, priorJourney);

            when(templateSteps.findByTemplateIdOrderBySequenceAsc(TEMPLATE)).thenReturn(List.of());

            ObJourney journey = service.instantiate(CLIENT, PRODUCT);

            assertThat(journey.getGateStatus()).isEqualTo(ObGateStatus.OPEN);
            assertThat(journey.getGateOpenedAt()).isNotNull();
            assertThat(journey.getGateOpenedBy()).isNull();
        }
    }

    @Nested
    @DisplayName("step snapshot — owners, dependencies, task list")
    class StepSnapshot {

        @Test
        @DisplayName("a pinned owner carries forward; every step is born PENDING with no due date")
        void pinnedOwnerCarriesForward() {
            when(templateSteps.findByTemplateIdOrderBySequenceAsc(TEMPLATE)).thenReturn(List.of(
                    templateStep(1L, 1, "Kickoff", 42L, null, null)));
            when(templateStepItems.findByStepIdOrderBySequenceAsc(1L)).thenReturn(List.of());

            ObJourney journey = service.instantiate(CLIENT, PRODUCT);
            List<ObJourneyStep> steps = stepsFor(journey.getId());

            assertThat(steps).hasSize(1);
            ObJourneyStep step = steps.get(0);
            assertThat(step.getOwnerUserId()).isEqualTo(42L);
            assertThat(step.getStatus()).isEqualTo(ObJourneyStepStatus.PENDING);
            assertThat(step.getDueAt()).isNull();
            assertThat(step.getTemplateStepId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("a role-only step is unresolved, not a guess — lands on the Manager's unassigned list")
        void ownerRoleOnlyStepIsUnresolved() {
            when(templateSteps.findByTemplateIdOrderBySequenceAsc(TEMPLATE)).thenReturn(List.of(
                    templateStep(1L, 1, "Legal review", null, "OB_MANAGER", null)));
            when(templateStepItems.findByStepIdOrderBySequenceAsc(1L)).thenReturn(List.of());

            ObJourney journey = service.instantiate(CLIENT, PRODUCT);
            ObJourneyStep step = stepsFor(journey.getId()).get(0);

            assertThat(step.getOwnerUserId()).isNull();
            assertThat(service.unassignedSteps()).containsExactly(step);
        }

        @Test
        @DisplayName("depends_on_step_id is re-pointed at the journey's own cloned step, not the template's")
        void dependsOnIsRePointedAtTheClone() {
            // Template step ids deliberately outside the range the cloned
            // journey steps' own auto-increment (starting at 1) will ever
            // produce, so a clone that leaked the template's raw id instead
            // of re-pointing to its own clone cannot pass by coincidence.
            when(templateSteps.findByTemplateIdOrderBySequenceAsc(TEMPLATE)).thenReturn(List.of(
                    templateStep(101L, 1, "Kickoff", 42L, null, null),
                    templateStep(102L, 2, "Data migration", 42L, null, 101L)));
            when(templateStepItems.findByStepIdOrderBySequenceAsc(any())).thenReturn(List.of());

            ObJourney journey = service.instantiate(CLIENT, PRODUCT);
            List<ObJourneyStep> steps = stepsFor(journey.getId());

            ObJourneyStep kickoff = steps.get(0);
            ObJourneyStep migration = steps.get(1);
            assertThat(migration.getDependsOnStepId()).isEqualTo(kickoff.getId());
            assertThat(migration.getDependsOnStepId()).isNotEqualTo(101L);
        }

        @Test
        @DisplayName("task list items are snapshotted with their template item id as provenance")
        void taskListItemsAreSnapshotted() {
            when(templateSteps.findByTemplateIdOrderBySequenceAsc(TEMPLATE)).thenReturn(List.of(
                    templateStep(1L, 1, "Kickoff", 42L, null, null)));
            ObJourneyTemplateStepItem templateItem = new ObJourneyTemplateStepItem();
            templateItem.setId(11L);
            templateItem.setStepId(1L);
            templateItem.setSequence(1);
            templateItem.setLabel("PAN collected");
            templateItem.setMandatory(true);
            when(templateStepItems.findByStepIdOrderBySequenceAsc(1L)).thenReturn(List.of(templateItem));

            ObJourney journey = service.instantiate(CLIENT, PRODUCT);
            ObJourneyStep step = stepsFor(journey.getId()).get(0);
            List<ObJourneyStepItem> items = itemsFor(step.getId());

            assertThat(items).hasSize(1);
            assertThat(items.get(0).getLabel()).isEqualTo("PAN collected");
            assertThat(items.get(0).getTemplateItemId()).isEqualTo(11L);
            assertThat(items.get(0).getAnswer()).isNull();
        }
    }

    @Nested
    @DisplayName("instantiateAll — the wizard's multi-select")
    class InstantiateAll {

        @Test
        @DisplayName("one journey per product id, same order")
        void oneJourneyPerProduct() {
            when(templateSteps.findByTemplateIdOrderBySequenceAsc(any())).thenReturn(List.of());

            List<ObJourney> created = service.instantiateAll(CLIENT, List.of(PRODUCT, PRODUCT + 1));

            assertThat(created).hasSize(2);
            assertThat(created.get(0).getProductId()).isEqualTo(PRODUCT);
            assertThat(created.get(1).getProductId()).isEqualTo(PRODUCT + 1);
        }
    }
}
