package com.edunext.edutrack.domain.onboarding;

/**
 * C-105 · {@code ob_step_clock_events.attributed_to} (A-105, {@code
 * V20260903_1730}), {@code ck_ob_clock_attributed_to}. Who the elapsed time
 * between this event and the next belongs to — module plan §1.1 item 1,
 * §14's mitigation for TAT disputes.
 */
public enum ObStepClockAttribution {
    INTERNAL, CLIENT
}
