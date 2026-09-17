package com.edunext.edutrack.domain.onboarding;

/**
 * Whose desk one check-list row is on —
 * {@code ob_journey_step_items.row_state}, {@code V20260917_1210}.
 *
 * <h2>The row is the unit of work, not the task</h2>
 *
 * <p>A review used to be a property of the whole task: an implementor
 * submitted a complete check list and a manager read a complete check list,
 * and the task's status was what froze everything in between. That shape
 * could not express either of the two things people actually do — finishing
 * two rows of five and wanting those two looked at now, or reading three of
 * the five that arrived and leaving the rest until later.
 *
 * <p>So the state machine lives here instead, and a task may hold a row being
 * worked, a row waiting on the manager and a row already approved at the same
 * time. None of that is a conflict.
 *
 * <h2>Two columns, two questions</h2>
 *
 * <p>This says <em>where the row is</em>. {@link ObStepReviewState} says
 * <em>what the reviewer decided</em> — draft while the row is {@link #SENT},
 * final once it has been sent back. Collapsing them into one column would
 * make "rejected, reason still being typed" unsayable, and that is the state
 * a reviewer occupies for as long as they are writing the reason.
 */
public enum ObStepRowState {

    /**
     * With its implementor, never yet sent. What every row is created as.
     */
    DRAFT,

    /**
     * With the manager, waiting for a verdict to be released.
     *
     * <p>Frozen to the implementor — an answer that changed while a manager
     * was reading it would make their verdict describe something they never
     * saw. That is the one guarantee the old task-level gate bought, kept
     * here at row granularity where it costs the row's neighbours nothing.
     */
    SENT,

    /**
     * Approved, and terminal. Shut to both people for good.
     */
    VERIFIED,

    /**
     * Sent back, and never without a reason.
     *
     * <p>The reason is the row's own {@code remark}. A rejected row returns
     * <em>unanswered</em> — the claim it carried is withdrawn with the
     * verdict — so its implementor has to assert the work again rather than
     * resubmit what was already refused.
     */
    REJECTED;

    /** With its implementor: theirs to answer, and theirs to send. */
    public boolean isWithImplementor() {
        return this == DRAFT || this == REJECTED;
    }

    /** Out for review — nobody but the reviewer may move it. */
    public boolean isOut() {
        return this == SENT;
    }

    /** A verdict has come back, either way — an outcome the implementor may not have seen. */
    public boolean isOutcome() {
        return this == VERIFIED || this == REJECTED;
    }
}
