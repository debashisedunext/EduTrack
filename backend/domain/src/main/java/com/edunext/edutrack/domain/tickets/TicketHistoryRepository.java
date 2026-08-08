package com.edunext.edutrack.domain.tickets;

import com.edunext.edutrack.domain.appendonly.AppendOnly;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/**
 * <b>Append-only.</b> Extends the bare {@link Repository} marker rather than
 * {@code JpaRepository}, so no {@code save}, {@code delete}, {@code deleteById}
 * or {@code deleteAll} exists to be called — see
 * {@link com.edunext.edutrack.domain.appendonly.AppendOnly}.
 *
 * <p>A correction is {@code insert} of a new row carrying
 * {@code correctsEntryId}, never a change to the row being corrected.
 */
public interface TicketHistoryRepository
        extends Repository<TicketHistory, Long>, AppendOnly<TicketHistory> {

    Optional<TicketHistory> findById(Long id);

    /** The History tab, oldest first. Served by {@code ix_history_ticket_id}. */
    List<TicketHistory> findByTicketIdOrderByIdAsc(Long ticketId);

    List<TicketHistory> findByTicketIdAndCycleNoOrderByIdAsc(Long ticketId, Short cycleNo);

    /**
     * The tail of this ticket's chain — the {@code prevHash} of the next
     * append. The caller must already hold
     * {@link TicketRepository#findByIdForUpdate}, or two concurrent appends
     * read the same tail and fork the chain (PLAN.md §3.7).
     */
    Optional<TicketHistory> findFirstByTicketIdOrderByIdDesc(Long ticketId);

    /** Every correction pointing at one entry — the reversal trail for A-043. */
    List<TicketHistory> findByCorrectsEntryId(Long correctsEntryId);
}
