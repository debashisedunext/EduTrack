package com.edunext.edutrack.domain.onboarding;

/**
 * C-105 · {@code ob_step_clock_events.actor_type} (A-105, {@code
 * V20260903_1730}), {@code ck_ob_clock_actor_type}. {@link #SYSTEM} pairs
 * with a {@code null actorId} — a scanner-driven event, not a person's.
 */
public enum ObStepClockActorType {
    USER, SYSTEM
}
