package com.edunext.edutrack.api.feature.onboarding.journeys;

import com.edunext.edutrack.domain.onboarding.ObJourneyTemplate;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateRepository;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStep;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStepDoc;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStepDocRepository;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStepItem;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStepItemRepository;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStepRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * <p>Cross-template dependency cycles ({@code dependsOnTemplateId}) and the
 * "earlier step in this template" rule on {@code dependsOnStepId} are both
 * out of scope here — the migration's own comments assign the first to
 * C-123 and the second to C-119, since neither is expressible as a database
 * constraint. This service only guards what deleting or revising could
 * otherwise corrupt: a dangling dependency, or a published row edited in
 * place.
 */
@Service
public class ObJourneyTemplateService {

    private final ObJourneyTemplateRepository templates;
    private final ObJourneyTemplateStepRepository steps;
    private final ObJourneyTemplateStepItemRepository stepItems;
    private final ObJourneyTemplateStepDocRepository stepDocs;

    public ObJourneyTemplateService(ObJourneyTemplateRepository templates,
                                     ObJourneyTemplateStepRepository steps,
                                     ObJourneyTemplateStepItemRepository stepItems,
                                     ObJourneyTemplateStepDocRepository stepDocs) {
        this.templates = templates;
        this.steps = steps;
        this.stepItems = stepItems;
        this.stepDocs = stepDocs;
    }

    /**
     * "+ Create journey template" (OB-07) — the way a new journey is born.
     * Refused once the product already has a template row, draft or
     * published: from that point on, editing goes through
     * {@link #beginRevision}.
     */
    @Transactional
    public ObJourneyTemplate createTemplate(long productId, String name, int sequence,
                                             Long dependsOnTemplateId, long createdBy) {
        if (templates.existsByProductId(productId)) {
            throw new TemplateAlreadyExistsException(productId);
        }

        ObJourneyTemplate template = new ObJourneyTemplate();
        template.setProductId(productId);
        template.setName(name);
        template.setVersion(1);
        template.setActive(false);
        template.setSequence(sequence);
        template.setDependsOnTemplateId(dependsOnTemplateId);
        template.setCreatedBy(createdBy);
        return templates.save(template);
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

        int nextVersion = templates.findTopByProductIdOrderByVersionDesc(active.getProductId())
                .map(ObJourneyTemplate::getVersion)
                .orElse(active.getVersion())
                + 1;

        ObJourneyTemplate draft = new ObJourneyTemplate();
        draft.setProductId(active.getProductId());
        draft.setName(active.getName());
        draft.setVersion(nextVersion);
        draft.setActive(false);
        draft.setSequence(active.getSequence());
        draft.setDependsOnTemplateId(active.getDependsOnTemplateId());
        draft.setCreatedBy(editorUserId);
        ObJourneyTemplate savedDraft = templates.save(draft);

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

    @Transactional
    public ObJourneyTemplateStep addStep(long templateId, String name, String description, int tatDays,
                                          Long ownerUserId, String ownerRole, Long backupOwnerUserId,
                                          boolean requiresSignoff, Long dependsOnStepId) {
        ObJourneyTemplate template = requireEditable(templateId);

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

    @Transactional
    public ObJourneyTemplateStepItem addStepItem(long stepId, String label) {
        ObJourneyTemplateStep step = requireStep(stepId);
        requireEditable(step.getTemplateId());

        ObJourneyTemplateStepItem item = new ObJourneyTemplateStepItem();
        item.setStepId(stepId);
        item.setSequence(nextItemSequence(stepId));
        item.setLabel(label);
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
     * The draft becomes the product's active version. If another version is
     * currently active, it is retired first — in the same transaction, and
     * in that order — so the unique index over one active row per product
     * never sees two at once.
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

        templates.findByProductIdAndIsActiveTrue(draft.getProductId())
                .ifPresent(current -> {
                    current.setActive(false);
                    templates.saveAndFlush(current);
                });

        draft.setActive(true);
        draft.setPublishedBy(publishedBy);
        draft.setPublishedAt(Instant.now());
        return templates.saveAndFlush(draft);
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
