package com.edunext.edutrack.domain.onboarding;

import com.edunext.edutrack.domain.appendonly.AppendOnly;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * C-107 · <b>Append-only.</b> Extends the bare {@link Repository} marker
 * rather than {@code JpaRepository}, on {@code TicketHistoryRepository}'s
 * exact precedent — no {@code save}, {@code delete}, {@code deleteById} or
 * {@code deleteAll} exists to be called. See {@link AppendOnly}.
 */
public interface ObStepHistoryRepository
        extends Repository<ObStepHistory, Long>, AppendOnly<ObStepHistory> {

    Optional<ObStepHistory> findById(Long id);

    /** One step's own entries, oldest first — {@code ix_ob_history_step}. */
    List<ObStepHistory> findByStepIdOrderByIdAsc(Long stepId);

    /**
     * The tail of this journey's chain — the {@code prevHash} of the next
     * append. The caller must already hold {@link ObJourneyRepository#findByIdForUpdate},
     * or two concurrent appends read the same tail and fork the chain, exactly
     * as {@code TicketHistoryRepository#findFirstByTicketIdOrderByIdDesc}'s own
     * javadoc explains for the ticketing table.
     *
     * <p>{@code FOR UPDATE} is baked into the native SQL rather than requested
     * through {@code @Lock}, for the identical reason that javadoc gives:
     * {@link ObStepHistory} is {@link org.hibernate.annotations.Immutable}, and
     * a JPA-level lock request fails outright once a journey has a second
     * history row. A locking read also defeats MySQL's REPEATABLE READ
     * snapshot — a plain {@code SELECT} taken after the journey lock would
     * still see the table as of the transaction's first consistent read, which
     * is older than whatever a concurrent, already-committed append just
     * wrote.
     */
    @Query(value = "select * from ob_step_history where journey_id = :journeyId "
            + "order by id desc limit 1 for update", nativeQuery = true)
    Optional<ObStepHistory> findFirstByJourneyIdOrderByIdDesc(@Param("journeyId") Long journeyId);
}
