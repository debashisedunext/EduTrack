package com.edunext.edutrack.api.feature.auth;

/**
 * A-026 · the caller is authenticated, but their account is flagged
 * {@code must_change_password} and they asked for something other than changing
 * it.
 *
 * <p><b>403, not 401.</b> The distinction carries real behaviour on the client:
 * a 401 means "your credentials are no good, go and sign in", and the frontend's
 * interceptor answers it by refreshing and then redirecting to the login screen.
 * Neither would help here — the credentials are perfectly good and re-logging in
 * produces another token with the same flag, so a 401 would be an infinite
 * redirect loop back to a login that keeps succeeding. 403 says "we know who you
 * are and you may not do this yet", which is exactly true, and the distinct
 * {@code type} tells S-03 to route to the change-password form rather than to
 * the login card.
 *
 * <p>Thrown by nothing today. {@link PasswordChangeGate} is the decision and
 * A-032's filter chain is the only thing positioned to act on it — see that
 * class for why the two halves land in different tasks.
 */
class PasswordChangeRequiredException extends RuntimeException {

    PasswordChangeRequiredException() {
        super("Password change required", null, false, false);
    }
}
