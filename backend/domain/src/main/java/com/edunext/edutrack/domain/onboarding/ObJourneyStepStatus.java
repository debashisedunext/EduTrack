package com.edunext.edutrack.domain.onboarding;

/**
 * C-103 · {@code ob_journey_steps.status}. C-103 only ever writes
 * {@link #PENDING} — every other transition (start, complete,
 * block-with-reason, waiting-on-client, resume, skip) is C-104's and
 * C-107's job. Kept here, not in the step-lifecycle package, because the
 * column and its {@code CHECK} constraint belong to the table this entity
 * maps, the same placement {@link ObGateStatus} follows.
 */
public enum ObJourneyStepStatus {

    /** Gate still locked, or the step's dependency has not completed yet. */
    PENDING,
    IN_PROGRESS,
    BLOCKED,
    WAITING_ON_CLIENT,
    DONE,
    SKIPPED
}
