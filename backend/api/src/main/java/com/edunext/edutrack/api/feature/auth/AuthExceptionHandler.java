package com.edunext.edutrack.api.feature.auth;

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
}
