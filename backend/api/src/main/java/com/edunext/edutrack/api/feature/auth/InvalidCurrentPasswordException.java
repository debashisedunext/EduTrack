package com.edunext.edutrack.api.feature.auth;

/**
 * A-026 · the {@code currentPassword} on {@code PATCH /me/password} did not
 * match.
 *
 * <p><b>Distinct from {@link InvalidCredentialsException}, but deliberately
 * mapped to the same {@code type} URI.</b> The two exist separately so the
 * problem {@code detail} can be accurate — "the current password is incorrect"
 * belongs on a form with one password field, and login's "the username or
 * password is incorrect" would be confusing prose there. The stable part,
 * {@code type}, stays {@code invalid-credentials}, so S-03 branches on the same
 * URI the rest of the auth feature already publishes rather than on a new one
 * that means the same thing.
 *
 * <p><b>Being specific is safe here, unlike at login.</b> The caller has already
 * proved who they are with a verified access token, so there is no account to
 * enumerate and nothing to learn: they are being told a fact about their own
 * password. The uniformity rules that govern {@link AuthenticationService} are
 * about strangers, and this endpoint has none.
 */
class InvalidCurrentPasswordException extends RuntimeException {

    InvalidCurrentPasswordException() {
        super("Current password does not match", null, false, false);
    }
}
