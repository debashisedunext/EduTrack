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
}
