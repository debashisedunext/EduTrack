package com.edunext.edutrack.api.feature.onboarding.journeys;

import com.edunext.edutrack.domain.onboarding.ObJourneyTemplate;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateRepository;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStep;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStepDoc;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStepDocRepository;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * C-101 · {@link ObJourneyTemplateService}. The four repositories are mocked
 * with Mockito, same as every other service test in this codebase
 * ({@code HandoffServiceTest} is the model), but each is backed by a small
 * in-memory map rather than per-call stubs — {@code beginRevision} saves a
 * cloned step and then immediately queries its items and docs by the id
 * just assigned, which a fixed-return-value stub cannot model.
 *
 * <p>The test every other one here is measured against is
 * {@code editingARetiredVersionIsRefused} — the exact failure mode the
 * service's class javadoc warns about: guarding on {@code !isActive}
 * instead of {@code publishedAt == null} would let a superseded version be
 * edited after a new one is published, silently corrupting whatever journey
 * is still pinned to it.
 */
class ObJourneyTemplateServiceTest {

    private static final long PRODUCT = 500L;
    private static final long ADMIN = 7L;

    private final Map<Long, ObJourneyTemplate> templateRows = new LinkedHashMap<>();
    private final Map<Long, ObJourneyTemplateStep> stepRows = new LinkedHashMap<>();
    private final Map<Long, ObJourneyTemplateStepItem> itemRows = new LinkedHashMap<>();
    private final Map<Long, ObJourneyTemplateStepDoc> docRows = new LinkedHashMap<>();

    private final AtomicLong templateIds = new AtomicLong();
    private final AtomicLong stepIds = new AtomicLong();
    private final AtomicLong itemIds = new AtomicLong();
    private final AtomicLong docIds = new AtomicLong();

    private final ObJourneyTemplateRepository templates = mock(ObJourneyTemplateRepository.class);
    private final ObJourneyTemplateStepRepository steps = mock(ObJourneyTemplateStepRepository.class);
    private final ObJourneyTemplateStepItemRepository stepItems = mock(ObJourneyTemplateStepItemRepository.class);
    private final ObJourneyTemplateStepDocRepository stepDocs = mock(ObJourneyTemplateStepDocRepository.class);

    private final ObJourneyTemplateService service =
            new ObJourneyTemplateService(templates, steps, stepItems, stepDocs);

