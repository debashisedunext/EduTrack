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

    /**
     * The owner has marked it complete and an OB Manager has not finished
     * reading it — the manager review gate, {@code V20260916_1700}.
     *
     * <p>Open, not terminal: the task is still counted against its Step and
     * still appears in its owner's queue. What it is not is <em>theirs</em>
     * any more — every write to the step and to its check-list rows is
     * refused while it sits here, because an answer that changed under a
     * review in progress would make the verdict describe something the
     * reader never saw.
     *
     * <p>It leaves in one of two directions and neither is a transition an
     * implementor can make: {@code DONE} when every row is
     * {@link ObStepReviewState#VERIFIED}, or back to {@code IN_PROGRESS}
     * the moment any row is {@link ObStepReviewState#REJECTED}.
     *
     * <p><b>The TAT clock is paused here</b>, on the same event
     * {@code WAITING_ON_CLIENT} uses. Charging an implementor for the time
     * their work spends in somebody else's inbox measures the wrong person.
     */
    PENDING_REVIEW,

    DONE,
    SKIPPED;

    /**
     * Whether a step in this status is finished with.
     *
     * <p>Named rather than compared inline because {@code PENDING_REVIEW}
     * makes "not open" and "terminal" different questions for the first
     * time: a task under review is closed to writes but is not settled, and
     * a caller testing the wrong one either lets an answer through during a
     * review or reports a Step done while a task under it is unread.
     */
    public boolean isTerminal() {
        return this == DONE || this == SKIPPED;
    }
}
