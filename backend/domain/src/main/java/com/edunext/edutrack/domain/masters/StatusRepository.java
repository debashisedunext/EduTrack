package com.edunext.edutrack.domain.masters;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StatusRepository extends JpaRepository<Status, Integer> {

    Optional<Status> findByCode(String code);

    /** The status dropdown, in display order. */
    List<Status> findByIsActiveTrueOrderBySeqAsc();

    /**
     * The set behind every "open tickets" figure. Returned as codes-in-hand so
     * dashboard queries filter on {@code tickets.status} directly instead of
     * joining this master on every read — inactive rows included, because a
     * status retired from the master can still be sitting on a live ticket.
     */
    List<Status> findByIsOpenTrue();
}
