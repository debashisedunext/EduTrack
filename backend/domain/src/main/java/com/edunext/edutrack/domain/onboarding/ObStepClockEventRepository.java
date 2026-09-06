package com.edunext.edutrack.domain.onboarding;

import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/**
 * C-105 · reads over {@code ob_step_clock_events}. Finders only, on {@code
 * AuditLogRepository}'s own precedent for an append-only table with nothing
 * to chain: extending the bare {@link Repository} marker rather than {@code
 * JpaRepository} means this interface never publishes {@code save} or
 * {@code delete} for a caller to find by autocomplete. The one write this
 * table has goes through {@code ObStepClockRecorder}, a hand-written insert
 * outside JPA entirely — see that class and {@link ObStepClockEvent}'s own
 * javadoc for why.
 */
public interface ObStepClockEventRepository extends Repository<ObStepClockEvent, Long> {

    /** A step's full clock history, oldest first — the fold a future roll-up walks. */
    List<ObStepClockEvent> findByStepIdOrderByOccurredAtAscIdAsc(long stepId);

    /**
     * The pause {@code resume} is closing out. Latest first: a step may have
     * paused and resumed more than once, and only the most recent, unmatched
     * {@code PAUSED} row bears on the {@code due_at} recomputation the
     * current {@code resume} call is making.
     */
    Optional<ObStepClockEvent> findFirstByStepIdAndEventTypeOrderByOccurredAtDescIdDesc(
            long stepId, ObStepClockEventType eventType);
}
