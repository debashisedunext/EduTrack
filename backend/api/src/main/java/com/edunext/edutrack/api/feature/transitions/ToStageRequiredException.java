package com.edunext.edutrack.api.feature.transitions;

/**
 * C-042 · 400 — {@code toStageCode} was omitted on an action that cannot
 * default it.
 *
 * <p>Only {@code FORWARD} has an unambiguous default (the template's next
 * stage after the one being left). Every other action code names a
 * deliberate destination — {@code REWORK} sends the ticket back to a specific
 * earlier stage, {@code SKIP} names which stage is being skipped past — and
 * guessing one would move a ticket somewhere nobody asked for.
 */
class ToStageRequiredException extends RuntimeException {

    ToStageRequiredException(String actionCode) {
        super("toStageCode is required for action_code " + actionCode
                + " — only FORWARD defaults to the workflow template's next stage.");
    }
}
