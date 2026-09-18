package com.edunext.edutrack.api.feature.onboarding.signoff;

/**
 * 422 {@code ob-signoff-journey-incomplete} — a {@code GO_LIVE} sign-off was
 * asked for while services on the journey are still running.
 *
 * <p>Plan section 5.9 makes Live-Green require every journey complete. The
 * contract's own reason for refusing early: "asking a client to sign off a
 * delivery that is still running is how a sign-off record stops meaning
 * anything".
 *
 * <p>This is the refusal {@code SignoffPanel} cannot predict from the row alone,
 * which is why it renders the go-live panel and lets the server answer.
 */
class ObSignoffJourneyIncompleteException extends RuntimeException {

    ObSignoffJourneyIncompleteException(long journeyId, long outstanding) {
        super("Journey " + journeyId + " still has " + outstanding
                + " service(s) that are not finished. A go-live sign-off is asked for once "
                + "the delivery is complete.");
    }
}
