package com.edunext.edutrack.domain.workflow;

import com.edunext.edutrack.domain.appendonly.AppendOnly;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The ribbon's repository. <b>Append-only</b> — see
 * {@link com.edunext.edutrack.domain.appendonly.AppendOnly} for why this
 * extends the bare {@link Repository} marker instead of {@code JpaRepository}.
 *
 * <p>There is no {@code save}, no {@code delete}, no {@code deleteAll}. The
 * only write is {@code insert}, plus {@link #seal} — the single mutation the
 * A-008 trigger permits.
 */
public interface TicketStageTransitionRepository
        extends Repository<TicketStageTransition, Long>, AppendOnly<TicketStageTransition> {

    Optional<TicketStageTransition> findById(Long id);

    /** The ribbon, in order. Served by {@code ix_stage_ticket_id}. */
    List<TicketStageTransition> findByTicketIdOrderBySeqNoAsc(Long ticketId);

    /**
     * The chain, in order — <b>by id, never by {@code seq_no}</b> (A-044).
     *
     * <p>The two differ and the difference is a trap. {@code seq_no} is unique
     * per <em>cycle</em> ({@code uq_stage_transitions} is
     * {@code (ticket_id, cycle_no, seq_no)}), so a reopened ticket restarts it
     * at 1 and ordering by it interleaves cycle 2's hops among cycle 1's. The
     * hash chain is insertion order and nothing else, which is what
     * {@code ix_stage_ticket_id (ticket_id, id)} — commented "chain walk" —
     * exists to serve.
     *
     * <p>Walking a reopened ticket by {@code seq_no} would report a perfectly
     * sound chain as broken, on precisely the tickets with the most history.
     */
    List<TicketStageTransition> findByTicketIdOrderByIdAsc(Long ticketId);

    /** One cycle's journey — cycle 2 renders without cycle 1's hops. */
    List<TicketStageTransition> findByTicketIdAndCycleNoOrderBySeqNoAsc(Long ticketId, short cycleNo);

    /**
     * "Where is this ticket right now?" Queries A-009's generated column rather
     * than {@code is_current}, so it resolves on {@code ix_stage_current} —
     * exactly one row per ticket, no matter how long the history grows.
     */
    Optional<TicketStageTransition> findByCurrentTicketId(Long ticketId);

    /**
     * The tail of the hash chain for one ticket — the {@code prev_hash} of the
     * next append. PLAN.md §3.7: the chain is <b>per-ticket</b>, and the caller
     * must already hold {@code SELECT … FOR UPDATE} on the parent ticket row,
     * or two concurrent appends read the same tail and fork the chain.
     *
     * <p><b>{@code FOR UPDATE} is baked into the SQL, not requested through
     * {@code @Lock}.</b> {@code TicketStageTransition} is {@link
     * org.hibernate.annotations.Immutable @Immutable}, and a JPA-level lock
     * request makes Hibernate try to upgrade the loaded entity's lock mode
     * during result initialisation — which {@code ImmutableEntityEntry}
     * refuses outright ({@code UnsupportedLockAttemptException: Lock mode not
     * supported}), for every ticket past its very first hop (the first append
     * has no prior row to lock, so the query returns empty and the entity path
     * is never exercised — which is why this went unnoticed until a second
     * transition was appended). A native query's {@code FOR UPDATE} clause is
     * executed by MySQL directly and needs no cooperation from Hibernate's
     * entity-locking bookkeeping, so the row lock — still required, per
     * {@link TicketHistoryRepository#findFirstByTicketIdOrderByIdDesc}'s own
     * note on why a plain {@code SELECT} is not enough under REPEATABLE READ —
     * survives without touching the immutable entity's lock mode at all.
     */
    @Query(value = "select * from ticket_stage_transitions where ticket_id = :ticketId "
            + "order by id desc limit 1 for update", nativeQuery = true)
    Optional<TicketStageTransition> findFirstByTicketIdOrderByIdDesc(@Param("ticketId") Long ticketId);

    /**
     * Seal an open stage — <b>the only mutation this table permits</b>.
     *
     * <p>An explicit JPQL update on exactly the three columns the A-008 trigger
     * allows, rather than mutating a loaded instance. That is the difference
     * PLAN.md §3.6 is drawing: a dirty-checked entity emits an {@code UPDATE}
     * touching every column, which the trigger rejects wholesale even though
     * the developer only meant to change one.
     *
     * <p>{@code exitedAt is null} in the predicate makes a double-seal a no-op
     * that returns 0 rather than a database error. The trigger rejects it too
     * ("already sealed"), but a caller that can distinguish "I sealed it" from
     * "someone else got there first" does not need an exception to do it.
     *
     * @param durationMins <b>working</b> minutes from the calendar service
     *                     (B-024), never wall-clock
     * @return 1 if this call sealed the row, 0 if it was already sealed
     */
    @Modifying
    @Query("""
            update TicketStageTransition t
               set t.exitedAt = :exitedAt,
                   t.durationMins = :durationMins,
                   t.isCurrent = false
             where t.id = :id
               and t.exitedAt is null
            """)
    int seal(@Param("id") Long id,
             @Param("exitedAt") Instant exitedAt,
             @Param("durationMins") Integer durationMins);
}
