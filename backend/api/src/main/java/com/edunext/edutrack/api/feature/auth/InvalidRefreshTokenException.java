package com.edunext.edutrack.api.feature.auth;

/**
 * A-024 · the ordinary refusal of a refresh: the cookie was missing, the token
 * was never issued, it has expired, its family was revoked, or the account
 * behind it is no longer active.
 *
 * <p><b>One exception for all of those, deliberately</b> — the same reasoning as
 * {@link InvalidCredentialsException}, applied to the other unauthenticated
 * endpoint. Distinguishing "expired" from "never existed" tells a caller
 * holding a random value whether it happened to be a real token, which is a
 * validity oracle over a 256-bit space that would otherwise give no feedback at
 * all. Distinguishing "your account was deactivated" tells an outsider holding a
 * stale cookie something about an employee.
 *
 * <p><b>{@link RefreshTokenReuseException} is the one case that <i>is</i>
 * distinguished</b>, and only because it is reachable exclusively by someone
 * holding a token this server genuinely issued and consumed — see that class for
 * why telling them costs nothing and telling the victim matters.
 *
 * <p>No stack trace: this is control flow on an unauthenticated path and the
 * trace would point at the same handful of lines every time.
 */
class InvalidRefreshTokenException extends RuntimeException {

    InvalidRefreshTokenException() {
        super("Invalid refresh token", null, false, false);
    }
}
