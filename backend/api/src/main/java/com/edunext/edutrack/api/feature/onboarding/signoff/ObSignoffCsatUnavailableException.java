package com.edunext.edutrack.api.feature.onboarding.signoff;

/**
 * 422 — the portal asked for the go-live survey on a sign-off that is not
 * offering one.
 *
 * <p>The public surface's own pair of refusals, {@code CsatNotOfferedException}
 * and {@code CsatAlreadySubmittedException}, collapsed into one type the portal
 * package can catch. Both are package-private, and translating is deliberately
 * preferred to widening them: the survey does not draw the distinction between
 * "not a completed go-live" and "already answered" anywhere a client can act
 * on, and {@code CsatNotOfferedException}'s own javadoc collapses two cases
 * into itself for that same reason.
 *
 * <p>The underlying message is carried through, so a developer reading the
 * problem document still gets the specific sentence; what is not carried is a
 * type a caller could branch on.
 */
public class ObSignoffCsatUnavailableException extends RuntimeException {

    public ObSignoffCsatUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
