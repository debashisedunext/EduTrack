package com.edunext.edutrack.api.feature.auth;

/**
 * A-029 · the password was right, but this account has 2FA enabled and the
 * request carried no code. Blueprint §10.1: {@code 2FA enabled? → TOTP
 * challenge}.
 *
 * <p><b>Reported only after the password verifies, and that ordering is the
 * whole design.</b> Saying "this account needs a code" to someone who has not
 * proved the password would confirm both that the account exists and that it is
 * protected — a list of exactly which employees are worth phishing. The rule is
 * A-021's, applied to a second factor: a specific answer is owed only to
 * somebody who has already demonstrated they hold the first one.
 *
 * <p>Carries no user id and no partial token. A "2FA pending" credential is the
 * obvious shape for this and is one this design deliberately avoids: it is a
 * half-authenticated state that has to be stored, expired and protected, and
 * every one of those is somewhere the second factor can be sidestepped. The
 * contract's {@code LoginRequest.totpCode} lets the client simply resubmit
 * username, password and code together, so there is no intermediate state at
 * all.
 */
class TwoFactorRequiredException extends RuntimeException {

    TwoFactorRequiredException() {
        super("A two-factor code is required", null, false, false);
    }
}
