package com.edunext.edutrack.api.feature.auth;

/**
 * A-029 · the submitted six-digit code, or recovery code, did not verify.
 *
 * <p><b>One exception for both, and for every reason either can fail.</b> Wrong
 * code, a code from outside the drift window, a code already spent this step,
 * and an unrecognised recovery code all arrive here. Telling them apart would
 * hand a caller a clock-synchronisation oracle — "that was close" is exactly the
 * feedback that turns brute-forcing six digits into a search rather than a
 * guess — and would reveal whether an account still has recovery codes left.
 *
 * <p>Deliberately distinct from {@link InvalidCredentialsException} even though
 * both are 401s on the login path. By the time this is thrown the password has
 * already verified, so the two carry different information and S-04 needs to
 * tell them apart: one means "re-enter your password", the other means "your
 * password was fine, check your authenticator".
 */
class InvalidTotpCodeException extends RuntimeException {

    InvalidTotpCodeException() {
        super("Two-factor code is not valid", null, false, false);
    }
}
