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
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateDependency;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateDependencyRepository;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateRepository;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStep;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStepItem;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStepItemRepository;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStepRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
    private final ObJourneyTemplateDependencyRepository templateDependencies =
            mock(ObJourneyTemplateDependencyRepository.class);
    private final ObJourneyTemplateStepRepository templateSteps = mock(ObJourneyTemplateStepRepository.class);
    private final ObJourneyTemplateStepItemRepository templateStepItems = mock(ObJourneyTemplateStepItemRepository.class);
    private final PurchasedProductAccess purchasedProducts = mock(PurchasedProductAccess.class);
    private final ObJourneyStepLifecycleService stepLifecycle = mock(ObJourneyStepLifecycleService.class);
    private final ObDemoStepDocumentSeeder demoStepDocumentSeeder = mock(ObDemoStepDocumentSeeder.class);

    private final ObJourneyInstantiationService service = new ObJourneyInstantiationService(
            journeys, journeySteps, journeyStepItems, templates, templateDependencies, templateSteps,
            templateStepItems, purchasedProducts, stepLifecycle, demoStepDocumentSeeder);

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
        lenient().when(journeys.existsByObClientIdAndProductIdAndServiceNameAndArchivedAtIsNull(
                any(), any(), any())).thenReturn(false);
        lenient().when(journeys.existsByObClientIdAndGateStatus(any(), any())).thenAnswer(inv ->
                journeyRows.values().stream().anyMatch(j ->
                        j.getObClientId().equals(inv.<Long>getArgument(0)) && j.getGateStatus() == inv.getArgument(1)));
        lenient().when(journeys.findFirstByObClientIdAndProductIdAndServiceNameAndArchivedAtIsNullOrderByIdDesc(
                        any(), any(), any()))
                .thenAnswer(inv -> journeyRows.values().stream()
                        .filter(j -> j.getObClientId().equals(inv.<Long>getArgument(0))
                                && j.getProductId().equals(inv.<Long>getArgument(1))
                                && inv.<String>getArgument(2).equals(j.getServiceName())
                                && j.getArchivedAt() == null)
                        .max(Comparator.comparing(ObJourney::getId)));

        // One Module Service per product by default; the two-service case is
        // its own test, which overrides this.
        lenient().when(templates.findByProductIdAndIsActiveTrueOrderBySequenceAscIdAsc(any())).thenAnswer(inv -> {
            ObJourneyTemplate t = new ObJourneyTemplate();
            t.setId(TEMPLATE);
            t.setProductId(inv.getArgument(0));
            t.setName("Standard SaaS Onboarding");
            t.setVersion(1);
            t.setActive(true);
            return List.of(t);
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

    /**
     * The one journey a single-service product instantiates.
     *
     * <p>{@code instantiate} returns a list because a product publishes as
     * many Module Services as it sells; asserting the size here rather than
     * indexing blindly means a test that silently started producing two
     * journeys fails on the count instead of on the wrong journey's fields.
     */
    private static ObJourney only(List<ObJourney> created) {
        assertThat(created).hasSize(1);
        return created.get(0);
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

            ObJourney journey = only(service.instantiate(CLIENT, PRODUCT));

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
        @DisplayName("refused when every service of the product already runs for this client")
        void refusedWhenAlreadyInstantiated() {
            when(journeys.existsByObClientIdAndProductIdAndServiceNameAndArchivedAtIsNull(
                    CLIENT, PRODUCT, "Standard SaaS Onboarding")).thenReturn(true);

            assertThatThrownBy(() -> service.instantiate(CLIENT, PRODUCT))
                    .isInstanceOf(JourneyAlreadyExistsException.class);
        }

        @Test
        @DisplayName("refused when the product publishes no active service")
        void refusedWhenNoActiveTemplate() {
            when(templates.findByProductIdAndIsActiveTrueOrderBySequenceAscIdAsc(PRODUCT)).thenReturn(List.of());

            assertThatThrownBy(() -> service.instantiate(CLIENT, PRODUCT))
                    .isInstanceOf(NoActiveTemplateForProductException.class);
        }

        @Test
        @DisplayName("a product publishing two Module Services instantiates one journey per service")
        void oneJourneyPerModuleService() {
            when(templates.findByProductIdAndIsActiveTrueOrderBySequenceAscIdAsc(PRODUCT))
                    .thenReturn(List.of(
                            activeService(TEMPLATE, "Standard SaaS Onboarding", 1),
                            activeService(TEMPLATE + 1, "Enterprise (with data migration audit)", 2)));
            when(templateSteps.findByTemplateIdOrderBySequenceAsc(any())).thenReturn(List.of());

            List<ObJourney> created = service.instantiate(CLIENT, PRODUCT);

            // Two ribbons on one purchase, in catalogue sequence, each pinned
            // to its own service's own version.
            assertThat(created).hasSize(2);
            assertThat(created).extracting(ObJourney::getServiceName)
                    .containsExactly("Standard SaaS Onboarding", "Enterprise (with data migration audit)");
            assertThat(created).extracting(ObJourney::getTemplateId)
                    .containsExactly(TEMPLATE, TEMPLATE + 1);
            assertThat(created).allMatch(j -> j.getProductId() == PRODUCT);
        }

        @Test
        @DisplayName("a service published after the client was boarded is topped up, not refused")
        void topsUpAServicePublishedLater() {
            when(templates.findByProductIdAndIsActiveTrueOrderBySequenceAscIdAsc(PRODUCT))
                    .thenReturn(List.of(
                            activeService(TEMPLATE, "Standard SaaS Onboarding", 1),
                            activeService(TEMPLATE + 1, "Enterprise (with data migration audit)", 2)));
            when(templateSteps.findByTemplateIdOrderBySequenceAsc(any())).thenReturn(List.of());
            // The client already runs the service they were boarded on.
            when(journeys.existsByObClientIdAndProductIdAndServiceNameAndArchivedAtIsNull(
                    CLIENT, PRODUCT, "Standard SaaS Onboarding")).thenReturn(true);

            List<ObJourney> created = service.instantiate(CLIENT, PRODUCT);

            assertThat(created).extracting(ObJourney::getServiceName)
                    .containsExactly("Enterprise (with data migration audit)");
        }

        private ObJourneyTemplate activeService(long templateId, String name, int sequence) {
            ObJourneyTemplate t = new ObJourneyTemplate();
            t.setId(templateId);
            t.setProductId(PRODUCT);
            t.setName(name);
            t.setSequence(sequence);
            t.setVersion(1);
            t.setActive(true);
            return t;
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

            ObJourney journey = only(service.instantiate(CLIENT, PRODUCT));

            assertThat(journey.getGateStatus()).isEqualTo(ObGateStatus.OPEN);
            assertThat(journey.getGateOpenedAt()).isNotNull();
            assertThat(journey.getGateOpenedBy()).isNull();
        }

        @Test
        @DisplayName("C-119 · born already OPEN triggers the first-wave activation pass")
        void bornOpenActivatesTheFirstWave() {
            ObJourney priorJourney = new ObJourney();
            priorJourney.setId(1L);
            priorJourney.setObClientId(CLIENT);
            priorJourney.setProductId(PRODUCT + 1);
            priorJourney.setGateStatus(ObGateStatus.OPEN);
            journeyRows.put(1L, priorJourney);
            when(templateSteps.findByTemplateIdOrderBySequenceAsc(TEMPLATE)).thenReturn(List.of());

            ObJourney journey = only(service.instantiate(CLIENT, PRODUCT));

            verify(stepLifecycle).activateEligibleSteps(journey.getId());
        }

        @Test
        @DisplayName("C-119 · born LOCKED triggers no activation pass")
        void bornLockedActivatesNothing() {
            when(templateSteps.findByTemplateIdOrderBySequenceAsc(TEMPLATE)).thenReturn(List.of());

            service.instantiate(CLIENT, PRODUCT);

            verify(stepLifecycle, never()).activateEligibleSteps(anyLong());
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

            ObJourney journey = only(service.instantiate(CLIENT, PRODUCT));
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

            ObJourney journey = only(service.instantiate(CLIENT, PRODUCT));
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

            ObJourney journey = only(service.instantiate(CLIENT, PRODUCT));
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

            ObJourney journey = only(service.instantiate(CLIENT, PRODUCT));
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

    @Nested
    @DisplayName("service-level dependency — C-123, plan §5 item 5")
    class ServiceLevelDependency {

        private static final long DEPENDENCY_PRODUCT = 501L;
        private static final long DEPENDENCY_TEMPLATE = 701L;
        private static final long DEPENDENT_PRODUCT = 502L;
        private static final long DEPENDENT_TEMPLATE = 702L;
        private static final long SECOND_DEPENDENCY_PRODUCT = 503L;
        private static final long SECOND_DEPENDENCY_TEMPLATE = 703L;

        private ObJourneyTemplate stubTemplate(long productId, long templateId, Long... dependsOnTemplateIds) {
            ObJourneyTemplate t = new ObJourneyTemplate();
            t.setId(templateId);
            t.setProductId(productId);
            t.setVersion(1);
            t.setActive(true);
            t.setName("Service of product " + productId);
            lenient().when(templates.findByProductIdAndIsActiveTrueOrderBySequenceAscIdAsc(productId))
                    .thenReturn(List.of(t));
            lenient().when(templates.findById(templateId)).thenReturn(Optional.of(t));
            lenient().when(templateSteps.findByTemplateIdOrderBySequenceAsc(templateId)).thenReturn(List.of());
            // The dependency set now lives in its own table, so it is stubbed
            // on the dependency repository rather than set on the row.
            lenient().when(templateDependencies
                            .findByIdTemplateIdOrderByIdDependsOnTemplateIdAsc(templateId))
                    .thenReturn(java.util.Arrays.stream(dependsOnTemplateIds)
                            .sorted()
                            .map(dependsOn -> new ObJourneyTemplateDependency(templateId, dependsOn))
                            .toList());
            return t;
        }

        @Test
        @DisplayName("instantiates held when the client's dependency journey is still running")
        void heldWhileDependencyRunning() {
            stubTemplate(DEPENDENCY_PRODUCT, DEPENDENCY_TEMPLATE);
            stubTemplate(DEPENDENT_PRODUCT, DEPENDENT_TEMPLATE, DEPENDENCY_TEMPLATE);

            ObJourney dependency = only(service.instantiate(CLIENT, DEPENDENCY_PRODUCT));
            ObJourney dependent = only(service.instantiate(CLIENT, DEPENDENT_PRODUCT));

            assertThat(dependent.getHeldByJourneyId()).isEqualTo(dependency.getId());
        }

        @Test
        @DisplayName("instantiates unheld — vacuous — when the client never bought the dependency's product")
        void vacuousWhenDependencyNeverBought() {
            stubTemplate(DEPENDENCY_PRODUCT, DEPENDENCY_TEMPLATE);
            stubTemplate(DEPENDENT_PRODUCT, DEPENDENT_TEMPLATE, DEPENDENCY_TEMPLATE);

            ObJourney dependent = only(service.instantiate(CLIENT, DEPENDENT_PRODUCT));

            assertThat(dependent.getHeldByJourneyId()).isNull();
        }

        @Test
        @DisplayName("instantiates unheld when the client's dependency journey has already completed")
        void vacuousWhenDependencyCompleted() {
            stubTemplate(DEPENDENCY_PRODUCT, DEPENDENCY_TEMPLATE);
            stubTemplate(DEPENDENT_PRODUCT, DEPENDENT_TEMPLATE, DEPENDENCY_TEMPLATE);

            ObJourney dependency = only(service.instantiate(CLIENT, DEPENDENCY_PRODUCT));
            dependency.setCompletedAt(Instant.now());

            ObJourney dependent = only(service.instantiate(CLIENT, DEPENDENT_PRODUCT));

            assertThat(dependent.getHeldByJourneyId()).isNull();
        }

        @Test
        @DisplayName("a template with no declared dependency never holds, regardless of sibling journeys")
        void noDependencyDeclaredNeverHolds() {
            stubTemplate(DEPENDENCY_PRODUCT, DEPENDENCY_TEMPLATE);
            service.instantiate(CLIENT, DEPENDENCY_PRODUCT);

            stubTemplate(DEPENDENT_PRODUCT, DEPENDENT_TEMPLATE);
            ObJourney dependent = only(service.instantiate(CLIENT, DEPENDENT_PRODUCT));

            assertThat(dependent.getHeldByJourneyId()).isNull();
        }

        @Test
        @DisplayName("with two dependencies, both running journeys are reported and the lower id is held on")
        void holdsBehindTheFirstOfSeveral() {
            stubTemplate(DEPENDENCY_PRODUCT, DEPENDENCY_TEMPLATE);
            stubTemplate(SECOND_DEPENDENCY_PRODUCT, SECOND_DEPENDENCY_TEMPLATE);
            ObJourneyTemplate dependent = stubTemplate(
                    DEPENDENT_PRODUCT, DEPENDENT_TEMPLATE, DEPENDENCY_TEMPLATE, SECOND_DEPENDENCY_TEMPLATE);

            ObJourney first = only(service.instantiate(CLIENT, DEPENDENCY_PRODUCT));
            ObJourney second = only(service.instantiate(CLIENT, SECOND_DEPENDENCY_PRODUCT));
            ObJourney held = only(service.instantiate(CLIENT, DEPENDENT_PRODUCT));

            // `held_by_journey_id` is one column and holds the first
            // outstanding holder — ObJourneyDependencyRelease re-points it at
            // the other when this one completes.
            assertThat(held.getHeldByJourneyId()).isEqualTo(first.getId());
            assertThat(service.holdingJourneysFor(CLIENT, dependent))
                    .containsExactly(first.getId(), second.getId());
        }

        @Test
        @DisplayName("a dependency already finished is skipped, and the unfinished one still holds")
        void skipsTheFinishedDependency() {
            stubTemplate(DEPENDENCY_PRODUCT, DEPENDENCY_TEMPLATE);
            stubTemplate(SECOND_DEPENDENCY_PRODUCT, SECOND_DEPENDENCY_TEMPLATE);
            stubTemplate(DEPENDENT_PRODUCT, DEPENDENT_TEMPLATE,
                    DEPENDENCY_TEMPLATE, SECOND_DEPENDENCY_TEMPLATE);

            ObJourney finished = only(service.instantiate(CLIENT, DEPENDENCY_PRODUCT));
            finished.setCompletedAt(Instant.now());
            ObJourney running = only(service.instantiate(CLIENT, SECOND_DEPENDENCY_PRODUCT));

            ObJourney held = only(service.instantiate(CLIENT, DEPENDENT_PRODUCT));

            assertThat(held.getHeldByJourneyId()).isEqualTo(running.getId());
        }

        @Test
        @DisplayName("instantiateAll orders by template sequence, so the dependency exists before the dependent")
        void instantiateAllOrdersBySequence() {
            ObJourneyTemplate dependency = stubTemplate(DEPENDENCY_PRODUCT, DEPENDENCY_TEMPLATE);
            ObJourneyTemplate dependent = stubTemplate(DEPENDENT_PRODUCT, DEPENDENT_TEMPLATE, DEPENDENCY_TEMPLATE);
            // Lower sequence instantiates first — the catalogue lists a
            // dependency before what depends on it, and instantiation order
            // follows the same number.
            dependency.setSequence(1);
            dependent.setSequence(5);

            // Requested in the "wrong" order — the wizard's own multi-select
            // does not promise one — but the lower-sequence service still
            // instantiates first, so the dependent one finds it and holds.
            List<ObJourney> created =
                    service.instantiateAll(CLIENT, List.of(DEPENDENT_PRODUCT, DEPENDENCY_PRODUCT));

            ObJourney dependencyJourney = created.stream()
                    .filter(j -> j.getProductId().equals(DEPENDENCY_PRODUCT)).findFirst().orElseThrow();
            ObJourney dependentJourney = created.stream()
                    .filter(j -> j.getProductId().equals(DEPENDENT_PRODUCT)).findFirst().orElseThrow();
            assertThat(dependentJourney.getHeldByJourneyId()).isEqualTo(dependencyJourney.getId());
        }
    }
}
