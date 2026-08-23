package com.edunext.edutrack.domain.tickets;

import com.edunext.edutrack.domain.appendonly.AppendOnly;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * <b>Append-only.</b> No {@code save}, no {@code delete} — see
 * {@link com.edunext.edutrack.domain.appendonly.AppendOnly}. A wrongly logged
 * hour is corrected by inserting a compensating row, which may be negative.
 */
public interface TicketEffortLogRepository
        extends Repository<TicketEffortLog, Long>, AppendOnly<TicketEffortLog> {

    Optional<TicketEffortLog> findById(Long id);

    List<TicketEffortLog> findByTicketIdOrderByIdAsc(Long ticketId);

    /**
     * The §4A.4 roll-up: effort attributed to one ribbon segment.
     *
     * <p><b>{@code cycleNo} is part of the key, not an optional refinement.</b>
     * PLAN.md §3.4 records the defect in the blueprint's version of this query,
     * which joins effort to transitions without it — a reopened ticket
     * re-entering the same stage at the same iteration then double-counts cycle
     * 1's effort into cycle 2. {@code ix_effort_rollup} matches these four
     * columns for that reason.
     */
    List<TicketEffortLog> findByTicketIdAndCycleNoAndStageCodeAndIterationNo(
            Long ticketId, short cycleNo, String stageCode, short iterationNo);

    /** One resource's week across all tickets — the B-063 timesheet. */
    List<TicketEffortLog> findByUserIdAndWorkDateBetweenOrderByWorkDateAsc(
            Long userId, LocalDate from, LocalDate to);

    /**
     * The tail of this ticket's chain. The caller must already hold
     * {@link TicketRepository#findByIdForUpdate} (PLAN.md §3.7).
     *
     * <p><b>{@code FOR UPDATE} is baked into the SQL, not requested through
     * {@code @Lock}</b> — {@code TicketEffortLog} is {@link
     * org.hibernate.annotations.Immutable @Immutable}, and {@link
     * TicketHistoryRepository#findFirstByTicketIdOrderByIdDesc}'s own note
     * explains both why a locking read is required under REPEATABLE READ and
     * why a JPA-level {@code @Lock} is the wrong way to ask for one against an
     * immutable entity ({@code UnsupportedLockAttemptException}, for every
     * ticket past its first effort row).
     */
    @Query(value = "select * from ticket_effort_logs where ticket_id = :ticketId "
            + "order by id desc limit 1 for update", nativeQuery = true)
    Optional<TicketEffortLog> findFirstByTicketIdOrderByIdDesc(@Param("ticketId") Long ticketId);

    List<TicketEffortLog> findByCorrectsEntryId(Long correctsEntryId);
}
