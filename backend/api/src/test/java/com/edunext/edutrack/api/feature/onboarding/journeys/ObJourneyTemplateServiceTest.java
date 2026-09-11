package com.edunext.edutrack.api.feature.onboarding.journeys;

import com.edunext.edutrack.domain.onboarding.ObJourneyTemplate;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateDependency;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateDependencyId;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateDependencyRepository;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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

    /**
     * C-124 · journeys instantiated per template version — {@code ob_journeys}
     * as far as this service is concerned. It reads the count through
     * {@code ObJourneyTemplateRepository}, never through
     * {@code ObJourneyRepository}, which {@code ScopeGuardRulesTest} forbids
     * feature code from touching at all.
     */
    private final Map<Long, Long> journeysByTemplate = new LinkedHashMap<>();

    /**
     * C-124 · the {@code service_name} those journeys carry, per template
     * version — {@code ob_journeys}' denormalised copy of the service name,
     * which a rename has to re-stamp or leave two lookups resolving a name
     * nothing holds any more.
     */
    private final Map<Long, String> journeyServiceNameByTemplate = new LinkedHashMap<>();

    private final AtomicLong templateIds = new AtomicLong();
    private final AtomicLong stepIds = new AtomicLong();
    private final AtomicLong itemIds = new AtomicLong();
    private final AtomicLong docIds = new AtomicLong();

    /**
     * The service dependency graph — {@code ob_journey_template_dependencies}
     * as far as this service is concerned, since {@code V20260911_1100} moved
     * it off the template row. A {@code LinkedHashSet} of the pair, because
     * the pair is the table's primary key: a fake that let the same edge in
     * twice would hide exactly the duplicate {@code replaceEdges} collapses.
     */
    private final Set<ObJourneyTemplateDependencyId> dependencyRows = new LinkedHashSet<>();

    private final ObJourneyTemplateRepository templates = mock(ObJourneyTemplateRepository.class);
    private final ObJourneyTemplateDependencyRepository dependencies =
            mock(ObJourneyTemplateDependencyRepository.class);
    private final ObJourneyTemplateStepRepository steps = mock(ObJourneyTemplateStepRepository.class);
    private final ObJourneyTemplateStepItemRepository stepItems = mock(ObJourneyTemplateStepItemRepository.class);
    private final ObJourneyTemplateStepDocRepository stepDocs = mock(ObJourneyTemplateStepDocRepository.class);

    private final ObJourneyTemplateService service =
            new ObJourneyTemplateService(templates, dependencies, steps, stepItems, stepDocs);

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
        lenient().when(templates.existsById(any())).thenAnswer(inv -> templateRows.containsKey(inv.<Long>getArgument(0)));
        lenient().when(templates.existsByProductId(any())).thenAnswer(inv ->
                templateRows.values().stream().anyMatch(t -> t.getProductId().equals(inv.<Long>getArgument(0))));
        // Keyed on the service, not the product: a fake that still answered
        // per product would make publish's own scoping untestable —
        // publishing one service would look like it had retired another and
        // nothing here would notice.
        lenient().when(templates.findByProductIdAndNameAndIsActiveTrue(any(), any())).thenAnswer(inv ->
                templateRows.values().stream()
                        .filter(t -> t.getProductId().equals(inv.<Long>getArgument(0))
                                && inv.<String>getArgument(1).equals(t.getName())
                                && t.isActive())
                        .findFirst());
        lenient().when(templates.findTopByProductIdOrderByVersionDesc(any())).thenAnswer(inv ->
                templateRows.values().stream()
                        .filter(t -> t.getProductId().equals(inv.<Long>getArgument(0)))
                        .max(Comparator.comparingInt(ObJourneyTemplate::getVersion)));
        lenient().when(templates.findByIsActiveTrueOrderBySequenceAsc()).thenAnswer(inv ->
                templateRows.values().stream()
                        .filter(ObJourneyTemplate::isActive)
                        .sorted(Comparator.comparingInt(ObJourneyTemplate::getSequence))
                        .toList());

        /*
          C-124 · the chain, its dependents, and the usage tally. Backed by the
          same in-memory maps as the rest — journeysByTemplate stands in for
          ob_journeys, which this service never touches directly.
        */
        lenient().when(templates.findByProductIdAndNameOrderByVersionAsc(any(), any())).thenAnswer(inv ->
                templateRows.values().stream()
                        .filter(t -> t.getProductId().equals(inv.<Long>getArgument(0))
                                && inv.<String>getArgument(1).equals(t.getName()))
                        .sorted(Comparator.comparingInt(ObJourneyTemplate::getVersion))
                        .toList());
        lenient().when(templates.findByIdIn(any())).thenAnswer(inv -> {
            java.util.Collection<Long> ids = inv.getArgument(0);
            return templateRows.values().stream().filter(t -> ids.contains(t.getId())).toList();
        });

        /*
          The dependency graph, backed by `dependencyRows`. Every read is
          derived from that one set rather than stubbed per test, so an edge
          written through `updateDependsOn` is visible to the cycle walk that
          runs on the next call — which is the whole behaviour under test, and
          is precisely what per-test stubbing would fake away.
        */
        lenient().when(dependencies.save(any())).thenAnswer(inv -> {
            ObJourneyTemplateDependency edge = inv.getArgument(0);
            dependencyRows.add(edge.getId());
            return edge;
        });
        lenient().when(dependencies.findByIdTemplateIdOrderByIdDependsOnTemplateIdAsc(any()))
                .thenAnswer(inv -> {
                    Long templateId = inv.getArgument(0);
                    return dependencyRows.stream()
                            .filter(id -> id.getTemplateId().equals(templateId))
                            .sorted(Comparator.comparing(ObJourneyTemplateDependencyId::getDependsOnTemplateId))
                            .map(id -> new ObJourneyTemplateDependency(
                                    id.getTemplateId(), id.getDependsOnTemplateId()))
                            .toList();
                });
        lenient().when(dependencies.findByIdTemplateIdIn(any())).thenAnswer(inv -> {
            java.util.Collection<Long> ids = inv.getArgument(0);
            return dependencyRows.stream()
                    .filter(id -> ids.contains(id.getTemplateId()))
                    .map(id -> new ObJourneyTemplateDependency(
                            id.getTemplateId(), id.getDependsOnTemplateId()))
                    .toList();
        });
        lenient().when(dependencies.findByIdDependsOnTemplateIdIn(any())).thenAnswer(inv -> {
            java.util.Collection<Long> ids = inv.getArgument(0);
            return dependencyRows.stream()
                    .filter(id -> ids.contains(id.getDependsOnTemplateId()))
                    .map(id -> new ObJourneyTemplateDependency(
                            id.getTemplateId(), id.getDependsOnTemplateId()))
                    .toList();
        });
        lenient().doAnswer(inv -> {
            java.util.Collection<Long> ids = inv.getArgument(0);
            dependencyRows.removeIf(id -> ids.contains(id.getTemplateId()));
            return null;
        }).when(dependencies).deleteByIdTemplateIdIn(any());
        lenient().when(templates.countJourneysForTemplates(any())).thenAnswer(inv -> {
            java.util.Collection<Long> ids = inv.getArgument(0);
            return ids.stream().mapToLong(id -> journeysByTemplate.getOrDefault(id, 0L)).sum();
        });
        lenient().when(templates.renameServiceOnJourneys(any(), any())).thenAnswer(inv -> {
            String name = inv.getArgument(0);
            java.util.Collection<Long> ids = inv.getArgument(1);
            int stamped = 0;
            for (Long id : ids) {
                long onThisVersion = journeysByTemplate.getOrDefault(id, 0L);
                if (onThisVersion > 0) {
                    journeyServiceNameByTemplate.put(id, name);
                    stamped += (int) onThisVersion;
                }
            }
            return stamped;
        });
        lenient().when(templates.countJourneysByTemplate(any())).thenAnswer(inv -> {
            java.util.Collection<Long> ids = inv.getArgument(0);
            // Absent rather than zero for a version nothing was boarded against,
            // which is what a GROUP BY actually returns.
            return ids.stream()
                    .filter(journeysByTemplate::containsKey)
                    .map(id -> tally(id, journeysByTemplate.get(id)))
                    .toList();
        });
        lenient().doAnswer(inv -> {
            for (ObJourneyTemplate t : inv.<Iterable<ObJourneyTemplate>>getArgument(0)) {
                templateRows.remove(t.getId());
            }
            return null;
        }).when(templates).deleteAll(any());
        lenient().doAnswer(inv -> {
            for (ObJourneyTemplateStep step : inv.<Iterable<ObJourneyTemplateStep>>getArgument(0)) {
                stepRows.remove(step.getId());
            }
            return null;
        }).when(steps).deleteAll(any());

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

        /**
         * The rule this replaces refused every second service on a product,
         * which the design never asked for — its own catalogue puts "Standard
         * SaaS Onboarding" and "Enterprise (with data migration audit)" side
         * by side under one product. The refusal existed because
         * {@code uq_ob_journey_templates_version} was keyed on
         * {@code (product_id, version)} and every new service starts at v1;
         * {@code V20260909_1900} re-keys it to include the name.
         */
        @Test
        @DisplayName("a product may sell several named services, each starting at v1")
        void secondServiceIsAllowed() {
            ObJourneyTemplate first = service.createTemplate(PRODUCT, "Standard SaaS Onboarding", 1, null, ADMIN);
            ObJourneyTemplate second = service.createTemplate(
                    PRODUCT, "Enterprise (with data migration audit)", 2, null, ADMIN);

            assertThat(first.getVersion()).isEqualTo(1);
            assertThat(second.getVersion()).isEqualTo(1);
            assertThat(second.getProductId()).isEqualTo(PRODUCT);
            assertThat(second.getId()).isNotEqualTo(first.getId());
        }

        /**
         * Neither is active on creation, so the "one active per product" rule
         * the index still holds is never in question until somebody publishes.
         */
        @Test
        @DisplayName("neither service is active until one is published")
        void neitherIsActiveOnCreate() {
            service.createTemplate(PRODUCT, "Standard SaaS Onboarding", 1, null, ADMIN);
            ObJourneyTemplate second = service.createTemplate(PRODUCT, "Enterprise", 2, null, ADMIN);

            assertThat(second.isActive()).isFalse();
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
        @DisplayName("C-119 · a dependsOnStepId naming an unknown step is refused")
        void addStepRefusesAnUnknownDependsOnStepId() {
            ObJourneyTemplate draft = service.createTemplate(PRODUCT, "ERP Rollout", 1, null, ADMIN);

            assertThatThrownBy(() ->
                    service.addStep(draft.getId(), "Migration", null, 1, null, null, null, false, 404L))
                    .isInstanceOf(StepNotFoundException.class);
        }

        @Test
        @DisplayName("C-119 · a dependsOnStepId naming a step of a different template is refused")
        void addStepRefusesADependsOnStepIdFromAnotherTemplate() {
            ObJourneyTemplate draftOne = service.createTemplate(PRODUCT, "ERP Rollout", 1, null, ADMIN);
            ObJourneyTemplate draftTwo = service.createTemplate(PRODUCT + 1, "Payroll Rollout", 1, null, ADMIN);
            ObJourneyTemplateStep foreign =
                    service.addStep(draftTwo.getId(), "Kickoff", null, 1, null, null, null, false, null);

            assertThatThrownBy(() ->
                    service.addStep(draftOne.getId(), "Migration", null, 1, null, null, null, false, foreign.getId()))
                    .isInstanceOf(StepNotFoundException.class);
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

    /** Bypasses createTemplate/publish — an active row with no steps, for the two C-123 nested groups below. */
    /** One version of a named service under {@link #PRODUCT}. */
    private ObJourneyTemplate version(String name, int version, boolean active) {
        ObJourneyTemplate t = new ObJourneyTemplate();
        t.setProductId(PRODUCT);
        t.setName(name);
        t.setVersion(version);
        t.setActive(active);
        t.setSequence(0);
        return templates.save(t);
    }

    /**
     * Board {@code count} clients on one template version, each journey
     * carrying that version's service name as {@code ob_journeys} denormalises
     * it at instantiation.
     */
    private void journeysOn(long templateId, long count) {
        journeysByTemplate.put(templateId, count);
        journeyServiceNameByTemplate.put(templateId, templateRows.get(templateId).getName());
    }

    /** The grouped-count projection, as an anonymous implementation. */
    private static ObJourneyTemplateRepository.TemplateTally tally(Long templateId, long count) {
        return new ObJourneyTemplateRepository.TemplateTally() {
            @Override
            public Long getTemplateId() {
                return templateId;
            }

            @Override
            public long getTally() {
                return count;
            }
        };
    }

    private ObJourneyTemplate activeTemplate(long productId, int sequence, Long... dependsOnTemplateIds) {
        ObJourneyTemplate t = new ObJourneyTemplate();
        t.setProductId(productId);
        t.setName("Product " + productId);
        t.setVersion(1);
        t.setActive(true);
        t.setSequence(sequence);
        ObJourneyTemplate saved = templates.save(t);
        /*
          Written straight into the graph rather than through
          `updateDependsOn`. A fixture that used the method under test to build
          its own preconditions could not set up a graph the method refuses —
          and the cycle tests below exist to reach exactly those.
        */
        for (Long dependsOn : dependsOnTemplateIds) {
            dependencyRows.add(new ObJourneyTemplateDependencyId(saved.getId(), dependsOn));
        }
        return saved;
    }

    /** What {@code templateId} waits behind, ascending — the picker's own read. */
    private List<Long> dependsOn(long templateId) {
        return service.dependsOnTemplateIds(templateId);
    }

    @Nested
    @DisplayName("updateDependsOn — C-123's cycle-free multi-select picker")
    class UpdateDependsOn {

        @Test
        @DisplayName("names a valid dependency")
        void namesAValidDependency() {
            ObJourneyTemplate erp = activeTemplate(1, 0);
            ObJourneyTemplate biometric = activeTemplate(2, 1);

            service.updateDependsOn(biometric.getId(), List.of(erp.getId()));

            assertThat(dependsOn(biometric.getId())).containsExactly(erp.getId());
        }

        @Test
        @DisplayName("a service can wait behind several others at once")
        void severalDependenciesAtOnce() {
            ObJourneyTemplate erp = activeTemplate(1, 0);
            ObJourneyTemplate network = activeTemplate(2, 1);
            ObJourneyTemplate biometric = activeTemplate(3, 2);

            service.updateDependsOn(biometric.getId(), List.of(erp.getId(), network.getId()));

            assertThat(dependsOn(biometric.getId()))
                    .containsExactlyInAnyOrder(erp.getId(), network.getId());
        }

        @Test
        @DisplayName("the list is the whole set, so dropping one leaves the others")
        void replacesRatherThanAdds() {
            ObJourneyTemplate erp = activeTemplate(1, 0);
            ObJourneyTemplate network = activeTemplate(2, 1);
            ObJourneyTemplate survey = activeTemplate(3, 2);
            ObJourneyTemplate biometric = activeTemplate(4, 3, erp.getId(), network.getId());

            service.updateDependsOn(biometric.getId(), List.of(network.getId(), survey.getId()));

            assertThat(dependsOn(biometric.getId()))
                    .containsExactlyInAnyOrder(network.getId(), survey.getId());
        }

        @Test
        @DisplayName("re-sending an unchanged set is accepted, not a duplicate-key failure")
        void resendingTheSameSetIsIdempotent() {
            // The picker sends its whole selection on every change, so most
            // saves re-declare edges the template already has. Without the
            // flush between the delete and the inserts this is the call that
            // collides on the (template_id, depends_on_template_id) key.
            ObJourneyTemplate erp = activeTemplate(1, 0);
            ObJourneyTemplate biometric = activeTemplate(2, 1, erp.getId());

            service.updateDependsOn(biometric.getId(), List.of(erp.getId()));

            assertThat(dependsOn(biometric.getId())).containsExactly(erp.getId());
        }

        @Test
        @DisplayName("a repeated id in one request is one edge, not a refusal")
        void duplicatesInTheRequestCollapse() {
            ObJourneyTemplate erp = activeTemplate(1, 0);
            ObJourneyTemplate biometric = activeTemplate(2, 1);

            service.updateDependsOn(biometric.getId(), List.of(erp.getId(), erp.getId()));

            assertThat(dependsOn(biometric.getId())).containsExactly(erp.getId());
        }

        @Test
        @DisplayName("an empty list clears every dependency")
        void emptyListClears() {
            ObJourneyTemplate erp = activeTemplate(1, 0);
            ObJourneyTemplate network = activeTemplate(2, 1);
            ObJourneyTemplate biometric = activeTemplate(3, 2, erp.getId(), network.getId());

            service.updateDependsOn(biometric.getId(), List.of());

            assertThat(dependsOn(biometric.getId())).isEmpty();
        }

        @Test
        @DisplayName("a template cannot depend on itself")
        void directSelfCycleRefused() {
            ObJourneyTemplate erp = activeTemplate(1, 0);

            assertThatThrownBy(() -> service.updateDependsOn(erp.getId(), List.of(erp.getId())))
                    .isInstanceOf(TemplateDependencyCycleException.class);
        }

        @Test
        @DisplayName("a transitive cycle is refused, not just a direct one")
        void transitiveCycleRefused() {
            // A -> B -> C already. Pointing C back at A would close the loop.
            ObJourneyTemplate a = activeTemplate(1, 0);
            ObJourneyTemplate b = activeTemplate(2, 1, a.getId());
            ObJourneyTemplate c = activeTemplate(3, 2, b.getId());

            assertThatThrownBy(() -> service.updateDependsOn(a.getId(), List.of(c.getId())))
                    .isInstanceOf(TemplateDependencyCycleException.class);

            // C depending on B (already true) is not itself a cycle to be
            // refused a second, unrelated time.
            service.updateDependsOn(c.getId(), List.of(b.getId()));
            assertThat(dependsOn(c.getId())).containsExactly(b.getId());
        }

        @Test
        @DisplayName("a cycle down the second branch of a fork is refused")
        void cycleThroughTheSecondBranchRefused() {
            /*
              This is the case a chain walk cannot see, and the reason
              requireNoCycle is a breadth-first search rather than a cursor.

                  fork ──▶ harmless
                       └─▶ middle ──▶ a

              Pointing `a` at `fork` closes a → fork → middle → a. A walk that
              followed only the first edge out of each node would reach
              `harmless`, run out, and report success.
            */
            ObJourneyTemplate a = activeTemplate(1, 0);
            ObJourneyTemplate middle = activeTemplate(2, 1, a.getId());
            ObJourneyTemplate harmless = activeTemplate(3, 2);
            ObJourneyTemplate fork = activeTemplate(4, 3, harmless.getId(), middle.getId());

            assertThatThrownBy(() -> service.updateDependsOn(a.getId(), List.of(fork.getId())))
                    .isInstanceOf(TemplateDependencyCycleException.class);
        }

        @Test
        @DisplayName("a legal id alongside a cycling one writes nothing at all")
        void oneBadIdRejectsTheWholeSet() {
            // Validated in full before anything is written, so the outcome
            // does not depend on which order the picker sent the ids in.
            ObJourneyTemplate a = activeTemplate(1, 0);
            ObJourneyTemplate dependent = activeTemplate(2, 1, a.getId());
            ObJourneyTemplate innocent = activeTemplate(3, 2);

            assertThatThrownBy(() -> service.updateDependsOn(
                    a.getId(), List.of(innocent.getId(), dependent.getId())))
                    .isInstanceOf(TemplateDependencyCycleException.class);

            assertThat(dependsOn(a.getId())).isEmpty();
        }

        @Test
        @DisplayName("naming an unknown template is refused, not silently accepted")
        void unknownDependencyRefused() {
            ObJourneyTemplate erp = activeTemplate(1, 0);

            assertThatThrownBy(() -> service.updateDependsOn(erp.getId(), List.of(999L)))
                    .isInstanceOf(TemplateNotFoundException.class);
        }

        @Test
        @DisplayName("a revision inherits the whole dependency set, not the first of it")
        void revisionClonesTheWholeSet() {
            ObJourneyTemplate erp = activeTemplate(1, 0);
            ObJourneyTemplate network = activeTemplate(2, 1);
            ObJourneyTemplate biometric = activeTemplate(3, 2, erp.getId(), network.getId());

            ObJourneyTemplate draft = service.beginRevision(biometric.getId(), 7L);

            assertThat(dependsOn(draft.getId()))
                    .containsExactlyInAnyOrder(erp.getId(), network.getId());
            // And the source is untouched — the draft holds its own rows.
            assertThat(dependsOn(biometric.getId()))
                    .containsExactlyInAnyOrder(erp.getId(), network.getId());
        }
    }

    @Nested
    @DisplayName("reorderCatalogue — the Module Service page's up/down control")
    class ReorderCatalogue {

        @Test
        @DisplayName("renumbers every active template 0..N-1 in the caller's order")
        void renumbers() {
            ObJourneyTemplate erp = activeTemplate(1, 0);
            ObJourneyTemplate biometric = activeTemplate(2, 1);
            ObJourneyTemplate lms = activeTemplate(3, 2);

            service.reorderCatalogue(List.of(lms.getId(), erp.getId(), biometric.getId()));

            assertThat(templateRows.get(lms.getId()).getSequence()).isZero();
            assertThat(templateRows.get(erp.getId()).getSequence()).isEqualTo(1);
            assertThat(templateRows.get(biometric.getId()).getSequence()).isEqualTo(2);
        }

        @Test
        @DisplayName("a product with only a draft template is not part of the reorder")
        void draftOnlyTemplateExcluded() {
            ObJourneyTemplate erp = activeTemplate(1, 0);
            ObJourneyTemplate biometric = activeTemplate(2, 1);
            ObJourneyTemplate draftOnly = service.createTemplate(3, "New Service", 0, null, ADMIN);

            // draftOnly's product has never had an active version, so it is
            // neither required in the list nor touched by it.
            service.reorderCatalogue(List.of(biometric.getId(), erp.getId()));

            assertThat(templateRows.get(draftOnly.getId()).isActive()).isFalse();
            assertThat(templateRows.get(draftOnly.getId()).getSequence()).isZero();
        }

        @Test
        @DisplayName("a repeated id is refused")
        void duplicateRefused() {
            ObJourneyTemplate erp = activeTemplate(1, 0);
            ObJourneyTemplate biometric = activeTemplate(2, 1);

            assertThatThrownBy(() -> service.reorderCatalogue(List.of(erp.getId(), erp.getId())))
                    .isInstanceOf(CatalogueReorderMismatchException.class);
        }

        @Test
        @DisplayName("a set that is not exactly the active catalogue is refused")
        void mismatchedSetRefused() {
            ObJourneyTemplate erp = activeTemplate(1, 0);
            activeTemplate(2, 1);

            assertThatThrownBy(() -> service.reorderCatalogue(List.of(erp.getId())))
                    .isInstanceOf(CatalogueReorderMismatchException.class);
        }
    }

    /**
     * C-124 · editing and deleting a whole Module Service.
     *
     * <p>The measure of this block is {@code renameMovesEveryVersionOfTheChain}
     * and {@code aClientOnARetiredVersionStillLocksTheService}. Both describe
     * the same mistake from two sides: treating the row named in the call as
     * the service. A rename that moved one row would split a chain in two; a
     * usage check that asked only about the head would report a service as
     * unused while a client is boarded on its v1, and let the delete through.
     */
    @Nested
    @DisplayName("editing and deleting a Module Service")
    class ModuleServiceAdmin {

        @Test
        @DisplayName("a rename moves every version of the chain, not the row that was named")
        void renameMovesEveryVersionOfTheChain() {
            ObJourneyTemplate v1 = version("Standard SaaS Onboarding", 1, true);
            ObJourneyTemplate v2 = version("Standard SaaS Onboarding", 2, false);
            ObJourneyTemplate other = version("Enterprise", 1, false);

            service.updateModuleService(v2.getId(), "Standard onboarding", null);

            assertThat(templateRows.get(v1.getId()).getName()).isEqualTo("Standard onboarding");
            assertThat(templateRows.get(v2.getId()).getName()).isEqualTo("Standard onboarding");
            // A different service under the same product is untouched — the
            // chain is (productId, name), not the product.
            assertThat(templateRows.get(other.getId()).getName()).isEqualTo("Enterprise");
        }

        @Test
        @DisplayName("a rename can move the service to another product")
        void renameCanRepointTheProduct() {
            ObJourneyTemplate v1 = version("Standard SaaS Onboarding", 1, true);

            service.updateModuleService(v1.getId(), "Standard SaaS Onboarding", 999L);

            assertThat(templateRows.get(v1.getId()).getProductId()).isEqualTo(999L);
        }

        @Test
        @DisplayName("renaming to a name the product already sells is refused")
        void duplicateNameRefused() {
            version("Standard SaaS Onboarding", 1, true);
            ObJourneyTemplate enterprise = version("Enterprise", 1, false);

            assertThatThrownBy(() ->
                    service.updateModuleService(enterprise.getId(), "Standard SaaS Onboarding", null))
                    .isInstanceOf(DuplicateModuleServiceNameException.class);
        }

        @Test
        @DisplayName("renaming to the name it already has writes nothing and is not a collision")
        void renamingToItsOwnNameIsANoOp() {
            ObJourneyTemplate v1 = version("Standard SaaS Onboarding", 1, true);

            ObJourneyTemplate result = service.updateModuleService(
                    v1.getId(), "Standard SaaS Onboarding", null);

            assertThat(result.getName()).isEqualTo("Standard SaaS Onboarding");
        }

        @Test
        @DisplayName("a published service nobody has bought can still be renamed")
        void publishedButUnusedIsRenameable() {
            ObJourneyTemplate v1 = version("Standard SaaS Onboarding", 1, true);
            v1.setPublishedAt(Instant.parse("2026-09-01T09:00:00Z"));
            templates.save(v1);

            service.updateModuleService(v1.getId(), "Standard onboarding", null);

            // Deliberately not requireEditable: a name is catalogue metadata,
            // not the journey content publishing freezes.
            assertThat(templateRows.get(v1.getId()).getName()).isEqualTo("Standard onboarding");
        }

        /**
         * The rule this screen turns on, and the one that changed: a service 49
         * clients are on is the one most worth being able to correct, not the
         * one to freeze. Nothing about being in use gates an edit any more —
         * only {@code deleteModuleService} still asks.
         */
        @Test
        @DisplayName("a rename goes through however many clients are on the service")
        void renameAllowedWhileInUse() {
            ObJourneyTemplate v1 = version("Standard SaaS Onboarding", 1, true);
            journeysOn(v1.getId(), 3);

            service.updateModuleService(v1.getId(), "Standard onboarding", null);

            assertThat(templateRows.get(v1.getId()).getName()).isEqualTo("Standard onboarding");
        }

        /**
         * The half a rename would be wrong without. {@code ob_journeys}
         * denormalises {@code service_name}, and both
         * {@code uq_ob_journeys_client_service} and the dependency hold resolve
         * a service by it — left holding the old string, a dependent journey
         * starts unheld and a client can be boarded twice onto one service.
         */
        @Test
        @DisplayName("a rename re-stamps the journeys boarded on every version of the chain")
        void renameCarriesItsJourneysWithIt() {
            ObJourneyTemplate v1 = version("Standard SaaS Onboarding", 1, false);
            ObJourneyTemplate v2 = version("Standard SaaS Onboarding", 2, true);
            ObJourneyTemplate other = version("Enterprise", 1, true);
            journeysOn(v1.getId(), 2);
            journeysOn(v2.getId(), 1);
            journeysOn(other.getId(), 1);

            service.updateModuleService(v2.getId(), "Standard onboarding", null);

            assertThat(journeyServiceNameByTemplate.get(v1.getId())).isEqualTo("Standard onboarding");
            assertThat(journeyServiceNameByTemplate.get(v2.getId())).isEqualTo("Standard onboarding");
            // Another service's journeys are no business of this rename.
            assertThat(journeyServiceNameByTemplate.get(other.getId())).isEqualTo("Enterprise");
        }

        @Test
        @DisplayName("a rename to the same name re-stamps nothing")
        void renameToTheSameNameTouchesNoJourney() {
            ObJourneyTemplate v1 = version("Standard SaaS Onboarding", 1, true);
            journeysOn(v1.getId(), 3);

            service.updateModuleService(v1.getId(), "Standard SaaS Onboarding", null);

            verify(templates, never()).renameServiceOnJourneys(any(), any());
        }

        @Test
        @DisplayName("the product can be re-filed too, however many clients are on it")
        void productMoveAllowedWhileInUse() {
            ObJourneyTemplate v1 = version("Standard SaaS Onboarding", 1, true);
            journeysOn(v1.getId(), 3);

            service.updateModuleService(v1.getId(), "Standard SaaS Onboarding", 999L);

            assertThat(templateRows.get(v1.getId()).getProductId()).isEqualTo(999L);
        }

        /**
         * The half that deliberately does <em>not</em> travel. A journey's
         * product is half of {@code fk_ob_journeys_application} — the client's
         * own purchase — so re-stamping it would claim a sale that never
         * happened. The templates move; the journeys keep recording what was
         * actually bought.
         */
        @Test
        @DisplayName("a move re-files the templates without touching what the client bought")
        void productMoveLeavesTheJourneysWhereTheyWereBought() {
            ObJourneyTemplate v1 = version("Standard SaaS Onboarding", 1, true);
            journeysOn(v1.getId(), 3);

            service.updateModuleService(v1.getId(), "Standard onboarding", 999L);

            assertThat(templateRows.get(v1.getId()).getProductId()).isEqualTo(999L);
            // The rename still reaches them; only the product does not.
            assertThat(journeyServiceNameByTemplate.get(v1.getId())).isEqualTo("Standard onboarding");
        }

        @Test
        @DisplayName("both fields move in one call, on a service clients are on")
        void renameAndMoveTogetherWhileInUse() {
            ObJourneyTemplate v1 = version("Standard SaaS Onboarding", 1, false);
            ObJourneyTemplate v2 = version("Standard SaaS Onboarding", 2, true);
            journeysOn(v1.getId(), 2);

            service.updateModuleService(v2.getId(), "Standard onboarding", 999L);

            assertThat(templateRows.get(v1.getId()).getName()).isEqualTo("Standard onboarding");
            assertThat(templateRows.get(v1.getId()).getProductId()).isEqualTo(999L);
            assertThat(templateRows.get(v2.getId()).getName()).isEqualTo("Standard onboarding");
            assertThat(templateRows.get(v2.getId()).getProductId()).isEqualTo(999L);
        }

        @Test
        @DisplayName("a delete removes every version of the service, with its steps")
        void deleteRemovesTheWholeChain() {
            ObJourneyTemplate v1 = version("Standard SaaS Onboarding", 1, false);
            ObJourneyTemplate v2 = version("Standard SaaS Onboarding", 2, true);
            ObJourneyTemplate survivor = version("Enterprise", 1, false);
            service.addStep(v1.getId(), "Kickoff", null, 3, null, "PM", null, false, null);
            service.addStep(v2.getId(), "Kickoff", null, 3, null, "PM", null, false, null);

            service.deleteModuleService(v2.getId());

            assertThat(templateRows).containsOnlyKeys(survivor.getId());
            assertThat(stepRows).isEmpty();
        }

        /**
         * The ordering {@code V20260903_1420} chose RESTRICT over CASCADE to
         * force. A delete that removed steps in load order would leave a step
         * something still points at, which the composite self-FK refuses.
         */
        @Test
        @DisplayName("steps are removed dependents-first, so a dependency chain deletes cleanly")
        void chainedStepsDeleteInDependencyOrder() {
            ObJourneyTemplate v1 = version("Standard SaaS Onboarding", 1, true);
            ObJourneyTemplateStep first =
                    service.addStep(v1.getId(), "Kickoff", null, 3, null, "PM", null, false, null);
            ObJourneyTemplateStep second = service.addStep(
                    v1.getId(), "Provisioning", null, 4, null, "DEPLOYMENT", null, false, first.getId());
            service.addStep(v1.getId(), "Migration", null, 8, null, "DEVELOPER", null, false, second.getId());

            service.deleteModuleService(v1.getId());

            assertThat(stepRows).isEmpty();
        }

        @Test
        @DisplayName("a delete is refused while another service depends on it, naming it")
        void deleteRefusedWhileDependedOn() {
            ObJourneyTemplate standard = version("Standard SaaS Onboarding", 1, true);
            ObJourneyTemplate enterprise = version("Enterprise", 1, true);
            service.updateDependsOn(enterprise.getId(), List.of(standard.getId()));

            assertThatThrownBy(() -> service.deleteModuleService(standard.getId()))
                    .isInstanceOf(ModuleServiceHasDependentsException.class)
                    .hasMessageContaining("Enterprise");
        }

        @Test
        @DisplayName("a delete is refused once a client is on the service")
        void deleteRefusedWhileInUse() {
            ObJourneyTemplate v1 = version("Standard SaaS Onboarding", 1, true);
            journeysOn(v1.getId(), 1);

            assertThatThrownBy(() -> service.deleteModuleService(v1.getId()))
                    .isInstanceOf(ModuleServiceInUseException.class)
                    .hasMessageContaining("1 client journey has");
            assertThat(templateRows).containsKey(v1.getId());
        }

        /**
         * The case a per-row count gets wrong. The client sits on v1 while the
         * catalogue card draws v2, so a check against the row that was clicked
         * would find nothing and delete a service somebody is being onboarded
         * through.
         */
        @Test
        @DisplayName("a client on a retired version still locks the service")
        void aClientOnARetiredVersionStillLocksTheService() {
            ObJourneyTemplate v1 = version("Standard SaaS Onboarding", 1, false);
            ObJourneyTemplate v2 = version("Standard SaaS Onboarding", 2, true);
            journeysOn(v1.getId(), 2);

            assertThatThrownBy(() -> service.deleteModuleService(v2.getId()))
                    .isInstanceOf(ModuleServiceInUseException.class);
        }

        @Test
        @DisplayName("the catalogue tally is chain-wide, so every version reports its service's total")
        void journeyCountsAreChainWide() {
            ObJourneyTemplate v1 = version("Standard SaaS Onboarding", 1, false);
            ObJourneyTemplate v2 = version("Standard SaaS Onboarding", 2, true);
            ObJourneyTemplate other = version("Enterprise", 1, true);
            journeysOn(v1.getId(), 2);

            Map<Long, Long> counts = service.journeyCountsByTemplate(
                    List.of(v1, v2, other));

            assertThat(counts.get(v1.getId())).isEqualTo(2L);
            // v2 carries none of its own; the card drawn from it must still
            // report the service as in use.
            assertThat(counts.get(v2.getId())).isEqualTo(2L);
            assertThat(counts.get(other.getId())).isZero();
        }
    }

}
