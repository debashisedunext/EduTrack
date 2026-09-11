package com.edunext.edutrack.domain.onboarding;

import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Reads and writes over {@code ob_implementation_stages}.
 *
 * <p>A {@code Repository} rather than {@code CrudRepository}, the choice
 * {@link ObProductRepository} makes and for the same reason: inheriting
 * {@code delete} and {@code deleteAll} would put a delete on a master whose
 * entire lifecycle is {@code isActive}, and the method that exists is the
 * method somebody eventually calls.
 */
public interface ObImplementationStageRepository extends Repository<ObImplementationStage, Long> {

    ObImplementationStage save(ObImplementationStage stage);

    /**
     * Renumbering writes every row it moved in one call — see
     * {@code ObImplementationStageService.renumber}.
     */
    List<ObImplementationStage> saveAll(Iterable<ObImplementationStage> stages);

    Optional<ObImplementationStage> findById(Long id);

    /**
     * The screen's list and the service's working set, in display order.
     *
     * <p>Ordered by {@code sequence} then {@code id}, and the tie-break is not
     * decoration: the column is deliberately not unique (the migration says
     * why), so a duplicate is momentarily possible mid-transaction and an
     * ordering that did not break ties would be non-deterministic exactly when
     * the service is renumbering against it.
     *
     * <p>Retired rows are included. {@code isActive} is not part of the
     * ordering — a retired stage keeps its slot so the master reads as one
     * list rather than two, which is {@code ObProductRepository}'s call for
     * its own catalogue.
     */
    List<ObImplementationStage> findAllByOrderBySequenceAscIdAsc();

    List<ObImplementationStage> findAllByIsActiveOrderBySequenceAscIdAsc(boolean isActive);

    /**
     * Case-insensitive by the column's own collation, so this matches what
     * {@code uq_ob_implementation_stages_name} would refuse rather than a
     * narrower thing.
     */
    Optional<ObImplementationStage> findByName(String name);
}
