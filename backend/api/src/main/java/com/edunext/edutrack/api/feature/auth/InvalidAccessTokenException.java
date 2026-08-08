package com.edunext.edutrack.api.feature.auth;

/**
 * A-025 · the access token presented to an authenticated route was missing,
 * malformed, not signed by us, issued by someone else, or expired.
 *
 * <p>One type for all of them, like {@link InvalidCredentialsException} and
 * {@link InvalidRefreshTokenException} before it. Here the caller is probing
 * with a token they constructed, and telling them <i>which</i> check failed
 * turns the endpoint into a forging tutor — "signature bad" versus "expired"
 * versus "wrong issuer" narrates exactly how close an attempt came.
 *
 * <p>Thrown today only by {@link LogoutService}, which authenticates its own
 * caller because A-032's filter chain does not exist yet. When that chain lands
 * it will reject these requests before a controller is reached and this becomes
 * a fallback rather than the main path — the {@code type} URI must stay stable
 * across that change, since the frontend branches on it.
 *
 * <p>No stack trace: unauthenticated control flow, thrown once per bad request,
 * pointing at the same line every time.
 */
class InvalidAccessTokenException extends RuntimeException {

    InvalidAccessTokenException() {
        super("Invalid access token", null, false, false);
    }
}
