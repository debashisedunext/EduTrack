package com.edunext.edutrack.domain.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/**
 * The service-level dependency graph, read in both directions.
 *
 * <p>Forward — "what does this service wait for" — is instantiation's
 * question, one template at a time, and the picker's when it draws the
 * current selection. Reverse — "which services wait for this one" — is what
 * a delete has to answer before it runs, and is the reason
 * {@code ix_ob_journey_template_dependencies_reverse} exists.
 */
public interface ObJourneyTemplateDependencyRepository
        extends JpaRepository<ObJourneyTemplateDependency, ObJourneyTemplateDependencyId> {

    /**
     * The forward edges of one template version, ascending so the picker and
     * every DTO that carries the set agree on an order. Nothing depends on
     * the order being meaningful — a dependency set has no sequence, unlike
     * the catalogue itself — only on it being stable, so two reads of an
     * unchanged template do not produce two different {@code ETag}s.
     */
    List<ObJourneyTemplateDependency> findByIdTemplateIdOrderByIdDependsOnTemplateIdAsc(Long templateId);

    /**
     * The same, for a batch of templates in one statement — what the
     * catalogue list read enriches every row with, rather than a query per
     * card.
     */
    List<ObJourneyTemplateDependency> findByIdTemplateIdIn(Collection<Long> templateIds);

    /**
     * Every edge pointing <em>at</em> any of these versions — what a delete
     * consults. The old column's
     * {@code ObJourneyTemplateRepository.findByDependsOnTemplateIdIn} in the
     * new shape, and for the same reason: without it the delete surfaces as a
     * raw {@code ERROR 1451} naming a constraint rather than as a refusal
     * naming the services that hold it.
     */
    List<ObJourneyTemplateDependency> findByIdDependsOnTemplateIdIn(Collection<Long> templateIds);

    /**
     * Clears one template's forward edges — {@code updateDependsOn}'s
     * replace-in-full, and the first half of a delete's tidy-up.
     *
     * <p>A delete of association rows, which {@code ClientProject}'s own
     * javadoc calls "the one place a delete is harmless": the row carries no
     * fact of its own beyond the association. None of the four append-only
     * tables is anywhere near this.
     */
    @Transactional
    void deleteByIdTemplateIdIn(Collection<Long> templateIds);
}
