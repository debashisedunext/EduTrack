package com.edunext.edutrack.domain.tickets;

import com.edunext.edutrack.domain.appendonly.AppendOnly;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

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
     *
     * <p>🔴 <b>{@code PESSIMISTIC_WRITE} is load-bearing, and the ticket lock
     * alone is not enough.</b> A-045 found this: MySQL's default REPEATABLE READ
     * serves plain {@code SELECT}s from the snapshot taken at the transaction's
     * <em>first consistent read</em>. A caller that reads anything at all before
     * appending — the ticket, the ribbon, a workflow stage — pins that snapshot
     * before the lock is taken, so the tail read afterwards still sees the table
     * as it was, and every concurrent writer believes it is the first. Eight
     * parallel appends produced eight rows with a NULL {@code prev_hash} rather
     * than one chain: not a fork, a shattering.
     *
     * <p>A locking read is exempt from that. {@code SELECT … FOR UPDATE} reads
     * the latest committed version, so the writer that waited on the ticket lock
     * sees what the writer ahead of it just committed. This is what makes the
     * lock mean anything across transactions; without it the lock serialises
     * writes correctly and each one is still blind.
     *
     * <p><b>{@code FOR UPDATE} is baked into the SQL, not requested through
     * {@code @Lock}.</b> {@code TicketHistory} is {@link
     * org.hibernate.annotations.Immutable @Immutable}, and a JPA-level lock
     * request makes Hibernate try to upgrade the loaded entity's lock mode
     * during result initialisation, which {@code ImmutableEntityEntry} refuses
     * outright ({@code UnsupportedLockAttemptException: Lock mode not
     * supported}) for every ticket past its first history row — the first
     * append has nothing to lock, so the defect was invisible until a second
     * row existed. A native query's {@code FOR UPDATE} clause is executed by
     * MySQL directly and needs no cooperation from Hibernate's entity-locking
     * bookkeeping, so the row lock this method's own reasoning above requires
     * still happens, without touching the immutable entity's lock mode.
     */
    @Query(value = "select * from ticket_history where ticket_id = :ticketId "
            + "order by id desc limit 1 for update", nativeQuery = true)
    Optional<TicketHistory> findFirstByTicketIdOrderByIdDesc(@Param("ticketId") Long ticketId);

    /** Every correction pointing at one entry — the reversal trail for A-043. */
    List<TicketHistory> findByCorrectsEntryId(Long correctsEntryId);
}
