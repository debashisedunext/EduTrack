package com.edunext.edutrack.api.feature.auth;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * A-020 · turns a refused login into the RFC 9457 problem document the contract
 * promises ({@code CONVENTIONS.md} §3).
 *
 * <p><b>Scoped to {@link AuthController}</b> rather than declared globally.
 * A repository-wide {@code @RestControllerAdvice} is shared surface: four
 * streams would edit one file, and it is not Stream A's to introduce
 * unilaterally. Framework-level failures (a malformed body, an unreadable
 * request) are already RFC 9457 through {@code spring.mvc.problemdetails.enabled},
 * so nothing here needs to duplicate them.
 *
 * <p>{@code type} is the stable part. {@code CONVENTIONS.md} §3 is explicit that
 * clients branch on {@code type} and never on {@code title} or {@code detail},
 * which are prose and may be reworded — so the URI below must not change once
 * the frontend switches on it.
 */
@RestControllerAdvice(assignableTypes = AuthController.class)
class AuthExceptionHandler {

    private static final URI INVALID_CREDENTIALS = URI.create("https://edutrack/errors/invalid-credentials");
    private static final URI ACCOUNT_LOCKED = URI.create("https://edutrack/errors/account-locked");
    private static final URI INVALID_REFRESH_TOKEN = URI.create("https://edutrack/errors/invalid-refresh-token");
    private static final URI REFRESH_TOKEN_REUSE = URI.create("https://edutrack/errors/refresh-token-reuse");
    private static final URI INVALID_ACCESS_TOKEN = URI.create("https://edutrack/errors/invalid-access-token");

    private final RefreshTokenIssuer refreshTokens;

    AuthExceptionHandler(RefreshTokenIssuer refreshTokens) {
        this.refreshTokens = refreshTokens;
    }

    /**
     * One handler, one status, one body — for unknown users, wrong passwords
     * and deactivated accounts alike. The {@code detail} string is fixed rather
     * than derived from the exception, so there is no path by which a future
     * change leaks the distinction that {@link InvalidCredentialsException}
     * was designed not to carry.
     */
    @ExceptionHandler(InvalidCredentialsException.class)
    ResponseEntity<ProblemDetail> handleInvalidCredentials(InvalidCredentialsException ignored) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        problem.setType(INVALID_CREDENTIALS);
        problem.setTitle("Invalid credentials");
        problem.setDetail("The username or password is incorrect.");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
    }

    /**
     * A-021 · {@code 423 Locked}, and the only login response that says
     * anything specific — reachable only once the password has verified.
     *
     * <p>{@code lockedUntil} is an extension property rather than prose in
     * {@code detail}: a client that wants to render "try again in 12 minutes"
     * should read a timestamp, not parse an English sentence that A-030 may
     * reword. RFC 9457 allows extra members on the problem object for exactly
     * this.
     */
    @ExceptionHandler(AccountLockedException.class)
    ResponseEntity<ProblemDetail> handleAccountLocked(AccountLockedException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.LOCKED);
        problem.setType(ACCOUNT_LOCKED);
        problem.setTitle("Account locked");
        problem.setDetail("Too many failed sign-in attempts. Try again later, or ask an administrator.");
        if (exception.lockedUntil() != null) {
            problem.setProperty("lockedUntil", exception.lockedUntil().toString());
        }
        return ResponseEntity.status(HttpStatus.LOCKED).body(problem);
    }

    // ── A-024 · refresh refusals ────────────────────────────────────────────

    /**
     * A-024 · the ordinary refusal — missing, expired, unknown, revoked family,
     * different device, or a deactivated account, all with one body.
     *
     * <p>The same {@code detail} for all of them, for the same reason the login
     * refusal has one: a caller who cannot tell "expired" from "never issued"
     * learns nothing about which random values happen to be real tokens. See
     * {@link InvalidRefreshTokenException}.
     */
    @ExceptionHandler(InvalidRefreshTokenException.class)
    ResponseEntity<ProblemDetail> handleInvalidRefreshToken(InvalidRefreshTokenException ignored) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        problem.setType(INVALID_REFRESH_TOKEN);
        problem.setTitle("Session expired");
        problem.setDetail("This session can no longer be renewed. Please sign in again.");
        return clearingCookie(HttpStatus.UNAUTHORIZED).body(problem);
    }

    /**
     * A-024 · reuse, and the only auth failure that says what actually happened.
     *
     * <p>By the time this runs {@link RefreshRotationService} has already revoked
     * the family — the revocation is the response, and this is only its
     * reporting. The distinct {@code type} exists so S-01 can tell the user
     * <i>why</i> they were signed out; a generic "session expired" would hide a
     * security event from the one person able to act on it, and reveals nothing
     * to an attacker who already knows the token they replayed was real.
     */
    @ExceptionHandler(RefreshTokenReuseException.class)
    ResponseEntity<ProblemDetail> handleRefreshTokenReuse(RefreshTokenReuseException ignored) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        problem.setType(REFRESH_TOKEN_REUSE);
        problem.setTitle("Session ended for your security");
        problem.setDetail("This session was signed out because its sign-in token was used more "
                + "than once, which can mean it was copied. Please sign in again.");
        return clearingCookie(HttpStatus.UNAUTHORIZED).body(problem);
    }

    /**
     * A-025 · the access token on an authenticated route was missing, malformed,
     * forged, or expired.
     *
     * <p>Uniform across all of those — see {@link InvalidAccessTokenException}
     * for why naming the failed check would help a forger more than a user.
     *
     * <p><b>No cookie is cleared here</b>, unlike the refresh refusals above. A
     * logout that could not authenticate has not ended anything, and stripping
     * the refresh cookie on the way out would half-end a session the caller was
     * never proven to own — an unauthenticated request that logs someone out by
     * failing. The cookie is only cleared on a logout that actually succeeded.
     */
    @ExceptionHandler(InvalidAccessTokenException.class)
    ResponseEntity<ProblemDetail> handleInvalidAccessToken(InvalidAccessTokenException ignored) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        problem.setType(INVALID_ACCESS_TOKEN);
        problem.setTitle("Not signed in");
        problem.setDetail("A valid access token is required for this request.");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
    }

    /**
     * Every refresh refusal takes the dead cookie away with it.
     *
     * <p>Left in place, a browser holding a token from a revoked family replays
     * it on every attempt: a 401 loop the user cannot break without clearing
     * cookies by hand, and a useless credential on the wire until it expires.
     * Built by {@link RefreshTokenIssuer} rather than assembled here, so the
     * name and {@code Path} are guaranteed to match the cookie being replaced —
     * a clearing cookie that differs in either is a second cookie, not a
     * replacement, and clears nothing.
     */
    private ResponseEntity.BodyBuilder clearingCookie(HttpStatus status) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, refreshTokens.clearing().toString());
    }
}
