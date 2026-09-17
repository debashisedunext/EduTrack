package com.edunext.edutrack.api.feature.onboarding.signoff;

/**
 * 422 {@code ob-signoff-step-not-signoffable} — a sign-off was asked for on a
 * step whose template never flagged one.
 *
 * <p>Reads the step's own {@code requires_signoff} snapshot rather than the live
 * template, on C-104's precedent and because it is the same field the completion
 * gate reads. Asking against an unflagged step would write a real acceptance
 * into the legal record for something no client was ever going to be shown, and
 * nothing would ever consult it.
 */
class ObSignoffNotSignoffableException extends RuntimeException {

    ObSignoffNotSignoffableException(long stepId) {
        super("Step " + stepId + " is not flagged for client sign-off, so nothing gates on one.");
    }
}