    @BeforeEach
    void wireFakes() {
        lenient().when(templates.save(any())).thenAnswer(inv -> {
            ObJourneyTemplate t = inv.getArgument(0);
            if (t.getId() == null) {
                t.setId(templateIds.incrementAndGet());
            }
            templateRows.put(t.getId(), t);
            return t;
        });
        lenient().when(templates.saveAndFlush(any())).thenAnswer(inv -> templates.save(inv.getArgument(0)));
        lenient().when(templates.findById(any())).thenAnswer(inv -> Optional.ofNullable(templateRows.get(inv.<Long>getArgument(0))));
        lenient().when(templates.existsByProductId(any())).thenAnswer(inv ->
                templateRows.values().stream().anyMatch(t -> t.getProductId().equals(inv.<Long>getArgument(0))));
        lenient().when(templates.findByProductIdAndIsActiveTrue(any())).thenAnswer(inv ->
                templateRows.values().stream()
                        .filter(t -> t.getProductId().equals(inv.<Long>getArgument(0)) && t.isActive())
                        .findFirst());
        lenient().when(templates.findTopByProductIdOrderByVersionDesc(any())).thenAnswer(inv ->
                templateRows.values().stream()
                        .filter(t -> t.getProductId().equals(inv.<Long>getArgument(0)))
                        .max(Comparator.comparingInt(ObJourneyTemplate::getVersion)));

        lenient().when(steps.save(any())).thenAnswer(inv -> {
            ObJourneyTemplateStep s = inv.getArgument(0);
            if (s.getId() == null) {
                s.setId(stepIds.incrementAndGet());
            }
            stepRows.put(s.getId(), s);
            return s;
        });
        lenient().when(steps.findById(any())).thenAnswer(inv -> Optional.ofNullable(stepRows.get(inv.<Long>getArgument(0))));
        lenient().doAnswer(inv -> stepRows.remove(((ObJourneyTemplateStep) inv.getArgument(0)).getId()))
                .when(steps).delete(any());
        lenient().when(steps.findByTemplateIdOrderBySequenceAsc(any())).thenAnswer(inv -> stepsFor(inv.getArgument(0)));
        lenient().when(steps.findTopByTemplateIdOrderBySequenceDesc(any())).thenAnswer(inv ->
                stepsFor(inv.<Long>getArgument(0)).stream().reduce((a, b) -> b));
        lenient().when(steps.countByTemplateId(any())).thenAnswer(inv -> (long) stepsFor(inv.<Long>getArgument(0)).size());
        lenient().when(steps.findByTemplateIdAndDependsOnStepId(any(), any())).thenAnswer(inv -> {
            Long templateId = inv.getArgument(0);
            Long dependsOn = inv.getArgument(1);
            return stepRows.values().stream()
                    .filter(s -> s.getTemplateId().equals(templateId) && dependsOn.equals(s.getDependsOnStepId()))
                    .toList();
        });

        lenient().when(stepItems.save(any())).thenAnswer(inv -> {
            ObJourneyTemplateStepItem i = inv.getArgument(0);
            if (i.getId() == null) {
                i.setId(itemIds.incrementAndGet());
            }
            itemRows.put(i.getId(), i);
            return i;
        });
        lenient().when(stepItems.findById(any())).thenAnswer(inv -> Optional.ofNullable(itemRows.get(inv.<Long>getArgument(0))));
        lenient().doAnswer(inv -> itemRows.remove(((ObJourneyTemplateStepItem) inv.getArgument(0)).getId()))
                .when(stepItems).delete(any());
        lenient().when(stepItems.findByStepIdOrderBySequenceAsc(any())).thenAnswer(inv -> itemsFor(inv.getArgument(0)));
        lenient().when(stepItems.findTopByStepIdOrderBySequenceDesc(any())).thenAnswer(inv ->
                itemsFor(inv.<Long>getArgument(0)).stream().reduce((a, b) -> b));

        lenient().when(stepDocs.save(any())).thenAnswer(inv -> {
            ObJourneyTemplateStepDoc d = inv.getArgument(0);
            if (d.getId() == null) {
                d.setId(docIds.incrementAndGet());
            }
            docRows.put(d.getId(), d);
            return d;
        });
        lenient().when(stepDocs.findById(any())).thenAnswer(inv -> Optional.ofNullable(docRows.get(inv.<Long>getArgument(0))));
        lenient().doAnswer(inv -> docRows.remove(((ObJourneyTemplateStepDoc) inv.getArgument(0)).getId()))
                .when(stepDocs).delete(any());
        lenient().when(stepDocs.findByStepIdOrderBySequenceAsc(any())).thenAnswer(inv -> docsFor(inv.getArgument(0)));
        lenient().when(stepDocs.findTopByStepIdOrderBySequenceDesc(any())).thenAnswer(inv ->
                docsFor(inv.<Long>getArgument(0)).stream().reduce((a, b) -> b));
    }

    private List<ObJourneyTemplateStep> stepsFor(Long templateId) {
        List<ObJourneyTemplateStep> result = new ArrayList<>(stepRows.values().stream()
                .filter(s -> s.getTemplateId().equals(templateId)).toList());
        result.sort(Comparator.comparingInt(ObJourneyTemplateStep::getSequence));
        return result;
    }

    private List<ObJourneyTemplateStepItem> itemsFor(Long stepId) {
        List<ObJourneyTemplateStepItem> result = new ArrayList<>(itemRows.values().stream()
                .filter(i -> i.getStepId().equals(stepId)).toList());
        result.sort(Comparator.comparingInt(ObJourneyTemplateStepItem::getSequence));
        return result;
    }

