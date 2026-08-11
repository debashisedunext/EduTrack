package com.edunext.edutrack.api.feature.auth;

/**
 * A-029 · a confirm or disable arrived for an account that has no enrolment in
 * progress.
 *
 * <p>Covers both "you never called setup" and "your unconfirmed secret was
 * replaced or cleared". 409 rather than 404: the account exists and the caller
 * is authenticated as it — what is absent is the <i>state</i> the request
 * assumes, which is a conflict with reality rather than a missing resource.
 */
class TwoFactorNotEnrolledException extends RuntimeException {

    TwoFactorNotEnrolledException() {
        super("No two-factor enrolment is in progress", null, false, false);
    }
}
