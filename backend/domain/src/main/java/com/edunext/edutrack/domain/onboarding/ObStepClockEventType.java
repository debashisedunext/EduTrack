package com.edunext.edutrack.domain.onboarding;

/**
 * C-105 · {@code ob_step_clock_events.event_type} (A-105, {@code
 * V20260903_1730}), {@code ck_ob_clock_event_type}.
 *
 * <p>Only {@link #PAUSED} and {@link #RESUMED} are written today, by {@code
 * ObJourneyStepLifecycleService#waitOnClient} and {@code #resume}. {@link
 * #STARTED} and {@link #STOPPED} are reserved by the migration's own
 * vocabulary for a step's activation and its arrival at {@code DONE}/{@code
 * SKIPPED} — both already timestamped on {@code ob_journey_steps} itself
 * ({@code startedAt}/{@code finishedAt}), so nothing today needs a
 * duplicate row to answer "when did this step start or stop". Left unwritten
 * rather than written speculatively; a future task that needs a clock-event
 * row for either can add the call without a migration.
 */
public enum ObStepClockEventType {
    STARTED, PAUSED, RESUMED, STOPPED
}
