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
 * <p><b>Scoped to the auth feature's controllers</b> rather than declared
 * globally. A repository-wide {@code @RestControllerAdvice} is shared surface:
 * four streams would edit one file, and it is not Stream A's to introduce
 * unilaterally. Framework-level failures (a malformed body, an unreadable
 * request) are already RFC 9457 through {@code spring.mvc.problemdetails.enabled},
 * so nothing here needs to duplicate them.
 *
 * <p>A-026 added {@link MeController} to the list. It is a second controller
 * rather than a second mapping on {@link AuthController} because the contract
 * puts {@code changeOwnPassword} under {@code /me}, and it shares this advice
 * because it raises the same exceptions and must produce byte-identical problem
 * bodies — an endpoint that reports {@code invalid-access-token} with a
 * different shape is a second contract the frontend has to learn.
 *
 * <p>{@code type} is the stable part. {@code CONVENTIONS.md} §3 is explicit that
 * clients branch on {@code type} and never on {@code title} or {@code detail},
 * which are prose and may be reworded — so the URI below must not change once
 * the frontend switches on it.
 */
@RestControllerAdvice(assignableTypes = {AuthController.class, MeController.class})
class AuthExceptionHandler {

    private static final URI INVALID_CREDENTIALS = URI.create("https://edutrack/errors/invalid-credentials");
    private static final URI ACCOUNT_LOCKED = URI.create("https://edutrack/errors/account-locked");
    private static final URI INVALID_REFRESH_TOKEN = URI.create("https://edutrack/errors/invalid-refresh-token");
    private static final URI REFRESH_TOKEN_REUSE = URI.create("https://edutrack/errors/refresh-token-reuse");
    private static final URI INVALID_ACCESS_TOKEN = URI.create("https://edutrack/errors/invalid-access-token");
    private static final URI PASSWORD_UNCHANGED = URI.create("https://edutrack/errors/password-unchanged");
    private static final URI PASSWORD_CHANGE_REQUIRED = URI.create("https://edutrack/errors/password-change-required");
    private static final URI INVALID_RESET_TOKEN = URI.create("https://edutrack/errors/invalid-reset-token");
    private static final URI TOO_MANY_RESET_REQUESTS =
            URI.create("https://edutrack/errors/too-many-reset-requests");

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

    // ── A-026 · the password lifecycle ──────────────────────────────────────

    /**
     * A-026 · {@code currentPassword} was wrong on {@code PATCH /me/password}.
     *
     * <p><b>The same {@code type} as a refused login, with an accurate
     * {@code detail}.</b> {@code CONVENTIONS.md} §3 makes {@code type} the stable
     * part clients branch on, and "these credentials are not right" is genuinely
     * the same class of failure — a second URI meaning the same thing would be
     * one more branch for S-03 to get wrong. The prose differs because a form
     * with one password field on it must not tell the user their <i>username</i>
     * might be at fault.
     *
     * <p>Specific where login is deliberately vague, for the reason
     * {@link InvalidCurrentPasswordException} gives: this caller is already
     * authenticated, so there is no account to enumerate.
     */
    @ExceptionHandler(InvalidCurrentPasswordException.class)
    ResponseEntity<ProblemDetail> handleInvalidCurrentPassword(InvalidCurrentPasswordException ignored) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        problem.setType(INVALID_CREDENTIALS);
        problem.setTitle("Invalid credentials");
        problem.setDetail("The current password is incorrect.");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
    }

    /**
     * A-026 · the replacement is the password being replaced.
     *
     * <p>400 rather than 401: the caller authenticated correctly and knew their
     * own password, so nothing about their credentials failed — the request is
     * what does not make sense. Its own {@code type} so S-03 can attach the
     * message to the new-password field instead of parsing {@code detail}.
     */
    @ExceptionHandler(PasswordUnchangedException.class)
    ResponseEntity<ProblemDetail> handlePasswordUnchanged(PasswordUnchangedException ignored) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(PASSWORD_UNCHANGED);
        problem.setTitle("Password unchanged");
        problem.setDetail("The new password must be different from your current one.");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    /**
     * A-026 · an authenticated caller whose account still demands a password
     * change asked for something else.
     *
     * <p>403, and {@link PasswordChangeRequiredException} explains why a 401 here
     * would be an infinite redirect loop rather than a stricter answer.
     *
     * <p><b>Unreachable today</b>, because {@link PasswordChangeGate} is not
     * consulted until A-032's filter chain lands. Registered now so that when it
     * is, the refusal already has an agreed shape and a documented {@code type}
     * rather than arriving as a bare Spring Security 403 with an empty body.
     */
    @ExceptionHandler(PasswordChangeRequiredException.class)
    ResponseEntity<ProblemDetail> handlePasswordChangeRequired(PasswordChangeRequiredException ignored) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setType(PASSWORD_CHANGE_REQUIRED);
        problem.setTitle("Password change required");
        problem.setDetail("Set a new password before continuing.");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    }

    // ── A-027 · the password reset flow ─────────────────────────────────────

    /**
     * A-027 · {@code 410 Gone} — the reset token is expired, already redeemed, or
     * was never issued.
     *
     * <p>One body for all three, per the contract and for
     * {@link InvalidResetTokenException}'s reason: this endpoint is
     * unauthenticated, so distinguishing them would let anyone holding a token
     * discover whether it was ever real and whether the account has already
     * recovered.
     *
     * <p><b>No cookie is cleared here.</b> The caller of a reset has no session
     * to end — that is why they are resetting — and a {@code Set-Cookie} on this
     * response would be an unauthenticated request mutating a browser's cookie
     * jar for no reason.
     */
    @ExceptionHandler(InvalidResetTokenException.class)
    ResponseEntity<ProblemDetail> handleInvalidResetToken(InvalidResetTokenException ignored) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.GONE);
        problem.setType(INVALID_RESET_TOKEN);
        problem.setTitle("Reset link is no longer valid");
        problem.setDetail("This password reset link has expired or has already been used. "
                + "Request a new one.");
        return ResponseEntity.status(HttpStatus.GONE).body(problem);
    }

    /**
     * A-027 · {@code 429} with {@code Retry-After}, as the contract's
     * {@code TooManyRequests} response declares.
     *
     * <p>The header is the point. A 429 without one tells a client to back off
     * without saying how far, and the honest ones then retry immediately and stay
     * refused while the dishonest ones were never going to read it anyway.
     * {@code Retry-After} is seconds here rather than an HTTP-date — both are
     * legal per RFC 9110, and a duration cannot be misread by a client whose
     * clock disagrees with ours.
     */
    @ExceptionHandler(TooManyResetRequestsException.class)
    ResponseEntity<ProblemDetail> handleTooManyResetRequests(TooManyResetRequestsException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        problem.setType(TOO_MANY_RESET_REQUESTS);
        problem.setTitle("Too many requests");
        problem.setDetail("Too many password reset requests. Try again shortly.");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(exception.retryAfter().toSeconds()))
                .body(problem);
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
