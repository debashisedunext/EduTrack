package com.edunext.edutrack.api.feature.auth;

/**
 * A-020 · the <b>only</b> way a login is refused.
 *
 * <p>It carries no field, no reason and no user reference, and that is the
 * whole point. Blueprint §10.1 requires generic error messages; the contract
 * spells out why — "wrong username, wrong password and unknown user all return
 * the same {@code invalid-credentials} problem. Saying which field was wrong is
 * a username-enumeration oracle."
 *
 * <p>An exception type that <i>could</i> carry a reason would eventually be
 * given one, by someone reasonably trying to improve an error message. Leaving
 * nowhere to put it is the cheapest possible enforcement.
 *
 * <p>Deactivated accounts throw this too, rather than something more specific:
 * "this account exists but is disabled" identifies a real employee to an
 * outsider just as effectively as "this username exists".
 *
 * <p>No stack trace is captured — this is control flow on an unauthenticated
 * path, thrown once per failed guess, and the trace would only ever point back
 * at the same line.
 */
class InvalidCredentialsException extends RuntimeException {

    InvalidCredentialsException() {
        super("Invalid credentials", null, false, false);
    }
}
