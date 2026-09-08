package com.edunext.edutrack.domain.onboarding;

import com.edunext.edutrack.domain.appendonly.AppendOnly;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * B-125 · <b>Append-only and hash-chained.</b> Extends the bare
 * {@link Repository} marker plus {@link AppendOnly}, on
 * {@code ObStepHistoryRepository}'s exact precedent.
 *
 * <p>{@code AppendOnlyRulesTest.theProtectedTablesAreWrittenOnlyThroughTheJournal}
 * is stated over {@code assignableTo(AppendOnly.class)}, so the moment this
 * interface extends it, no class outside {@code domain.journal} may depend on
 * it — {@code ObPrereqJournal} is the only door.
 */
public interface ObPrereqHistoryRepository
        extends Repository<ObPrereqHistory, Long>, AppendOnly<ObPrereqHistory> {

    Optional<ObPrereqHistory> findById(Long id);

    /** One task's own entries, oldest first — {@code ix_ob_prereq_history_task}. */
    List<ObPrereqHistory> findByPrereqTaskIdOrderByIdAsc(Long prereqTaskId, Pageable pageable);

    List<ObPrereqHistory> findByPrereqTaskIdAndIdGreaterThanOrderByIdAsc(
            Long prereqTaskId, Long afterId, Pageable pageable);

    /**
     * The tail of this client's chain — the {@code prevHash} of the next
     * append. The caller must already hold
     * {@link ObClientRepository#findByIdForUpdate}, or two concurrent appends
     * read the same tail and fork the chain.
     *
     * <p>{@code FOR UPDATE} is baked into the native SQL rather than
     * requested through {@code @Lock}, for the reason
     * {@code ObStepHistoryRepository} records: {@link ObPrereqHistory} is
     * {@code @Immutable}, and a JPA-level lock request fails outright once a
     * client has a second history row. A locking read also defeats MySQL's
     * REPEATABLE READ snapshot — a plain {@code SELECT} taken after the
     * client lock would still see the table as of the transaction's first
     * consistent read, which is older than whatever a concurrent,
     * already-committed append just wrote.
     */
    @Query(value = "select * from ob_prereq_history where ob_client_id = :obClientId "
            + "order by id desc limit 1 for update", nativeQuery = true)
    Optional<ObPrereqHistory> findFirstByObClientIdOrderByIdDesc(@Param("obClientId") Long obClientId);
}
