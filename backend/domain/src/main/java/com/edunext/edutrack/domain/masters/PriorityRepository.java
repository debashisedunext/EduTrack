package com.edunext.edutrack.domain.masters;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PriorityRepository extends JpaRepository<Priority, Integer> {

    /**
     * {@code tickets.level} stores this code rather than an id, so this is the
     * lookup every ticket read goes through to render a level.
     */
    Optional<Priority> findByCode(String code);

    /** Ordered by {@code seq} — severity rank is the sequence, not the id. */
    List<Priority> findByIsActiveTrueOrderBySeqAsc();

    /**
     * B-021 · every level, retired ones included.
     *
     * <p>Only the S-12 master grid wants this. {@code listPriorities} answers
     * with the active list by default, because Stream C's {@code LevelPicker}
     * and ticket-list filter consume that endpoint without filtering — see
     * {@code PriorityService.list}.
     *
     * <p>{@code id} breaks the tie so that two levels sharing a {@code seq}
     * still order deterministically, which is what makes the SLA matrix's
     * column order stable between reads.
     */
    List<Priority> findAllByOrderBySeqAscIdAsc();

    /** B-021 · the uniqueness check on create, before the index has to refuse it. */
    boolean existsByCode(String code);

    /**
     * B-021 · the second uniqueness rule, which has no index behind it.
     *
     * <p>Two levels displaying the same name are indistinguishable in the
     * picker, the grid and the SLA matrix's column headers at once.
     */
    Optional<Priority> findByNameIgnoreCase(String name);

    /**
     * B-021 · every level currently flagged as the SLA engine's escalation
     * target, active or not.
     *
     * <p>A list rather than an {@code Optional} deliberately: the column is a
     * bare {@code TINYINT DEFAULT 0} with no uniqueness constraint, so more
     * than one row *can* carry it, and a single-result query would throw on
     * data the database is perfectly willing to hold. {@code PriorityService}
     * is what makes it exactly one.
     */
    List<Priority> findByIsEscalationTriggerTrue();
}
