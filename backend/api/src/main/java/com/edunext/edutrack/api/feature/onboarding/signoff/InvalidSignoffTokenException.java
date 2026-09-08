package com.edunext.edutrack.api.feature.onboarding.signoff;

/**
 * A-120 · the one refusal the public sign-off surface has.
 *
 * <h2>One exception for four different facts, on purpose</h2>
 *
 * <p>A token that never existed, one that has expired, one whose sign-off was
 * cancelled and one already signed all throw this, and the handler renders all
 * four identically. The contract states the rule above these routes:
 * distinguishing them "would turn this into an oracle, and the caller who
 * deserves a specific answer — the real contact — has an email telling them
 * what to do."
 *
 * <p>It carries no detail for the same reason. A message, a code, even a
 * different {@code type} URI per cause would rebuild the oracle inside the
 * response body, which is the one place somebody probing would look first.
 */
public class InvalidSignoffTokenException extends RuntimeException {

    public InvalidSignoffTokenException() {
        // No cause and no message. Anything either carried would eventually be
        // logged beside the token it refused, and a log line pairing a token
        // with "expired" is the oracle again, written down.
        super("Invalid sign-off token");
    }
}