    private List<ObJourneyTemplateStepDoc> docsFor(Long stepId) {
        List<ObJourneyTemplateStepDoc> result = new ArrayList<>(docRows.values().stream()
                .filter(d -> d.getStepId().equals(stepId)).toList());
        result.sort(Comparator.comparingInt(ObJourneyTemplateStepDoc::getSequence));
        return result;
    }

    @Nested
    @DisplayName("createTemplate — a product's first draft")
    class CreateTemplate {

        @Test
        @DisplayName("first version for a product is a draft, version 1")
        void firstVersionIsADraft() {
            ObJourneyTemplate created = service.createTemplate(PRODUCT, "ERP Rollout", 1, null, ADMIN);

            assertThat(created.getVersion()).isEqualTo(1);
            assertThat(created.isActive()).isFalse();
            assertThat(created.getPublishedAt()).isNull();
            assertThat(created.getProductId()).isEqualTo(PRODUCT);
        }

        @Test
        @DisplayName("a second call for the same product is refused")
        void secondCallRefused() {
            service.createTemplate(PRODUCT, "ERP Rollout", 1, null, ADMIN);

            assertThatThrownBy(() -> service.createTemplate(PRODUCT, "ERP Rollout v2", 1, null, ADMIN))
                    .isInstanceOf(TemplateAlreadyExistsException.class);
        }
    }

    @Nested
    @DisplayName("publish — the version, not the row, changes")
    class Publish {

        @Test
        @DisplayName("a draft with steps publishes and becomes active")
        void publishesADraftWithSteps() {
            ObJourneyTemplate draft = service.createTemplate(PRODUCT, "ERP Rollout", 1, null, ADMIN);
            service.addStep(draft.getId(), "Kickoff", null, 2, null, "PM", null, false, null);

            ObJourneyTemplate published = service.publish(draft.getId(), ADMIN);

            assertThat(published.isActive()).isTrue();
            assertThat(published.getPublishedAt()).isNotNull();
            assertThat(published.getPublishedBy()).isEqualTo(ADMIN);
        }

        @Test
        @DisplayName("a draft with no steps cannot be published")
        void emptyDraftRefused() {
            ObJourneyTemplate draft = service.createTemplate(PRODUCT, "ERP Rollout", 1, null, ADMIN);

            assertThatThrownBy(() -> service.publish(draft.getId(), ADMIN))
                    .isInstanceOf(TemplateHasNoStepsException.class);
        }

        @Test
        @DisplayName("publishing twice is refused — a version publishes exactly once")
        void publishTwiceRefused() {
            ObJourneyTemplate draft = service.createTemplate(PRODUCT, "ERP Rollout", 1, null, ADMIN);
            service.addStep(draft.getId(), "Kickoff", null, 2, null, "PM", null, false, null);
            service.publish(draft.getId(), ADMIN);

            assertThatThrownBy(() -> service.publish(draft.getId(), ADMIN))
                    .isInstanceOf(TemplateAlreadyPublishedException.class);
        }

