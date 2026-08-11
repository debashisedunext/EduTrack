package com.edunext.edutrack.api.feature.auth;

/**
 * A-029 · setup or confirm arrived for an account that already has 2FA on.
 *
 * <p><b>Refusing this is a security decision, not tidiness.</b> Letting an
 * authenticated caller silently re-enrol would let anyone holding a stolen
 * fifteen-minute access token swap the second factor for one of their own —
 * converting a short-lived theft into a permanent foothold on the account, and
 * doing it through the very feature meant to prevent that.
 *
 * <p>Re-enrolling therefore means disabling first, and disabling requires the
 * password (see {@code MeController}) — which is precisely what the holder of a
 * stolen token does not have.
 *
 * <p>409 rather than 400: the request is well-formed and the caller is who they
 * say they are; it conflicts with the account's current state.
 */
class TwoFactorAlreadyEnabledException extends RuntimeException {

    TwoFactorAlreadyEnabledException() {
        super("Two-factor authentication is already enabled", null, false, false);
    }
}
