package com.edunext.edutrack.api.feature.portal;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.format.DateTimeFormatter;

/**
 * C-121 · RFC 9457 problem documents for {@link PortalAuthController}
 * ({@code contracts/CONVENTIONS.md} §3) — {@code AuthExceptionHandler}'s
 * shapes, one principal type over, scoped by {@code assignableTypes} on the
 * same precedent {@link ClientAccountExceptionHandler} follows.
 */
@RestControllerAdvice(assignableTypes = PortalAuthController.class)
class PortalAuthExceptionHandler {

    private static final URI INVALID_CREDENTIALS = URI.create("https://edutrack/errors/invalid-credentials");
    private static final URI ACCOUNT_LOCKED = URI.create("https://edutrack/errors/account-locked");
    private static final URI TOO_MANY_ATTEMPTS = URI.create("https://edutrack/errors/too-many-login-attempts");
    private static final URI INVALID_CREDENTIAL_TOKEN =
            URI.create("https://edutrack/errors/invalid-portal-credential-token");
    private static final URI INVALID_REFRESH_TOKEN =
            URI.create("https://edutrack/errors/invalid-portal-refresh-token");
    private static final URI PASSWORD_CHANGE_REQUIRED =
            URI.create("https://edutrack/errors/portal-password-change-required");

    @ExceptionHandler(PortalInvalidCredentialsException.class)
    ResponseEntity<ProblemDetail> handleInvalidCredentials(PortalInvalidCredentialsException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        problem.setType(INVALID_CREDENTIALS);
        problem.setTitle("Invalid credentials");
        problem.setDetail("The username or password is incorrect.");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
    }

    @ExceptionHandler(PortalAccountLockedException.class)
    ResponseEntity<ProblemDetail> handleLocked(PortalAccountLockedException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.LOCKED);
        problem.setType(ACCOUNT_LOCKED);
        problem.setTitle("Account locked");
        problem.setDetail("Too many failed sign-in attempts. Try again later.");
        problem.setProperty("lockedUntil", DateTimeFormatter.ISO_INSTANT.format(e.lockedUntil()));
        return ResponseEntity.status(HttpStatus.LOCKED).body(problem);
    }

    @ExceptionHandler(PortalTooManyLoginAttemptsException.class)
    ResponseEntity<ProblemDetail> handleThrottled(PortalTooManyLoginAttemptsException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        problem.setType(TOO_MANY_ATTEMPTS);
        problem.setTitle("Too many requests");
        problem.setDetail("Too many attempts. Try again shortly.");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, e.retryAfter().toSeconds())))
                .body(problem);
    }

    @ExceptionHandler(PortalInvalidCredentialTokenException.class)
    ResponseEntity<ProblemDetail> handleInvalidToken(PortalInvalidCredentialTokenException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.GONE);
        problem.setType(INVALID_CREDENTIAL_TOKEN);
        problem.setTitle("Link is no longer valid");
        problem.setDetail("This portal sign-in link has expired or has already been used. "
                + "Ask your account manager for a new one.");
        return ResponseEntity.status(HttpStatus.GONE).body(problem);
    }

    @ExceptionHandler(PortalInvalidRefreshTokenException.class)
    ResponseEntity<ProblemDetail> handleInvalidRefresh(PortalInvalidRefreshTokenException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        problem.setType(INVALID_REFRESH_TOKEN);
        problem.setTitle("Session expired");
        problem.setDetail("This session can no longer be renewed. Please sign in again.");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
    }

    @ExceptionHandler(PortalPasswordChangeRequiredException.class)
    ResponseEntity<ProblemDetail> handlePasswordChangeRequired(PortalPasswordChangeRequiredException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setType(PASSWORD_CHANGE_REQUIRED);
        problem.setTitle("Password change required");
        problem.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    }
}
