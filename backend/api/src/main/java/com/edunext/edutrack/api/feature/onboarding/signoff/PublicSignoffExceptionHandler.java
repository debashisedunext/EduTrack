package com.edunext.edutrack.api.feature.onboarding.signoff;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * A-120 · renders the surface's two refusals, and only these two.
 *
 * <h2>One 401, byte for byte</h2>
 *
 * <p>The contract: "A bad token, an expired token, a cancelled sign-off and a
 * token that never existed all answer the same {@code 401} with the same
 * body." This is where that is true. The detail is a fixed string built here
 * rather than taken from the exception, so no future cause can smuggle its own
 * wording into the response by throwing with a message.
 *
 * <p>No {@code instance} either, unlike the chain's other problem responses.
 * The path is already known to the caller and adding it buys nothing — but it
 * would make the four refusals differ in length, and a length that varies with
 * the route is a small oracle in a place that has been careful not to have one.
 *
 * <h2>Scoped by package, not global</h2>
 *
 * <p>{@code basePackageClasses} rather than a global advice: both exceptions
 * are about the public token surface and mean nothing elsewhere, and a global
 * handler for a feature-local type is how an advice class becomes a junk drawer
 * every stream edits. It also means an ordinary authenticated route cannot
 * accidentally start answering this 401 by throwing the same type.
 */
@RestControllerAdvice(basePackageClasses = PublicSignoffExceptionHandler.class)
class PublicSignoffExceptionHandler {

    private static final URI INVALID_TOKEN =
            URI.create("https://edutrack/errors/invalid-signoff-token");
    private static final URI RATE_LIMITED =
            URI.create("https://edutrack/errors/too-many-requests");

    /**
     * The generic refusal. Four causes, one response.
     *
     * <p>401 rather than 404: the caller presented a credential and it did not
     * work, which is what 401 means. A 404 would additionally imply something
     * about the route, and the route is public and known.
     */
    @ExceptionHandler(InvalidSignoffTokenException.class)
    ResponseEntity<ProblemDetail> invalidToken(InvalidSignoffTokenException ignored) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        problem.setType(INVALID_TOKEN);
        problem.setTitle("This link is no longer valid");
        problem.setDetail("Ask your onboarding contact to send a new link.");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    /**
     * <p>{@code Retry-After} in seconds rather than an HTTP-date — both are
     * legal per RFC 9110 and seconds is what {@code AuthExceptionHandler}
     * already emits, so a client has one format to parse across the product.
     */
    @ExceptionHandler(SignoffRateLimitedException.class)
    ResponseEntity<ProblemDetail> rateLimited(SignoffRateLimitedException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        problem.setType(RATE_LIMITED);
        problem.setTitle("Too many attempts");
        problem.setDetail("Wait a few minutes and try again.");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(e.retryAfter().toSeconds()))
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
