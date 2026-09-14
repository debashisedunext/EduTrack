package com.edunext.edutrack.domain.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes over {@code ob_journey_template_stages}.
 *
 * <p>A {@code JpaRepository} rather than the narrower {@code Repository}
 * {@link ObImplementationStageRepository} uses, because unlike that master
 * these rows genuinely are deleted: deleting a Module Service deletes its
 * stage groups along with its tasks, and beginning a revision clones them.
 */
public interface ObJourneyTemplateStageRepository extends JpaRepository<ObJourneyTemplateStage, Long> {

    /**
     * A template's stage groups in display order.
     *
     * <p>Ties broken by id for the reason the master's own list gives:
     * {@code sequence} is deliberately not unique, so an ordering that did not
     * break ties would be non-deterministic exactly while something is
     * renumbering against it.
     */
    List<ObJourneyTemplateStage> findByTemplateIdOrderBySequenceAscIdAsc(Long templateId);

    /** Every group across several templates — the catalogue's own read, one query rather than N. */
    List<ObJourneyTemplateStage> findByTemplateIdInOrderBySequenceAscIdAsc(Collection<Long> templateIds);

    /**
     * The group a seeded stage already occupies on this template, if any.
     *
     * <p>What makes {@code seedStageGroups} idempotent against
     * {@code uq_ob_template_stages} rather than reliant on never being called
     * twice.
     */
    Optional<ObJourneyTemplateStage> findByTemplateIdAndImplementationStageId(
            Long templateId, Long implementationStageId);

    /**
     * The Ungrouped group, which {@code findByTemplateIdAndImplementationStageId}
     * cannot find: SQL {@code = NULL} matches nothing, so a derived query
     * taking a null argument returns empty rather than the row.
     */
    Optional<ObJourneyTemplateStage> findByTemplateIdAndImplementationStageIdIsNull(Long templateId);

    long countByTemplateId(Long templateId);

    void deleteByTemplateId(Long templateId);
}
