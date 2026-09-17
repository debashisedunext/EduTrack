package com.edunext.edutrack.api.feature.onboarding.instances;

/**
 * A write was attempted on a check-list row an OB Manager has already
 * verified — by either person.
 *
 * <h2>This is the refusal the whole feature rests on</h2>
 *
 * <p>"Only the rejected rows reopen" is drawn on the screen by disabling two
 * controls, and a disabled control is a claim rather than a guarantee. This
 * is the guarantee: {@code VERIFIED} is terminal for the row, and the service
 * refuses every later write to it.
 *
 * <p>It closes the hole in both directions, which is why it is not simply an
 * ownership check:
 *
 * <ul>
 *   <li>the <b>implementor</b> may not change an answer that has been
 *       accepted — otherwise a resubmission could quietly alter a row the
 *       manager passed in an earlier round and will never be shown again;</li>
 *   <li>the <b>manager</b> may not revisit their own verdict — a row they
 *       verified in round one is not put back in front of them in round two,
 *       so a write to it would be a decision made outside any review.</li>
 * </ul>
 *
 * @see com.edunext.edutrack.domain.onboarding.ObStepReviewState#VERIFIED
 */
class StepItemAlreadyVerifiedException extends RuntimeException {

    private final long itemId;

    StepItemAlreadyVerifiedException(long itemId) {
        super("check-list row " + itemId + " has been verified by an OB Manager and is closed to further "
                + "changes");
        this.itemId = itemId;
    }

    long itemId() {
        return itemId;
    }
}
