package com.edunext.edutrack.api.feature.portal;

/**
 * B-126 · 422 — the client has no active primary SPOC, so a credential mail has
 * nowhere to go.
 *
 * <p>B-103's own note is the argument: "the kickoff mail, the one-time portal
 * password and every sign-off request address the primary SPOC, so with none
 * they go to nobody and nothing says so". Creating the account anyway would
 * leave a login nobody was told about, which is B-102's
 * {@code createPortalLogin} failure exactly — "a boarder ticks the box, sees a
 * 201, tells the client their credentials are coming, and nothing was ever
 * sent".
 *
 * <p>422 rather than 409: nothing conflicts, the request is simply not
 * satisfiable in the client's current state, and the operator has a concrete
 * action — appoint a primary SPOC, then try again.
 */
class NoPrimaryContactException extends RuntimeException {

    NoPrimaryContactException(long obClientId) {
        super("onboarding client " + obClientId + " has no active primary SPOC to send credentials to");
    }
}
