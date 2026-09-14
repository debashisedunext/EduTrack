package com.edunext.edutrack.api.feature.onboarding.instances;

/**
 * A task list entry answered <b>False</b> with no reason.
 *
 * <h2>Why this is a refusal and not a default</h2>
 *
 * <p>{@code ck_ob_journey_step_items_remark} already rejects the row — the
 * constraint reads {@code answer IS NULL OR answer = 1 OR (remark IS NOT NULL
 * AND remark <> '')} — so without this the write fails as a constraint
 * violation and the caller gets a 500 describing a database object. The rule is
 * a product rule, not a storage detail, and the screen that has to render it
 * deserves a problem type it can switch on.
 *
 * <p><b>The alternative was to invent a remark</b>, which is worse than it
 * sounds: "False" on a task list is an exception somebody downstream has to act
 * on, and an exception whose reason reads {@code (none given)} is one that gets
 * actioned by asking the person who recorded it what they meant. The whole
 * value of the False arm is the sentence beside it.
 */
class StepItemRemarkRequiredException extends RuntimeException {

    private final long itemId;

    StepItemRemarkRequiredException(long itemId) {
        super("task list item " + itemId + " was answered False with no remark");
        this.itemId = itemId;
    }

    long itemId() {
        return itemId;
    }
}
