package com.edunext.edutrack.api.feature.onboarding.instances;

import com.edunext.edutrack.domain.onboarding.ObStepClockEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * C-105 · the only way a row reaches {@code ob_step_clock_events}.
 *
 * <p>A hand-written {@code INSERT} rather than {@code
 * EntityManager#persist}, on {@code AuditTrail}'s own precedent one feature
 * package over — and for the same reason {@code ImportBatchService}'s own
 * history gives for moving away from an entity manager in this layer: an
 * injected {@code EntityManager} opens a live persistence context per call,
 * which is what would turn {@code ObJourneyStepLifecycleServiceTest} (no
 * container, plain mocks) into a test that needs a database. {@link
 * ObStepClockEvent} stays a JPA entity for the read side only — {@code
 * ObStepClockEventRepository}'s two finders — and this class is mockable
 * the exact way {@code ObStepHistoryRepository#insert} already is in that
 * test.
 *
 * <p><b>Unlike {@code AuditTrail#record}, this does not run in its own
 * {@code REQUIRES_NEW} transaction.</b> An audit row is best-effort
 * observability of a transaction that might roll back; a clock event <em>is
 * the TAT record</em> (A-105's migration header) and must commit or roll
 * back with the status change it accompanies — a {@code PAUSED} row that
 * survived a rolled-back {@code waitOnClient} would assert a wait that never
 * happened. So this joins the caller's transaction, and a failure here fails
 * the caller's write instead of being logged and swallowed.
 */
@Component
public class ObStepClockRecorder {

    private static final String INSERT = """
            INSERT INTO ob_step_clock_events
                (step_id, journey_id, event_type, pause_reason, attributed_to,
                 occurred_at, actor_id, actor_type, note)
            VALUES
                (:stepId, :journeyId, :eventType, :pauseReason, :attributedTo,
                 :occurredAt, :actorId, :actorType, :note)
            """;

    private final JdbcClient jdbc;

    public ObStepClockRecorder(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Append one clock event. The only write this table has. */
    public void record(ObStepClockEvent event) {
        jdbc.sql(INSERT)
                .param("stepId", event.getStepId())
                .param("journeyId", event.getJourneyId())
                .param("eventType", event.getEventType().name())
                .param("pauseReason", event.getPauseReason())
                .param("attributedTo", event.getAttributedTo().name())
                .param("occurredAt", event.getOccurredAt())
                .param("actorId", event.getActorId())
                .param("actorType", event.getActorType().name())
                .param("note", event.getNote())
                .update();
    }
}
