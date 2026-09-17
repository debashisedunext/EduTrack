package com.edunext.edutrack.api.feature.onboarding.instances;

/**
 * A rejected check-list row was left with no reason in its remark.
 *
 * <h2>Two callers reach this, and the second one is the surprising one</h2>
 *
 * <p>The obvious one is the manager: rejecting a row without saying why sends
 * work back that nobody can act on, so {@code reviewItem} refuses it.
 *
 * <p>The second is the <b>implementor reworking that row</b>. The rejection
 * reason rides on the row's own {@code remark} — there is no separate column,
 * see {@code V20260916_1700}'s header for why — so while the row is still
 * {@code REJECTED}, blanking the remark would leave a rejection with no
 * reason on it. {@code ck_ob_journey_step_items_reject_reason} refuses that
 * at the database, and a constraint violation surfaces as a 500 carrying
 * nothing a reader can act on. This exception is what turns it into a 422
 * that says what to do instead.
 *
 * <p><b>Not to be confused with D-17.</b> The implementor's own remark on a
 * {@code false} answer is optional and stays optional — {@code
 * V20260916_1520} dropped that rule deliberately (PLAN.md §4). The two rules
 * share a column and point in opposite directions: an answer needs no reason,
 * a rejection always does.
 */
class RejectReasonRequiredException extends RuntimeException {

    private final long itemId;

    RejectReasonRequiredException(long itemId) {
        super("check-list row " + itemId + " is rejected, so its remark must say why — a rejected row "
                + "cannot be left without a reason");
        this.itemId = itemId;
    }

    long itemId() {
        return itemId;
    }
}
