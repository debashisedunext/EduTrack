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

    /**
     * The S-13 tab 1 grid — every status, retired ones included, in the order the
     * screen renders them.
     *
     * <p>Ordered by {@code seq} and not by {@code category}, deliberately. The
     * category is a grouping the screen applies; {@code seq} is the lifecycle
     * order an admin arranged by hand, and it is the same order the ticket
     * screens' status filters use. Sorting here by category would mean the grid
     * and the filter disagree about what comes after what, and one of them would
     * be wrong.
     */
    List<Status> findAllByOrderBySeqAscIdAsc();

    boolean existsByCode(String code);

    Optional<Status> findByNameIgnoreCase(String name);
}