        @Test
        @DisplayName("publishing a revision retires the version it supersedes")
        void publishingARevisionRetiresThePrevious() {
            ObJourneyTemplate v1 = service.createTemplate(PRODUCT, "ERP Rollout", 1, null, ADMIN);
            service.addStep(v1.getId(), "Kickoff", null, 2, null, "PM", null, false, null);
            service.publish(v1.getId(), ADMIN);

            ObJourneyTemplate v2Draft = service.beginRevision(v1.getId(), ADMIN);
            ObJourneyTemplate v2 = service.publish(v2Draft.getId(), ADMIN);

            ObJourneyTemplate retiredV1 = templates.findById(v1.getId()).orElseThrow();
            assertThat(retiredV1.isActive()).isFalse();
            assertThat(retiredV1.getPublishedAt()).isNotNull(); // still stamped — it WAS published
            assertThat(v2.isActive()).isTrue();
            assertThat(v2.getVersion()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("beginRevision — clone, never mutate, the active version")
    class BeginRevision {

        @Test
        @DisplayName("only the active version may be revised")
        void onlyActiveMayBeRevised() {
            ObJourneyTemplate draft = service.createTemplate(PRODUCT, "ERP Rollout", 1, null, ADMIN);

            assertThatThrownBy(() -> service.beginRevision(draft.getId(), ADMIN))
                    .isInstanceOf(TemplateNotActiveException.class);
        }

        @Test
        @DisplayName("clones steps, items and docs, re-pointing dependsOnStepId at the clones")
        void clonesStepsItemsAndDocs() {
            ObJourneyTemplate v1 = service.createTemplate(PRODUCT, "ERP Rollout", 1, null, ADMIN);
            ObJourneyTemplateStep kickoff =
                    service.addStep(v1.getId(), "Kickoff", "desc", 2, null, "PM", null, false, null);
            service.addStep(v1.getId(), "Data migration", null, 5, null, "DEV", null, true, kickoff.getId());
            service.addStepItem(kickoff.getId(), "Signed requirement sheet received", true);
            service.addStepDoc(kickoff.getId(), "Signed requirement sheet", true);
            service.publish(v1.getId(), ADMIN);

            ObJourneyTemplate v2 = service.beginRevision(v1.getId(), ADMIN);

            List<ObJourneyTemplateStep> v1Steps = steps.findByTemplateIdOrderBySequenceAsc(v1.getId());
            List<ObJourneyTemplateStep> v2Steps = steps.findByTemplateIdOrderBySequenceAsc(v2.getId());
            assertThat(v2Steps).hasSameSizeAs(v1Steps);

            ObJourneyTemplateStep clonedKickoff = v2Steps.get(0);
            ObJourneyTemplateStep clonedMigration = v2Steps.get(1);
            assertThat(clonedKickoff.getId()).isNotEqualTo(kickoff.getId());
            assertThat(clonedMigration.getDependsOnStepId())
                    .as("the clone's dependency points at the CLONED kickoff step, not the original")
                    .isEqualTo(clonedKickoff.getId());

            assertThat(stepItems.findByStepIdOrderBySequenceAsc(clonedKickoff.getId())).hasSize(1);
            assertThat(stepDocs.findByStepIdOrderBySequenceAsc(clonedKickoff.getId())).hasSize(1);

            // The source is untouched — this is the whole point.
            assertThat(steps.findByTemplateIdOrderBySequenceAsc(v1.getId())).isEqualTo(v1Steps);
        }
    }

    @Nested
    @DisplayName("a published version — active or retired — can never be edited again")
    class Immutability {

        @Test
        @DisplayName("editing the currently active version is refused")
        void editingActiveRefused() {
            ObJourneyTemplate v1 = service.createTemplate(PRODUCT, "ERP Rollout", 1, null, ADMIN);
            service.addStep(v1.getId(), "Kickoff", null, 2, null, "PM", null, false, null);
            service.publish(v1.getId(), ADMIN);

            assertThatThrownBy(() ->
                    service.addStep(v1.getId(), "Sneaky extra step", null, 1, null, "PM", null, false, null))
                    .isInstanceOf(TemplateNotEditableException.class);
        }

        @Test
        @DisplayName("editing a RETIRED version — superseded by a later publish — is also refused")
        void editingARetiredVersionIsRefused() {
            ObJourneyTemplate v1 = service.createTemplate(PRODUCT, "ERP Rollout", 1, null, ADMIN);
            service.addStep(v1.getId(), "Kickoff", null, 2, null, "PM", null, false, null);
            service.publish(v1.getId(), ADMIN);

            ObJourneyTemplate v2Draft = service.beginRevision(v1.getId(), ADMIN);
            service.addStep(v2Draft.getId(), "Kickoff (v2)", null, 2, null, "PM", null, false, null);
            service.publish(v2Draft.getId(), ADMIN);

            // v1 is now retired: publishedAt is still set, isActive is now false.
            // A journey instantiated while v1 was active still pins it — this must stay frozen.
            assertThatThrownBy(() ->
                    service.addStep(v1.getId(), "Corrupting a live journey's ribbon", null, 1,
                            null, "PM", null, false, null))
                    .isInstanceOf(TemplateNotEditableException.class);

            long v1KickoffId = steps.findByTemplateIdOrderBySequenceAsc(v1.getId()).get(0).getId();
            assertThatThrownBy(() -> service.removeStep(v1KickoffId))
                    .isInstanceOf(TemplateNotEditableException.class);
        }
    }

    @Nested
    @DisplayName("steps and step items")
    class StepsAndItems {

        @Test
        @DisplayName("steps are sequenced 1, 2, 3, ... in add order")
        void stepsAreSequencedInOrder() {
            ObJourneyTemplate draft = service.createTemplate(PRODUCT, "ERP Rollout", 1, null, ADMIN);
            ObJourneyTemplateStep first = service.addStep(draft.getId(), "A", null, 1, null, null, null, false, null);
            ObJourneyTemplateStep second = service.addStep(draft.getId(), "B", null, 1, null, null, null, false, null);

            assertThat(first.getSequence()).isEqualTo(1);
            assertThat(second.getSequence()).isEqualTo(2);
        }

        @Test
        @DisplayName("deleting a step other steps depend on is refused, naming the dependents")
        void deletingADependedOnStepRefused() {
            ObJourneyTemplate draft = service.createTemplate(PRODUCT, "ERP Rollout", 1, null, ADMIN);
            ObJourneyTemplateStep kickoff = service.addStep(draft.getId(), "Kickoff", null, 1, null, null, null, false, null);
            ObJourneyTemplateStep migration =
                    service.addStep(draft.getId(), "Migration", null, 1, null, null, null, false, kickoff.getId());

            assertThatThrownBy(() -> service.removeStep(kickoff.getId()))
                    .isInstanceOf(StepHasDependentsException.class)
                    .hasMessageContaining(String.valueOf(migration.getId()));
        }

        @Test
        @DisplayName("step items are sequenced independently per step")
        void stepItemsSequencedPerStep() {
            ObJourneyTemplate draft = service.createTemplate(PRODUCT, "ERP Rollout", 1, null, ADMIN);
            ObJourneyTemplateStep step = service.addStep(draft.getId(), "Kickoff", null, 1, null, null, null, false, null);

            service.addStepItem(step.getId(), "Item A", true);
            ObJourneyTemplateStepItem itemB = service.addStepItem(step.getId(), "Item B", true);

            assertThat(itemB.getSequence()).isEqualTo(2);
            assertThat(stepItems.findByStepIdOrderBySequenceAsc(step.getId())).hasSize(2);

            service.removeStepItem(itemB.getId());
            assertThat(stepItems.findByStepIdOrderBySequenceAsc(step.getId())).hasSize(1);
        }

        @Test
        @DisplayName("step docs default to required and can be removed")
        void stepDocsAddAndRemove() {
            ObJourneyTemplate draft = service.createTemplate(PRODUCT, "ERP Rollout", 1, null, ADMIN);
            ObJourneyTemplateStep step = service.addStep(draft.getId(), "Kickoff", null, 1, null, null, null, false, null);

            ObJourneyTemplateStepDoc doc = service.addStepDoc(step.getId(), "Signed requirement sheet", true);
            assertThat(doc.isRequired()).isTrue();

            service.removeStepDoc(doc.getId());
            assertThat(stepDocs.findByStepIdOrderBySequenceAsc(step.getId())).isEmpty();
        }

        @Test
        @DisplayName("an item's mandatory flag is whatever the caller asked for, both ways")
        void mandatoryFlagRoundTrips() {
            ObJourneyTemplate draft = service.createTemplate(PRODUCT, "ERP Rollout", 1, null, ADMIN);
            ObJourneyTemplateStep step = service.addStep(draft.getId(), "Kickoff", null, 1, null, null, null, false, null);

            ObJourneyTemplateStepItem mandatoryItem = service.addStepItem(step.getId(), "Mandatory item", true);
            ObJourneyTemplateStepItem optionalItem = service.addStepItem(step.getId(), "Optional item", false);

            assertThat(mandatoryItem.isMandatory()).isTrue();
            assertThat(optionalItem.isMandatory()).isFalse();
        }
    }

    @Nested
    @DisplayName("beginRevision — the mandatory flag travels with the clone")
    class CloneCarriesMandatoryFlag {

        @Test
        @DisplayName("an item marked optional on the source stays optional on the clone")
        void optionalItemStaysOptionalAfterRevision() {
            ObJourneyTemplate v1 = service.createTemplate(PRODUCT, "ERP Rollout", 1, null, ADMIN);
            ObJourneyTemplateStep kickoff =
                    service.addStep(v1.getId(), "Kickoff", null, 2, null, "PM", null, false, null);
            service.addStepItem(kickoff.getId(), "Mandatory item", true);
            service.addStepItem(kickoff.getId(), "Optional item", false);
            service.publish(v1.getId(), ADMIN);

            ObJourneyTemplate v2 = service.beginRevision(v1.getId(), ADMIN);

            ObJourneyTemplateStep clonedKickoff = steps.findByTemplateIdOrderBySequenceAsc(v2.getId()).get(0);
            List<ObJourneyTemplateStepItem> clonedItems =
                    stepItems.findByStepIdOrderBySequenceAsc(clonedKickoff.getId());

            assertThat(clonedItems).hasSize(2);
            assertThat(clonedItems)
                    .as("the clone's mandatory flags are not silently reset to the column default")
                    .extracting(ObJourneyTemplateStepItem::isMandatory)
                    .containsExactly(true, false);
        }
    }

    @Nested
    @DisplayName("reorderSteps — the OB-07 ↑/↓ control")
    class Reorder {

        @Test
        @DisplayName("persists the caller's exact ordering as sequence 1..N")
        void persistsRequestedOrder() {
            ObJourneyTemplate draft = service.createTemplate(PRODUCT, "ERP Rollout", 1, null, ADMIN);
            ObJourneyTemplateStep a = service.addStep(draft.getId(), "A", null, 1, null, null, null, false, null);
            ObJourneyTemplateStep b = service.addStep(draft.getId(), "B", null, 1, null, null, null, false, null);
            ObJourneyTemplateStep c = service.addStep(draft.getId(), "C", null, 1, null, null, null, false, null);

            service.reorderSteps(draft.getId(), List.of(c.getId(), a.getId(), b.getId()));

            List<ObJourneyTemplateStep> reordered = steps.findByTemplateIdOrderBySequenceAsc(draft.getId());
            assertThat(reordered).extracting(ObJourneyTemplateStep::getId)
                    .containsExactly(c.getId(), a.getId(), b.getId());
            assertThat(reordered).extracting(ObJourneyTemplateStep::getSequence)
                    .containsExactly(1, 2, 3);
        }

        @Test
        @DisplayName("swapping two adjacent steps' positions does not trip the (template_id, sequence) unique index")
        void adjacentSwapDoesNotCollide() {
            // The collision case named in the service's own javadoc: writing
            // step A's new sequence to what step B currently holds, before B
            // has been moved off it, would violate
            // uq_ob_journey_template_steps_seq under a real unique index. An
            // in-memory fake cannot enforce that constraint, so this test
            // proves the two-pass shape ran (final state is the swap) rather
            // than proving MySQL accepted it — that half is CI's job against
            // a real database.
            ObJourneyTemplate draft = service.createTemplate(PRODUCT, "ERP Rollout", 1, null, ADMIN);
            ObJourneyTemplateStep first = service.addStep(draft.getId(), "First", null, 1, null, null, null, false, null);
            ObJourneyTemplateStep second = service.addStep(draft.getId(), "Second", null, 1, null, null, null, false, null);

            service.reorderSteps(draft.getId(), List.of(second.getId(), first.getId()));

            List<ObJourneyTemplateStep> reordered = steps.findByTemplateIdOrderBySequenceAsc(draft.getId());
            assertThat(reordered).extracting(ObJourneyTemplateStep::getId)
                    .containsExactly(second.getId(), first.getId());
            assertThat(reordered).extracting(ObJourneyTemplateStep::getSequence)
                    .containsExactly(1, 2);
        }

        @Test
        @DisplayName("a list missing a step is refused")
        void missingStepRefused() {
            ObJourneyTemplate draft = service.createTemplate(PRODUCT, "ERP Rollout", 1, null, ADMIN);
            ObJourneyTemplateStep a = service.addStep(draft.getId(), "A", null, 1, null, null, null, false, null);
            service.addStep(draft.getId(), "B", null, 1, null, null, null, false, null);

            assertThatThrownBy(() -> service.reorderSteps(draft.getId(), List.of(a.getId())))
                    .isInstanceOf(StepReorderMismatchException.class);
        }

        @Test
        @DisplayName("a list naming an id twice is refused")
        void duplicateIdRefused() {
            ObJourneyTemplate draft = service.createTemplate(PRODUCT, "ERP Rollout", 1, null, ADMIN);
            ObJourneyTemplateStep a = service.addStep(draft.getId(), "A", null, 1, null, null, null, false, null);
            ObJourneyTemplateStep b = service.addStep(draft.getId(), "B", null, 1, null, null, null, false, null);

            assertThatThrownBy(() -> service.reorderSteps(draft.getId(), List.of(a.getId(), a.getId())))
                    .isInstanceOf(StepReorderMismatchException.class);

            // Refused before anything is written — b's original sequence still stands.
            assertThat(steps.findById(b.getId()).orElseThrow().getSequence()).isEqualTo(2);
        }

        @Test
        @DisplayName("a list naming a step from a different template is refused")
        void foreignStepRefused() {
            ObJourneyTemplate draftOne = service.createTemplate(PRODUCT, "ERP Rollout", 1, null, ADMIN);
            ObJourneyTemplateStep a = service.addStep(draftOne.getId(), "A", null, 1, null, null, null, false, null);
            ObJourneyTemplate draftTwo = service.createTemplate(600L, "Biometric Rollout", 1, null, ADMIN);
            ObJourneyTemplateStep x = service.addStep(draftTwo.getId(), "X", null, 1, null, null, null, false, null);

            assertThatThrownBy(() -> service.reorderSteps(draftOne.getId(), List.of(x.getId())))
                    .isInstanceOf(StepReorderMismatchException.class);
            assertThatThrownBy(() -> service.reorderSteps(draftOne.getId(), List.of(a.getId(), x.getId())))
                    .isInstanceOf(StepReorderMismatchException.class);
        }

        @Test
        @DisplayName("a published template cannot be reordered")
        void publishedTemplateRefused() {
            ObJourneyTemplate draft = service.createTemplate(PRODUCT, "ERP Rollout", 1, null, ADMIN);
            ObJourneyTemplateStep a = service.addStep(draft.getId(), "A", null, 1, null, null, null, false, null);
            service.publish(draft.getId(), ADMIN);

            assertThatThrownBy(() -> service.reorderSteps(draft.getId(), List.of(a.getId())))
                    .isInstanceOf(TemplateNotEditableException.class);
        }
    }

    @Nested
    @DisplayName("parallelGroups — the computed layering OB-07 renders as concurrent groups")
    class ParallelGroups {

        @Test
        @DisplayName("every step with no dependency is layer 0, all in one group")
        void allParallelIsOneGroup() {
            ObJourneyTemplate draft = service.createTemplate(PRODUCT, "ERP Rollout", 1, null, ADMIN);
            ObJourneyTemplateStep a = service.addStep(draft.getId(), "A", null, 1, null, null, null, false, null);
            ObJourneyTemplateStep b = service.addStep(draft.getId(), "B", null, 1, null, null, null, false, null);

            List<List<ObJourneyTemplateStep>> groups = service.parallelGroups(draft.getId());

            assertThat(groups).hasSize(1);
            assertThat(groups.get(0)).extracting(ObJourneyTemplateStep::getId)
                    .containsExactlyInAnyOrder(a.getId(), b.getId());
        }

        @Test
        @DisplayName("a straight chain is one step per layer")
        void chainIsOnePerLayer() {
            ObJourneyTemplate draft = service.createTemplate(PRODUCT, "ERP Rollout", 1, null, ADMIN);
            ObJourneyTemplateStep a = service.addStep(draft.getId(), "A", null, 1, null, null, null, false, null);
            ObJourneyTemplateStep b = service.addStep(draft.getId(), "B", null, 1, null, null, null, false, a.getId());
            ObJourneyTemplateStep c = service.addStep(draft.getId(), "C", null, 1, null, null, null, false, b.getId());

            List<List<ObJourneyTemplateStep>> groups = service.parallelGroups(draft.getId());

            assertThat(groups).hasSize(3);
            assertThat(groups.get(0)).extracting(ObJourneyTemplateStep::getId).containsExactly(a.getId());
            assertThat(groups.get(1)).extracting(ObJourneyTemplateStep::getId).containsExactly(b.getId());
            assertThat(groups.get(2)).extracting(ObJourneyTemplateStep::getId).containsExactly(c.getId());
        }

        @Test
        @DisplayName("a fork — two steps both depending on the same root — land in the same later layer")
        void forkLandsSiblingsInSameLayer() {
            ObJourneyTemplate draft = service.createTemplate(PRODUCT, "ERP Rollout", 1, null, ADMIN);
            ObJourneyTemplateStep kickoff =
                    service.addStep(draft.getId(), "Kickoff", null, 1, null, null, null, false, null);
            ObJourneyTemplateStep migration =
                    service.addStep(draft.getId(), "Migration", null, 1, null, null, null, false, kickoff.getId());
            ObJourneyTemplateStep training =
                    service.addStep(draft.getId(), "Training", null, 1, null, null, null, false, kickoff.getId());

            List<List<ObJourneyTemplateStep>> groups = service.parallelGroups(draft.getId());

            assertThat(groups).hasSize(2);
            assertThat(groups.get(0)).extracting(ObJourneyTemplateStep::getId).containsExactly(kickoff.getId());
            assertThat(groups.get(1)).extracting(ObJourneyTemplateStep::getId)
                    .containsExactlyInAnyOrder(migration.getId(), training.getId());
        }

        @Test
        @DisplayName("a multi-layer mix of chains and forks layers by longest path from a root")
        void mixedGraphLayersByLongestPath() {
            ObJourneyTemplate draft = service.createTemplate(PRODUCT, "ERP Rollout", 1, null, ADMIN);
            ObJourneyTemplateStep root =
                    service.addStep(draft.getId(), "Root", null, 1, null, null, null, false, null);
            ObJourneyTemplateStep branchA =
                    service.addStep(draft.getId(), "BranchA", null, 1, null, null, null, false, root.getId());
            ObJourneyTemplateStep branchAChild =
                    service.addStep(draft.getId(), "BranchAChild", null, 1, null, null, null, false, branchA.getId());
            ObJourneyTemplateStep branchB =
                    service.addStep(draft.getId(), "BranchB", null, 1, null, null, null, false, root.getId());

            List<List<ObJourneyTemplateStep>> groups = service.parallelGroups(draft.getId());

            assertThat(groups).hasSize(3);
            assertThat(groups.get(0)).extracting(ObJourneyTemplateStep::getId).containsExactly(root.getId());
            assertThat(groups.get(1)).extracting(ObJourneyTemplateStep::getId)
                    .containsExactlyInAnyOrder(branchA.getId(), branchB.getId());
            assertThat(groups.get(2)).extracting(ObJourneyTemplateStep::getId)
                    .containsExactly(branchAChild.getId());
        }

        @Test
        @DisplayName("an empty template has no groups")
        void emptyTemplateHasNoGroups() {
            ObJourneyTemplate draft = service.createTemplate(PRODUCT, "ERP Rollout", 1, null, ADMIN);

            assertThat(service.parallelGroups(draft.getId())).isEmpty();
        }
    }
}
