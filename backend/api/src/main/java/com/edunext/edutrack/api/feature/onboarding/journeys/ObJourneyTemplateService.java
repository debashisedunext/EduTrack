package com.edunext.edutrack.api.feature.onboarding.journeys;

import com.edunext.edutrack.domain.onboarding.ObJourneyTemplate;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateDependency;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateDependencyRepository;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateRepository;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStep;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStepDoc;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStepDocRepository;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStepItem;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStepItemRepository;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStepRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * C-101 · Module Service (journey template) domain and versioning.
 *
 * <h2>The one rule everything else here serves</h2>
 *
 * <p><b>An admin edit never mutates an in-flight journey.</b> Plan §5.1: a
 * template is versioned by copy — editing publishes a new version, and a
 * journey pins the exact version it was instantiated from (C-103). So once a
 * version has been published, this service refuses to touch it again for
 * the rest of its life, active or retired — see
 * {@link ObJourneyTemplate}'s own javadoc on why the test is
 * {@code publishedAt == null}, not {@code !isActive}.
 *
 * <h2>The three moves</h2>
 *
 * <ol>
 *   <li>{@link #createTemplate} — a brand-new product's first draft.</li>
 *   <li>{@link #beginRevision} — clones the active version into a new,
 *       editable draft; the source is untouched.</li>
 *   <li>Every {@code addStep}/{@code removeStep}/{@code addStepItem}/… call —
 *       mutates a draft only, refused on anything already published.</li>
 *   <li>{@link #publish} — the draft becomes the active version; the version
 *       it supersedes (if any) is retired in the same transaction.</li>
 * </ol>
 *
 * <p>Cross-template dependency cycles ({@code ObJourneyTemplateDependency}) are out
 * of scope here — the migration's own comments assign that to C-123, since
 * it is not expressible as a database constraint. The "earlier step in this
 * template" rule on {@code dependsOnStepId} is C-119's: {@link #addStep}
 * refuses a {@code dependsOnStepId} that does not name a step of this same
 * template, and needs no other check — see that method's own javadoc for
 * why "earlier" needs nothing further. This service otherwise only guards
 * what deleting or revising could corrupt: a dangling dependency, or a
 * published row edited in place.
 */
@Service
public class ObJourneyTemplateService {

    private final ObJourneyTemplateRepository templates;
    private final ObJourneyTemplateDependencyRepository dependencies;
    private final ObJourneyTemplateStepRepository steps;
    private final ObJourneyTemplateStepItemRepository stepItems;
    private final ObJourneyTemplateStepDocRepository stepDocs;

    public ObJourneyTemplateService(ObJourneyTemplateRepository templates,
                                     ObJourneyTemplateDependencyRepository dependencies,
                                     ObJourneyTemplateStepRepository steps,
                                     ObJourneyTemplateStepItemRepository stepItems,
                                     ObJourneyTemplateStepDocRepository stepDocs) {
        this.templates = templates;
        this.dependencies = dependencies;
        this.steps = steps;
        this.stepItems = stepItems;
        this.stepDocs = stepDocs;
    }

    /**
     * "+ Create module service" (OB-07) — the way a new service is born.
     *
     * <p><b>A product may sell several named services</b>, one of them active:
     * the design's own catalogue has "Standard SaaS Onboarding" and
     * "Enterprise (with data migration audit)" side by side under one product.
     * This used to refuse the second of them — {@code TemplateAlreadyExistsException},
     * on any existing row — and the refusal was never the rule anybody wanted.
     * It was forced by {@code uq_ob_journey_templates_version (product_id,
     * version)}: every new service starts at v1, and the first service's own
     * v1 is still on the table because retired versions are kept. That index is
     * re-keyed to {@code (product_id, name, version)} by
     * {@code V20260909_1900}, so a second service starting at v1 is now a row
     * the database accepts.
     *
     * <p>What is <em>not</em> relaxed: one <b>active</b> service per product,
     * which {@code uq_ob_journey_templates_active} still enforces and which the
     * design agrees with. A new service is created inactive, as it always was;
     * publishing it is what retires whichever service was active before.
     *
     * <p>A duplicate name within one product is refused by the index rather
     * than by a check here — two services a picker cannot tell apart is the
     * collision worth having the database hold, and it is the same name that
     * now identifies a version chain.
     */
    @Transactional
    public ObJourneyTemplate createTemplate(long productId, String name, int sequence,
                                             List<Long> dependsOnTemplateIds, long createdBy) {
        ObJourneyTemplate template = new ObJourneyTemplate();
        template.setProductId(productId);
        template.setName(name);
        template.setVersion(1);
        template.setActive(false);
        template.setSequence(sequence);
        template.setCreatedBy(createdBy);
        ObJourneyTemplate saved = templates.save(template);
        /*
          After the save, not before: a dependency row names this template by
          id and there is no id until the insert has happened. `replaceEdges`
          runs its full validation anyway rather than trusting a create to be
          safe — the existence check is the half that matters here, since a
          brand-new row has no dependents and so cannot be in a cycle.
        */
        templates.flush();
        replaceEdges(saved.getId(), dependsOnTemplateIds);
        return saved;
    }

    /**
     * Clones the product's currently active template — steps, step items and
     * step documents, {@code dependsOnStepId} re-pointed to the matching
     * clone — into a brand-new draft one version higher. The source row is
     * read, never written; every in-flight journey pinned to it keeps
     * rendering exactly what it always has.
     */
    @Transactional
    public ObJourneyTemplate beginRevision(long templateId, long editorUserId) {
        ObJourneyTemplate active = templates.findById(templateId)
                .orElseThrow(() -> new TemplateNotFoundException(templateId));
        if (!active.isActive()) {
            throw new TemplateNotActiveException(templateId);
        }

        /*
          Numbered within this service, not across the product.

          It used to read MAX(version) for the whole product, which was the
          only safe answer while `uq_..._version` was keyed on
          (product_id, version) — but it means revising "Enterprise" while
          "Standard SaaS Onboarding" sits at v4 produces "Enterprise v5", a
          version number counting somebody else's edits. V20260909_1900 re-keys
          the index to (product_id, name, version), so the chain this revision
          belongs to is the one sharing its name.
        */
        int nextVersion = templates
                .findTopByProductIdAndNameOrderByVersionDesc(active.getProductId(), active.getName())
                .map(ObJourneyTemplate::getVersion)
                .orElse(active.getVersion())
                + 1;

        ObJourneyTemplate draft = new ObJourneyTemplate();
        draft.setProductId(active.getProductId());
        draft.setName(active.getName());
        draft.setVersion(nextVersion);
        draft.setActive(false);
        draft.setSequence(active.getSequence());
        draft.setCreatedBy(editorUserId);
        ObJourneyTemplate savedDraft = templates.save(draft);
        templates.flush();

        /*
          The dependency set is cloned onto the draft, exactly as the single
          `dependsOnTemplateId` used to be copied across. Cloned rather than
          shared: the draft is a separate template version and the picker can
          be pointed at something else while it is still a draft, which must
          not reach back and change what the active version waits for.

          Not routed through `replaceEdges`: the source's set is already known
          cycle-free, and re-validating it would fail the revision rather than
          the edit that introduced a problem, if one ever could.
        */
        for (ObJourneyTemplateDependency edge
                : dependencies.findByIdTemplateIdOrderByIdDependsOnTemplateIdAsc(active.getId())) {
            dependencies.save(new ObJourneyTemplateDependency(
                    savedDraft.getId(), edge.getDependsOnTemplateId()));
        }

        cloneSteps(active.getId(), savedDraft.getId());
        return savedDraft;
    }

    private void cloneSteps(long sourceTemplateId, long targetTemplateId) {
        List<ObJourneyTemplateStep> sourceSteps = steps.findByTemplateIdOrderBySequenceAsc(sourceTemplateId);

        // First pass: clone every step without depends_on_step_id, since the
        // target ids a later step might point at do not exist yet.
        Map<Long, Long> sourceToClonedStepId = new LinkedHashMap<>();
        for (ObJourneyTemplateStep source : sourceSteps) {
            ObJourneyTemplateStep clone = new ObJourneyTemplateStep();
            clone.setTemplateId(targetTemplateId);
            clone.setSequence(source.getSequence());
            clone.setName(source.getName());
            clone.setDescription(source.getDescription());
            clone.setTatDays(source.getTatDays());
            clone.setOwnerUserId(source.getOwnerUserId());
            clone.setOwnerRole(source.getOwnerRole());
            clone.setBackupOwnerUserId(source.getBackupOwnerUserId());
            clone.setRequiresSignoff(source.isRequiresSignoff());
            ObJourneyTemplateStep savedClone = steps.save(clone);
            sourceToClonedStepId.put(source.getId(), savedClone.getId());

            for (ObJourneyTemplateStepItem item : stepItems.findByStepIdOrderBySequenceAsc(source.getId())) {
                ObJourneyTemplateStepItem itemClone = new ObJourneyTemplateStepItem();
                itemClone.setStepId(savedClone.getId());
                itemClone.setSequence(item.getSequence());
                itemClone.setLabel(item.getLabel());
                // C-102: carried forward explicitly rather than left to the
                // column default. The default (true) happens to match most
                // items, which is exactly the trap — a revision that cloned
                // an item somebody had deliberately marked optional would
                // silently make it mandatory again, and nothing about a
                // clone-on-revise would surface that regression to whoever
                // requested it.
                itemClone.setMandatory(item.isMandatory());
                stepItems.save(itemClone);
            }

            for (ObJourneyTemplateStepDoc doc : stepDocs.findByStepIdOrderBySequenceAsc(source.getId())) {
                ObJourneyTemplateStepDoc docClone = new ObJourneyTemplateStepDoc();
                docClone.setStepId(savedClone.getId());
                docClone.setSequence(doc.getSequence());
                docClone.setLabel(doc.getLabel());
                docClone.setRequired(doc.isRequired());
                stepDocs.save(docClone);
            }
        }

        // Second pass: every clone now has an id, so depends_on_step_id can
        // be re-pointed at the clone of whatever the source step pointed at.
        for (ObJourneyTemplateStep source : sourceSteps) {
            if (source.getDependsOnStepId() == null) {
                continue;
            }
            Long clonedId = sourceToClonedStepId.get(source.getId());
            Long clonedDependsOn = sourceToClonedStepId.get(source.getDependsOnStepId());
            ObJourneyTemplateStep clone = steps.findById(clonedId)
                    .orElseThrow(() -> new IllegalStateException(
                            "step " + clonedId + " was just cloned from " + source.getId()
                                    + " and has vanished mid-revision"));
            clone.setDependsOnStepId(clonedDependsOn);
        }
    }

    /**
     * @throws StepNotFoundException C-119 · {@code dependsOnStepId} is not
     *         null and does not name a step of this same template — the
     *         composite FK would refuse it anyway, but as a raw constraint
     *         violation rather than a clean refusal. "Earlier step" needs no
     *         separate check beyond this: a new step always receives the
     *         highest sequence in the template ({@link #nextStepSequence}),
     *         so any step that already exists in it is earlier by
     *         construction, and no step can depend on one that does not
     *         exist yet — a cycle is therefore structurally unreachable
     *         through this method.
     */
    @Transactional
    public ObJourneyTemplateStep addStep(long templateId, String name, String description, int tatDays,
                                          Long ownerUserId, String ownerRole, Long backupOwnerUserId,
                                          boolean requiresSignoff, Long dependsOnStepId) {
        ObJourneyTemplate template = requireEditable(templateId);
        if (dependsOnStepId != null) {
            steps.findById(dependsOnStepId)
                    .filter(dependency -> dependency.getTemplateId().equals(template.getId()))
                    .orElseThrow(() -> new StepNotFoundException(dependsOnStepId));
        }

        ObJourneyTemplateStep step = new ObJourneyTemplateStep();
        step.setTemplateId(template.getId());
        step.setSequence(nextStepSequence(templateId));
        step.setName(name);
        step.setDescription(description);
        step.setTatDays(tatDays);
        step.setOwnerUserId(ownerUserId);
        step.setOwnerRole(ownerRole);
        step.setBackupOwnerUserId(backupOwnerUserId);
        step.setRequiresSignoff(requiresSignoff);
        step.setDependsOnStepId(dependsOnStepId);
        return steps.save(step);
    }

    @Transactional
    public void removeStep(long stepId) {
        ObJourneyTemplateStep step = steps.findById(stepId)
                .orElseThrow(() -> new StepNotFoundException(stepId));
        requireEditable(step.getTemplateId());

        List<Long> dependents = steps.findByTemplateIdAndDependsOnStepId(step.getTemplateId(), stepId)
                .stream().map(ObJourneyTemplateStep::getId).toList();
        if (!dependents.isEmpty()) {
            throw new StepHasDependentsException(stepId, dependents);
        }
        steps.delete(step);
    }

    /**
     * C-102 · the OB-07 ↑/↓ control (plan §5.5's ordering language, applied
     * to services rather than templates). Persists the caller's exact
     * ordering as {@code sequence} 1..N — validated to be exactly the
     * template's current step set, no missing id, no extra id, no id named
     * twice, so a partial or malformed reorder can never leave the table
     * short of a row or with two steps sharing a position.
     *
     * <p><b>Two passes, not one, and this is not a style choice.</b> A
     * single pass that writes each step's final {@code sequence} in list
     * order can ask MySQL to set some step to a value another, not-yet-
     * updated step still holds — swapping positions 1 and 2 is the minimal
     * case — and {@code uq_ob_journey_template_steps_seq (template_id,
     * sequence)} refuses that mid-transaction collision even though the two
     * writes never conflict once both have landed. The first pass moves
     * every step to a negative, mutually distinct placeholder (disjoint from
     * every real 1..N value and from each other), flushed before the second
     * pass writes the real 1..N sequence — so no UPDATE this method issues
     * ever asks the unique index to hold two rows at the same value at once.
     */
    @Transactional
    public void reorderSteps(long templateId, List<Long> orderedStepIds) {
        requireEditable(templateId);

        List<ObJourneyTemplateStep> current = steps.findByTemplateIdOrderBySequenceAsc(templateId);
        Set<Long> currentIds = new LinkedHashSet<>();
        for (ObJourneyTemplateStep step : current) {
            currentIds.add(step.getId());
        }

        Set<Long> requestedIds = new LinkedHashSet<>(orderedStepIds);
        if (requestedIds.size() != orderedStepIds.size()) {
            throw new StepReorderMismatchException(templateId,
                    "the same step id appears more than once");
        }
        if (!requestedIds.equals(currentIds)) {
            throw new StepReorderMismatchException(templateId,
                    "the given ids are not exactly this template's current step set");
        }

        Map<Long, ObJourneyTemplateStep> byId = new HashMap<>();
        for (ObJourneyTemplateStep step : current) {
            byId.put(step.getId(), step);
        }

        // Pass 1: every step to a negative, distinct placeholder. See the
        // javadoc above — this is what keeps pass 2 collision-free.
        int placeholder = 1;
        for (Long stepId : orderedStepIds) {
            ObJourneyTemplateStep step = byId.get(stepId);
            step.setSequence(-placeholder);
            steps.save(step);
            placeholder++;
        }
        steps.flush();

        // Pass 2: the real ordering, 1..N in the caller's requested order.
        int sequence = 1;
        for (Long stepId : orderedStepIds) {
            ObJourneyTemplateStep step = byId.get(stepId);
            step.setSequence(sequence);
            steps.save(step);
            sequence++;
        }
        steps.flush();
    }

    /**
     * C-102 · the read-only view OB-07 needs to render "which services could
     * run at once" — plan §5.6's parallel-step language, computed rather
     * than stored because nothing new is being recorded here, only a
     * topological layering of {@code dependsOnStepId} the table already
     * carries.
     *
     * <p><b>Layer rule: layer(step) = 0 when {@code dependsOnStepId} is
     * null, else 1 + layer(the step it depends on).</b> That is longest-path
     * layering from every root, not "any earlier layer" — the simpler
     * alternative of using the dependency's layer unchanged would put a step
     * in the same group as a dependency it cannot possibly run alongside.
     * Layer N is therefore exactly "every step whose entire dependency chain
     * is N hops deep", which is what the designer means by a parallel group:
     * everything in it could be in progress at the same moment.
     *
     * @throws IllegalStateException if a step's {@code dependsOnStepId}
     *         cannot be resolved inside this template, or if the dependency
     *         chain cycles. The composite FK ({@code template_id,
     *         depends_on_step_id}) and C-119's earlier-step rule are what is
     *         supposed to make both impossible — this is a guard against
     *         that assumption being wrong, not a case the caller is expected
     *         to trigger, so it fails loud rather than returning a
     *         plausible-looking but wrong layering.
     */
    @Transactional(readOnly = true)
    public List<List<ObJourneyTemplateStep>> parallelGroups(long templateId) {
        List<ObJourneyTemplateStep> allSteps = steps.findByTemplateIdOrderBySequenceAsc(templateId);

        Map<Long, ObJourneyTemplateStep> byId = new HashMap<>();
        for (ObJourneyTemplateStep step : allSteps) {
            byId.put(step.getId(), step);
        }

        Map<Long, Integer> layerOf = new HashMap<>();
        for (ObJourneyTemplateStep step : allSteps) {
            layerOf.put(step.getId(), layerOfStep(step, byId, layerOf, new LinkedHashSet<>()));
        }

        int maxLayer = -1;
        for (int layer : layerOf.values()) {
            maxLayer = Math.max(maxLayer, layer);
        }

        List<List<ObJourneyTemplateStep>> groups = new ArrayList<>();
        for (int layer = 0; layer <= maxLayer; layer++) {
            List<ObJourneyTemplateStep> group = new ArrayList<>();
            for (ObJourneyTemplateStep step : allSteps) {
                if (layerOf.get(step.getId()) == layer) {
                    group.add(step);
                }
            }
            groups.add(group);
        }
        return groups;
    }

    private int layerOfStep(ObJourneyTemplateStep step, Map<Long, ObJourneyTemplateStep> byId,
                             Map<Long, Integer> memo, Set<Long> visiting) {
        Long stepId = step.getId();
        Integer memoized = memo.get(stepId);
        if (memoized != null) {
            return memoized;
        }
        if (!visiting.add(stepId)) {
            throw new IllegalStateException("dependency cycle detected at journey template step "
                    + stepId + " while computing parallel groups for template " + step.getTemplateId()
                    + " — the composite FK and C-119's earlier-step rule should make a cycle "
                    + "impossible, so something upstream let one through");
        }

        int layer;
        Long dependsOnStepId = step.getDependsOnStepId();
        if (dependsOnStepId == null) {
            layer = 0;
        } else {
            ObJourneyTemplateStep dependency = byId.get(dependsOnStepId);
            if (dependency == null) {
                throw new IllegalStateException("journey template step " + stepId + " depends on step "
                        + dependsOnStepId + ", which is not part of template " + step.getTemplateId()
                        + " — the composite FK should make this impossible");
            }
            layer = 1 + layerOfStep(dependency, byId, memo, visiting);
        }

        visiting.remove(stepId);
        memo.put(stepId, layer);
        return layer;
    }

    @Transactional
    public ObJourneyTemplateStepItem addStepItem(long stepId, String label, boolean mandatory) {
        ObJourneyTemplateStep step = requireStep(stepId);
        requireEditable(step.getTemplateId());

        ObJourneyTemplateStepItem item = new ObJourneyTemplateStepItem();
        item.setStepId(stepId);
        item.setSequence(nextItemSequence(stepId));
        item.setLabel(label);
        item.setMandatory(mandatory);
        return stepItems.save(item);
    }

    @Transactional
    public void removeStepItem(long itemId) {
        ObJourneyTemplateStepItem item = stepItems.findById(itemId)
                .orElseThrow(() -> new StepItemNotFoundException(itemId));
        requireEditable(requireStep(item.getStepId()).getTemplateId());
        stepItems.delete(item);
    }

    @Transactional
    public ObJourneyTemplateStepDoc addStepDoc(long stepId, String label, boolean required) {
        ObJourneyTemplateStep step = requireStep(stepId);
        requireEditable(step.getTemplateId());

        ObJourneyTemplateStepDoc doc = new ObJourneyTemplateStepDoc();
        doc.setStepId(stepId);
        doc.setSequence(nextDocSequence(stepId));
        doc.setLabel(label);
        doc.setRequired(required);
        return stepDocs.save(doc);
    }

    @Transactional
    public void removeStepDoc(long docId) {
        ObJourneyTemplateStepDoc doc = stepDocs.findById(docId)
                .orElseThrow(() -> new StepDocNotFoundException(docId));
        requireEditable(requireStep(doc.getStepId()).getTemplateId());
        stepDocs.delete(doc);
    }

    /**
     * The draft becomes the active version <b>of its own service</b>. If
     * another version of that service is currently active it is retired
     * first — in the same transaction, and in that order — so
     * {@code uq_ob_journey_templates_service_active} never sees two at once.
     *
     * <p><b>Scoped to the service since {@code V20260910_0030}, and the
     * previous scope was the bug that migration exists to fix.</b> Retiring
     * by product meant publishing "Enterprise (with data migration audit)"
     * switched off "Standard SaaS Onboarding" — no error, no warning, and
     * every client boarded afterwards silently got the wrong journey. The
     * two are separate pieces of work sold together, not alternatives.
     */
    @Transactional
    public ObJourneyTemplate publish(long templateId, long publishedBy) {
        ObJourneyTemplate draft = templates.findById(templateId)
                .orElseThrow(() -> new TemplateNotFoundException(templateId));
        if (draft.getPublishedAt() != null) {
            throw new TemplateAlreadyPublishedException(templateId);
        }
        if (steps.countByTemplateId(templateId) == 0) {
            throw new TemplateHasNoStepsException(templateId);
        }

        templates.findByProductIdAndNameAndIsActiveTrue(draft.getProductId(), draft.getName())
                .ifPresent(current -> {
                    current.setActive(false);
                    templates.saveAndFlush(current);
                });

        draft.setActive(true);
        draft.setPublishedBy(publishedBy);
        draft.setPublishedAt(Instant.now());
        return templates.saveAndFlush(draft);
    }

    /**
     * C-102 · the OB-07 detail read. Package-private repository access
     * elsewhere in this service stays private; these four are the read-only
     * surface {@code ObJourneyTemplateController} composes into one response
     * so the controller never holds a repository reference of its own.
     */
    @Transactional(readOnly = true)
    public ObJourneyTemplate getTemplate(long templateId) {
        return templates.findById(templateId).orElseThrow(() -> new TemplateNotFoundException(templateId));
    }

    @Transactional(readOnly = true)
    public List<ObJourneyTemplateStep> getSteps(long templateId) {
        return steps.findByTemplateIdOrderBySequenceAsc(templateId);
    }

    /**
     * The OB-07 catalogue's rows — every service, or every service one product
     * sells, newest version of each first.
     *
     * <p><b>All versions, not only the active one.</b> The caller decides what
     * to draw: the catalogue shows the head of each service's chain, while an
     * admin looking at history wants the retired rows too. Filtering here would
     * make the second question unanswerable without a second endpoint.
     *
     * <p>Ordered by name then version so a service's own chain is contiguous
     * and its newest version leads — the grouping the page renders, done in SQL
     * rather than re-derived in three places.
     */
    @Transactional(readOnly = true)
    public List<ObJourneyTemplate> listTemplates(Long productId) {
        return productId == null
                ? templates.findAll(Sort.by("productId").and(Sort.by("name")).and(Sort.by(Sort.Direction.DESC, "version")))
                : templates.findByProductIdOrderByNameAscVersionDesc(productId);
    }

    /** Σ of a service's step TATs — what a journey for it costs, on the card. */
    @Transactional(readOnly = true)
    public int totalTatDays(long templateId) {
        return steps.findByTemplateIdOrderBySequenceAsc(templateId).stream()
                .mapToInt(ObJourneyTemplateStep::getTatDays)
                .sum();
    }

    /** How many services this step count belongs to — the card's "N services". */
    @Transactional(readOnly = true)
    public int stepCount(long templateId) {
        return (int) steps.countByTemplateId(templateId);
    }

    @Transactional(readOnly = true)
    public List<ObJourneyTemplateStepItem> getStepItems(long stepId) {
        return stepItems.findByStepIdOrderBySequenceAsc(stepId);
    }

    @Transactional(readOnly = true)
    public List<ObJourneyTemplateStepDoc> getStepDocs(long stepId) {
        return stepDocs.findByStepIdOrderBySequenceAsc(stepId);
    }

    /**
     * C-123 · the Module Service catalogue's own "Depends on" picker,
     * settable at any time — unlike a step's fields, the dependency set is
     * catalogue metadata, not journey content an in-flight instantiation has
     * pinned, so {@link #requireEditable}'s publish guard does not apply to
     * it. Works on a draft or the active row alike, on the same reasoning
     * {@link #reorderCatalogue} states for {@code sequence}.
     *
     * <h2>A set, and a full replace</h2>
     *
     * <p>This took a single {@code Long} until the picker became multi-select:
     * a service waits behind several others, and the plan's own example — a
     * biometric rollout after the ERP service — is a set of one only because
     * the example is small. {@code dependsOnTemplateIds} is the caller's whole
     * desired set, not a delta, on {@link #reorderCatalogue}'s convention for
     * the same screen: an empty list clears every dependency and the service
     * runs unheld. Duplicates within the list are collapsed rather than
     * refused — asking for the same edge twice is a request for one edge, and
     * the primary key would otherwise answer it as a duplicate-key error the
     * caller cannot act on.
     *
     * @throws TemplateDependencyCycleException one of the named templates
     *                                           already depends, directly or
     *                                           transitively, on this one
     */
    @Transactional
    public ObJourneyTemplate updateDependsOn(long templateId, List<Long> dependsOnTemplateIds) {
        ObJourneyTemplate template = templates.findById(templateId)
                .orElseThrow(() -> new TemplateNotFoundException(templateId));
        replaceEdges(templateId, dependsOnTemplateIds);
        return template;
    }

    /**
     * Validates a template's whole desired dependency set and writes it,
     * replacing whatever it had.
     *
     * <p>Every candidate is checked before anything is written. The
     * alternative — delete, then insert one at a time, refusing on the third —
     * leaves a transaction to roll back and, more to the point, makes the
     * failure a caller sees depend on the order the picker happened to send
     * the ids in.
     */
    private void replaceEdges(long templateId, List<Long> dependsOnTemplateIds) {
        Set<Long> requested = new LinkedHashSet<>();
        if (dependsOnTemplateIds != null) {
            for (Long id : dependsOnTemplateIds) {
                if (id != null) {
                    requested.add(id);
                }
            }
        }

        for (Long candidate : requested) {
            if (candidate == templateId) {
                // `ck_ob_jt_dependencies_not_self` would refuse this too, but
                // as a constraint-violation stack trace rather than as the
                // 409 the picker knows how to show.
                throw new TemplateDependencyCycleException(templateId, candidate);
            }
            if (!templates.existsById(candidate)) {
                throw new TemplateNotFoundException(candidate);
            }
            requireNoCycle(templateId, candidate);
        }

        dependencies.deleteByIdTemplateIdIn(List.of(templateId));
        /*
          Flushed between the delete and the inserts. Without it Hibernate is
          free to order the inserts first, and re-declaring an edge the
          template already has — which is most saves, since the picker sends
          its whole set every time — collides with the row about to be removed
          on the (template_id, depends_on_template_id) primary key.
        */
        dependencies.flush();
        for (Long candidate : requested) {
            dependencies.save(new ObJourneyTemplateDependency(templateId, candidate));
        }
        dependencies.flush();
    }

    /**
     * Refuses {@code templateId -> candidate} if the candidate can already
     * reach {@code templateId}.
     *
     * <h2>Why this is a search and not a walk</h2>
     *
     * <p>The single-dependency version followed a <em>chain</em>: one cursor,
     * one {@code dependsOnTemplateId} at a time, until it ran out. With a set
     * per node the structure is a directed graph, so this is a breadth-first
     * search over every outgoing edge. A candidate three hops away down the
     * second branch of a fork is exactly the cycle a chain walk misses — and
     * having missed it, reports success.
     *
     * <p>{@code visited} is what makes this terminate on data that is already
     * cyclic. It should not be: that is the invariant this method exists to
     * keep. But a walk that assumes its own invariant holds hangs the request
     * instead of reporting the problem, which is the worse of the two.
     *
     * <p>Edges out of {@code templateId} itself are never followed. They are
     * about to be replaced wholesale, so following them would test the graph
     * as it stands rather than the graph the caller is asking for, and would
     * refuse a legal edit purely because the set being discarded reached the
     * candidate.
     */
    private void requireNoCycle(long templateId, long candidate) {
        Set<Long> visited = new LinkedHashSet<>();
        Deque<Long> frontier = new ArrayDeque<>();
        frontier.add(candidate);
        visited.add(candidate);

        while (!frontier.isEmpty()) {
            long current = frontier.removeFirst();
            for (ObJourneyTemplateDependency edge
                    : dependencies.findByIdTemplateIdOrderByIdDependsOnTemplateIdAsc(current)) {
                long next = edge.getDependsOnTemplateId();
                if (next == templateId) {
                    throw new TemplateDependencyCycleException(templateId, candidate);
                }
                if (visited.add(next)) {
                    frontier.addLast(next);
                }
            }
        }
    }

    /**
     * What one template version waits behind — the picker's current selection,
     * and what every DTO carrying the set reads.
     */
    @Transactional(readOnly = true)
    public List<Long> dependsOnTemplateIds(long templateId) {
        return dependencies.findByIdTemplateIdOrderByIdDependsOnTemplateIdAsc(templateId).stream()
                .map(ObJourneyTemplateDependency::getDependsOnTemplateId)
                .toList();
    }

    /**
     * The same for a whole page of templates in one statement — what the
     * catalogue list read enriches every row with, rather than a query per
     * card.
     *
     * <p>A template with no dependencies is absent from the map rather than
     * present with an empty list, {@code countJourneysByTemplate}'s own shape;
     * callers read it with {@code getOrDefault(id, List.of())}.
     */
    @Transactional(readOnly = true)
    public Map<Long, List<Long>> dependsOnByTemplate(Collection<Long> templateIds) {
        Map<Long, List<Long>> byTemplate = new LinkedHashMap<>();
        if (templateIds.isEmpty()) {
            return byTemplate;
        }
        for (ObJourneyTemplateDependency edge : dependencies.findByIdTemplateIdIn(templateIds)) {
            byTemplate.computeIfAbsent(edge.getTemplateId(), key -> new ArrayList<>())
                    .add(edge.getDependsOnTemplateId());
        }
        /*
          Sorted for the same reason the single-template read has an ORDER BY:
          the batch query has none, and a set read two ways has to come back
          the same way both times or the detail ETag moves without the
          template having changed.
        */
        byTemplate.values().forEach(Collections::sort);
        return byTemplate;
    }

    /**
     * C-123 · the OB-07 catalogue's own ↑/↓ control, {@code reorderSteps}'
     * two-pass shape applied one level up: every active template renumbered
     * 1..N in the caller's order. {@code sequence} "drives instantiation and
     * display order" (plan §5 item 5) for every client from here on — it is
     * not journey content, so this writes the active row directly rather
     * than routing through a draft the way step edits do.
     *
     * <p>No {@code If-Match} here, unlike {@code reorderSteps}: that
     * precondition protects one editor's view of one template's step list
     * from a second editor's concurrent write to the <em>same</em> row: this
     * call spans every active template at once, and two admins reordering
     * the catalogue seconds apart is a "whoever saved last wins" property of
     * a full-list replace, not a lost-update race over a single resource —
     * named here rather than a precondition invented to look complete.
     *
     * @param orderedTemplateIds every currently-active template's id, named exactly once
     * @throws CatalogueReorderMismatchException a duplicate, or a set that does
     *                                           not match every active template
     */
    @Transactional
    public void reorderCatalogue(List<Long> orderedTemplateIds) {
        List<ObJourneyTemplate> current = templates.findByIsActiveTrueOrderBySequenceAsc();

        Set<Long> currentIds = new LinkedHashSet<>();
        for (ObJourneyTemplate template : current) {
            currentIds.add(template.getId());
        }
        Set<Long> requestedIds = new LinkedHashSet<>(orderedTemplateIds);
        if (requestedIds.size() != orderedTemplateIds.size()) {
            throw new CatalogueReorderMismatchException("the same template id appears more than once");
        }
        if (!requestedIds.equals(currentIds)) {
            throw new CatalogueReorderMismatchException(
                    "the given ids are not exactly the catalogue's current active templates");
        }

        Map<Long, ObJourneyTemplate> byId = new HashMap<>();
        for (ObJourneyTemplate template : current) {
            byId.put(template.getId(), template);
        }

        // Pass 1: negative, distinct placeholders — reorderSteps' own reason:
        // keeps pass 2 collision-free against uq-style reads mid-loop.
        int placeholder = 1;
        for (Long templateId : orderedTemplateIds) {
            ObJourneyTemplate template = byId.get(templateId);
            template.setSequence(-placeholder);
            templates.save(template);
            placeholder++;
        }
        templates.flush();

        int sequence = 0;
        for (Long templateId : orderedTemplateIds) {
            ObJourneyTemplate template = byId.get(templateId);
            template.setSequence(sequence);
            templates.save(template);
            sequence++;
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // C-124 · editing and deleting a whole Module Service
    // ══════════════════════════════════════════════════════════════════

    /**
     * Client journeys instantiated from each of these template versions,
     * <b>chain-wide</b>: every row of one service carries the same total.
     *
     * <p>The catalogue's Edit and Delete controls are enabled or disabled by
     * this number, and the card they sit on is the head of a version chain —
     * so the honest figure for a card is the whole chain's, not the head row's.
     * A service whose v1 carries three clients and whose v2 carries none is in
     * use, and a per-row count would have said "0" on the card that draws the
     * buttons.
     *
     * <p>Returned as a map keyed by every template id passed in, zeros
     * included, so the caller never has to distinguish "no journeys" from "not
     * asked about".
     */
    @Transactional(readOnly = true)
    public Map<Long, Long> journeyCountsByTemplate(List<ObJourneyTemplate> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = rows.stream().map(ObJourneyTemplate::getId).toList();
        Map<Long, Long> perVersion = new HashMap<>();
        for (ObJourneyTemplateRepository.TemplateTally tally : templates.countJourneysByTemplate(ids)) {
            perVersion.put(tally.getTemplateId(), tally.getTally());
        }

        // Roll the per-version tallies up onto the (productId, name) chain,
        // then hand every member of that chain the chain's own total.
        Map<String, Long> perService = new HashMap<>();
        for (ObJourneyTemplate row : rows) {
            perService.merge(serviceKey(row), perVersion.getOrDefault(row.getId(), 0L), Long::sum);
        }

        Map<Long, Long> byTemplateId = new LinkedHashMap<>();
        for (ObJourneyTemplate row : rows) {
            byTemplateId.put(row.getId(), perService.getOrDefault(serviceKey(row), 0L));
        }
        return byTemplateId;
    }

    /**
     * The catalogue card's "Edit details" — renames a Module Service, or moves
     * it to another product, across <b>every version in its chain</b>.
     *
     * <p>A service is identified by {@code (product_id, name)}, exactly as
     * {@code uq_ob_journey_templates_version} keys it. Renaming the one row the
     * caller happened to click would therefore not rename the service: it would
     * split one chain in two, leave {@link #beginRevision} numbering from a head
     * that is no longer the head, and make the catalogue draw a second card for
     * a service nobody created. So the write spans the chain, and the row id in
     * the path only says <em>which</em> chain.
     *
     * <h3>Never refused for being in use — neither field, at any number of
     * clients</h3>
     *
     * <p>Both used to be, and {@link ModuleServiceInUseException} carries the
     * argument that was made for it. It rested on {@code ob_journeys}
     * denormalising both facts at instantiation: {@code service_name}, which
     * {@code uq_ob_journeys_client_service} and the dependency hold resolve a
     * service by, and {@code product_id}, which is half of
     * {@code fk_ob_journeys_application}. Neither was ever an argument for
     * freezing a catalogue entry — a typo, or a service filed under the wrong
     * product, is most worth correcting precisely when clients are already on
     * it. What each needs is an answer for the copy the journeys hold, and the
     * two answers are different:
     *
     * <ul>
     *   <li><b>The name travels.</b> {@code renameServiceOnJourneys} re-stamps
     *       every journey of the chain in this same transaction, so the
     *       template and the journeys are never observed disagreeing and both
     *       lookups keep matching.</li>
     *   <li><b>The product stays.</b> A journey's {@code product_id} is the key
     *       to that client's purchase, not a copy of where the catalogue files
     *       the service; re-stamping it would claim a sale that never happened,
     *       and the foreign key would refuse it. So the templates move and the
     *       journeys keep recording what was actually bought.</li>
     * </ul>
     *
     * <p><b>What a move therefore costs, stated plainly.</b> Journeys boarded
     * before it keep the old product, so the dependency hold — keyed on the
     * chain's <em>current</em> {@code (product, name)} — stops recognising
     * them. Holds already stamped are unaffected: {@code release} finds waiting
     * journeys by {@code held_by_journey_id}, which is an id. The narrow case
     * that changes is {@code outstandingHolder}, the "is another dependency
     * still running?" re-check, which can now answer no for a journey under the
     * old product and release a dependent early. Resolving the hold by the
     * dependency's template-chain ids instead of its name would close that, and
     * is the right follow-up; it is not done here because it is a change to the
     * instantiation and release path rather than to this one.
     *
     * <p>This is deliberately <em>not</em> {@link #requireEditable}. That guard
     * asks whether a version has been published, because publishing freezes the
     * journey content a running instance renders from. A name and a product are
     * catalogue metadata rather than journey content — the same distinction
     * {@link #updateDependsOn} draws to justify working on a published row — so
     * publishing has never had a say here either. What is left of the usage
     * check belongs to {@link #deleteModuleService} alone.
     */
    @Transactional
    public ObJourneyTemplate updateModuleService(long templateId, String name, Long productId) {
        ObJourneyTemplate head = templates.findById(templateId)
                .orElseThrow(() -> new TemplateNotFoundException(templateId));

        List<ObJourneyTemplate> chain = serviceChain(head);
        String oldName = head.getName();
        long oldProductId = head.getProductId();
        String newName = name.trim();
        long newProductId = productId == null ? oldProductId : productId;
        if (newName.equals(oldName) && newProductId == oldProductId) {
            return head;
        }

        List<Long> chainIds = chain.stream().map(ObJourneyTemplate::getId).toList();

        /*
          Checked before the loop rather than left to the unique index. The
          write touches every version of the chain, so a collision discovered on
          the third row aborts a transaction that has already renamed two — and
          surfaces as an index name and a version number, which is not something
          an admin can act on. See DuplicateModuleServiceNameException.
        */
        if (!templates.findByProductIdAndNameOrderByVersionAsc(newProductId, newName).isEmpty()) {
            throw new DuplicateModuleServiceNameException(newProductId, newName);
        }

        for (ObJourneyTemplate version : chain) {
            version.setName(newName);
            version.setProductId(newProductId);
            templates.save(version);
        }
        templates.flush();

        /*
          The journeys follow the name, in this transaction. Left behind, they
          would keep the old string and two lookups keyed on it would stop
          matching: the dependency hold (ObJourneyDependencyRelease joins
          h2.service_name = dep.name) and uq_ob_journeys_client_service, which
          is what stops one client being boarded twice onto one service.

          It cannot collide with a journey of another service. A journey's
          service_name only ever comes from a template of the same product, the
          duplicate check above has just established that no other chain in that
          product holds this name, and the index already forbids one client two
          live journeys on one chain. So the rows being re-stamped are the only
          rows in their (client, product) that can hold the new name.

          Unconditional on the journey count, unlike the guard it replaced:
          asking how many there are first would be a second query to decide
          whether to run an UPDATE whose WHERE clause answers the same question.
        */
        if (!newName.equals(oldName)) {
            templates.renameServiceOnJourneys(newName, chainIds);
        }
        return templates.findById(templateId).orElseThrow(() -> new TemplateNotFoundException(templateId));
    }

    /**
     * The catalogue card's Delete — removes a Module Service and every version
     * of it, with its steps, step items and step docs.
     *
     * <p>Three refusals, in the order the database would hit them if they were
     * left to it, each replaced by one that names the thing the admin can
     * actually change:
     *
     * <ol>
     *   <li><b>A client is on it</b> — {@link ModuleServiceInUseException}.
     *       {@code ob_journeys.template_id} is a RESTRICT foreign key, so this
     *       would otherwise be {@code ERROR 1451}; more to the point, a journey
     *       renders its steps from these very rows, and deleting them would
     *       empty a running client's ribbon.</li>
     *   <li><b>Another service depends on it</b> —
     *       {@link ModuleServiceHasDependentsException}, for
     *       {@code fk_ob_journey_templates_depends_on}'s own stated reason.</li>
     *   <li>Steps depending on other steps, which is not a refusal but an
     *       ordering problem — see below.</li>
     * </ol>
     *
     * <p><b>Steps are deleted dependents-first, and this is the order
     * {@code V20260903_1420} asks for by name.</b> That migration chose
     * RESTRICT over CASCADE on {@code fk_ob_journey_template_steps_template}
     * after measuring the alternative on MySQL 8.4: with CASCADE, deleting a
     * template whose steps form a dependency chain fails with an error naming
     * {@code fk_ob_journey_template_steps_depends_on} — a constraint on a
     * different table than the one the caller asked about — while a template
     * whose steps are all parallel deletes cleanly. Deletion working
     * <em>except</em> when the admin used the feature OB-07 is built around is
     * the worst available behaviour, so the migration made it fail the same way
     * every time and left the ordering to this method.
     *
     * <p>Items and docs need no such care: their own foreign keys are
     * {@code ON DELETE CASCADE} onto the step.
     *
     * <p>The chain's own dependency edges are cleared first. A dependency
     * normally names another service, but nothing stops one version of a
     * chain naming another, and {@code fk_ob_jt_dependencies_depends_on}
     * would refuse the delete over a reference that is about to be deleted
     * too. Edges are rows in {@code ob_journey_template_dependencies} since
     * {@code V20260911_1100}, so clearing them is a delete of association
     * rows rather than a null-out of a column — the same act, one table over.
     */
    @Transactional
    public void deleteModuleService(long templateId) {
        ObJourneyTemplate head = templates.findById(templateId)
                .orElseThrow(() -> new TemplateNotFoundException(templateId));

        List<ObJourneyTemplate> chain = serviceChain(head);
        requireUnused(head.getName(), chain);

        Set<Long> chainIds = new LinkedHashSet<>(chain.stream().map(ObJourneyTemplate::getId).toList());
        /*
          Which other services hold an edge onto any version of this chain.
          The chain's own versions are filtered out before the ids are
          resolved to names: a version of this service depending on another
          version of itself is about to be deleted along with the edge, and
          reporting the service as its own dependent would refuse a delete
          that is perfectly safe.
        */
        Set<Long> dependentIds = new LinkedHashSet<>();
        for (ObJourneyTemplateDependency edge : dependencies.findByIdDependsOnTemplateIdIn(chainIds)) {
            if (!chainIds.contains(edge.getTemplateId())) {
                dependentIds.add(edge.getTemplateId());
            }
        }
        if (!dependentIds.isEmpty()) {
            List<String> dependents = templates.findByIdIn(dependentIds).stream()
                    .map(ObJourneyTemplate::getName)
                    .distinct()
                    .toList();
            throw new ModuleServiceHasDependentsException(head.getName(), dependents);
        }

        dependencies.deleteByIdTemplateIdIn(chainIds);
        dependencies.flush();

        for (ObJourneyTemplate version : chain) {
            deleteStepsOf(version.getId());
        }
        templates.deleteAll(chain);
        templates.flush();
    }

    /**
     * One version's steps, dependents first — {@code removeStep}'s refusal
     * turned into an ordering, since here every step is going.
     *
     * <p>Peeling rather than reverse-sequence order: a dependency is supposed to
     * name an earlier step and C-119 enforces that on the write path, but a
     * delete that only works while that invariant holds is a delete that fails
     * on exactly the data worth investigating. Peeling terminates on any acyclic
     * graph, and the self-FK makes a cycle unrepresentable.
     */
    private void deleteStepsOf(long versionId) {
        List<ObJourneyTemplateStep> remaining =
                new ArrayList<>(steps.findByTemplateIdOrderBySequenceAsc(versionId));
        while (!remaining.isEmpty()) {
            Set<Long> stillPointedAt = new LinkedHashSet<>();
            for (ObJourneyTemplateStep step : remaining) {
                if (step.getDependsOnStepId() != null) {
                    stillPointedAt.add(step.getDependsOnStepId());
                }
            }

            List<ObJourneyTemplateStep> leaves = remaining.stream()
                    .filter(step -> !stillPointedAt.contains(step.getId()))
                    .toList();
            if (leaves.isEmpty()) {
                // Unreachable while the self-FK holds — a cycle cannot be
                // stored. Fail loudly rather than spin forever.
                throw new IllegalStateException(
                        "journey template " + versionId + " has a cycle in depends_on_step_id");
            }
            steps.deleteAll(leaves);
            steps.flush();
            remaining.removeAll(leaves);
        }
    }

    /** Every version sharing this row's {@code (productId, name)} — the service itself. */
    private List<ObJourneyTemplate> serviceChain(ObJourneyTemplate head) {
        return templates.findByProductIdAndNameOrderByVersionAsc(head.getProductId(), head.getName());
    }

    /**
     * The delete's own usage check.
     *
     * <p>Only the delete's: editing is not gated on usage in either field —
     * a rename carries its journeys with it, and a product move leaves them
     * recording the purchase they were bought under. See
     * {@link #updateModuleService}.
     *
     * @throws ModuleServiceInUseException if any client was ever boarded on any version of the chain
     */
    private void requireUnused(String serviceName, List<ObJourneyTemplate> chain) {
        List<Long> ids = chain.stream().map(ObJourneyTemplate::getId).toList();
        long journeys = ids.isEmpty() ? 0L : templates.countJourneysForTemplates(ids);
        if (journeys > 0) {
            throw new ModuleServiceInUseException(serviceName, journeys);
        }
    }

    /**
     * {@code (productId, name)} as one map key — the exact key the OB-07 page
     * groups its cards on, and the one {@code uq_ob_journey_templates_version}
     * keys uniqueness on, so the three cannot drift apart about what "one
     * service" means. Unambiguous despite the plain space: the prefix is
     * always digits, so the first space is always the separator.
     */
    private static String serviceKey(ObJourneyTemplate row) {
        return row.getProductId() + " " + row.getName();
    }

    /** @throws TemplateNotEditableException if the template has ever been published. */
    private ObJourneyTemplate requireEditable(long templateId) {
        ObJourneyTemplate template = templates.findById(templateId)
                .orElseThrow(() -> new TemplateNotFoundException(templateId));
        if (template.getPublishedAt() != null) {
            throw new TemplateNotEditableException(templateId);
        }
        return template;
    }

    private ObJourneyTemplateStep requireStep(long stepId) {
        return steps.findById(stepId).orElseThrow(() -> new StepNotFoundException(stepId));
    }

    private int nextStepSequence(long templateId) {
        return steps.findTopByTemplateIdOrderBySequenceDesc(templateId)
                .map(s -> s.getSequence() + 1)
                .orElse(1);
    }

    private int nextItemSequence(long stepId) {
        return stepItems.findTopByStepIdOrderBySequenceDesc(stepId)
                .map(i -> i.getSequence() + 1)
                .orElse(1);
    }

    private int nextDocSequence(long stepId) {
        return stepDocs.findTopByStepIdOrderBySequenceDesc(stepId)
                .map(d -> d.getSequence() + 1)
                .orElse(1);
    }
}
