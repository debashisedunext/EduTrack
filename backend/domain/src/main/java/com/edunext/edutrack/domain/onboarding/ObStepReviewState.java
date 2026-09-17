package com.edunext.edutrack.domain.onboarding;

/**
 * The OB Manager's verdict on one check-list row —
 * {@code ob_journey_step_items.review_state}, {@code V20260916_1700}.
 *
 * <h2>The second ledger on the row</h2>
 *
 * <p>{@link ObJourneyStepItem#getAnswer()} is the implementor's: <em>did I do
 * the thing</em>, three-state because it may be unanswered. This is the
 * manager's: <em>does it hold</em>, three-state for the same reason. They are
 * deliberately separate columns — one column would mean the verdict
 * overwrites the claim it is judging, and nothing would then record what was
 * asserted before it was rejected.
 *
 * <h2>Why the words differ from the answer's</h2>
 *
 * <p>The screen says Completed / Not completed for an answer and Verified /
 * Rejected for a verdict. That is not decoration: a row reading
 * "Completed / Completed" tells a reader nothing about which of the two
 * people said it, and the two columns sit side by side.
 */
public enum ObStepReviewState {

    /** No verdict yet. What a row is created with, and what it returns to on resubmission. */
    NOT_REVIEWED,

    /**
     * Passed — and <b>terminal for the row</b>.
     *
     * <p>{@code ObJourneyStepLifecycleService} refuses every later write to a
     * verified row, from either person: the implementor may not change an
     * answer that has been accepted, and the manager is never shown it again.
     * That refusal is what makes "only the rejected rows reopen" a fact about
     * the data rather than a claim the screen makes.
     */
    VERIFIED,

    /**
     * Sent back, and never without a reason.
     *
     * <p>The reason is the row's own {@code remark} — there is no separate
     * column — and {@code ck_ob_journey_step_items_reject_reason} holds it.
     * A row in this state is the one thing an implementor may edit on a
     * returned task.
     */
    REJECTED;

    /** A verdict has been recorded, either way. */
    public boolean isDecided() {
        return this != NOT_REVIEWED;
    }
}
