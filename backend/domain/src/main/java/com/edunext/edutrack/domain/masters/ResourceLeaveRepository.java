package com.edunext.edutrack.domain.masters;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface ResourceLeaveRepository extends JpaRepository<ResourceLeave, Long> {

    /**
     * Every approved absence overlapping {@code [from, to]} for one user — the
     * input B-024's working-hours service subtracts before it measures an SLA.
     *
     * <p>Overlap, not containment: a fortnight's leave that brackets the window
     * entirely has neither endpoint inside it, and a containment test would
     * conclude the resource was at their desk all week. The test is
     * {@code start <= to and end >= from}, which is the whole of it.
     *
     * <p>Approved only. A pending or rejected request must not stop the clock,
     * or the SLA a manager sees depends on paperwork nobody has signed. Both
     * bounds are inclusive, matching {@code end_date}'s meaning in the master.
     */
    @Query("""
            select l from ResourceLeave l
             where l.userId = :userId
               and l.status = 'APPROVED'
               and l.startDate <= :to
               and l.endDate >= :from
             order by l.startDate asc
            """)
    List<ResourceLeave> findApprovedOverlapping(@Param("userId") Long userId,
                                                @Param("from") LocalDate from,
                                                @Param("to") LocalDate to);
}
