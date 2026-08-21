package com.edunext.edutrack.api.feature.transitions;

/**
 * C-042 · 400 — {@code toStageCode} was omitted on an action that cannot
 * default it.
 *
 * <p>Only {@code FORWARD} has an unambiguous default (the template's next
 * stage after the one being left). Every other action code names a
 * deliberate destination — {@code REWORK} sends the ticket back to a specific
 * earlier stage, {@code OVERRIDE} to anywhere at all — and guessing one would
 * move a ticket somewhere nobody asked for.
 *
 * <p><b>Corrected by C-047.</b> This javadoc used to say "{@code SKIP} names
 * which stage is being skipped past", which read as though {@code toStageCode}
 * meant something different on that one action. It does not: the stage being
 * skipped is always the one the ticket is standing in, and
 * {@code toStageCode} is the destination on all four routes. {@code SkipService}
 * records why, and defaults the destination itself rather than asking this
 * class to make an exception for it — so a {@code SKIP} reaching
 * {@link TransitionService#advance} without one is still refused here, and
 * should be.
 */
class ToStageRequiredException extends RuntimeException {

    ToStageRequiredException(String actionCode) {
        super("toStageCode is required for action_code " + actionCode
                + " — only FORWARD defaults to the workflow template's next stage.");
    }
}
