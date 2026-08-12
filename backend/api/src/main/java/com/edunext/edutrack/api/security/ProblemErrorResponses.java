package com.edunext.edutrack.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;

/**
 * A-032 · RFC 9457 bodies for the two refusals the filter chain produces itself.
 *
 * <h2>Why these have to be written by hand</h2>
 *
 * <p>{@code AuthExceptionHandler} covers everything thrown from inside a
 * controller, but the chain refuses <b>before</b> any controller is reached, so
 * {@code @ExceptionHandler} never runs. Spring Security's defaults answer with an
 * empty body and a {@code WWW-Authenticate} header.
 *
 * <p>That would make these the only two responses in the application the
 * frontend cannot parse. {@code problemTypes.ts} branches on the {@code type}
 * URI to tell "your session ended, sign in again" from "that failed, try again",
 * and an empty body gives it nothing to branch on — so the one response that
 * means "your session ended" would be the one it could not recognise.
 *
 * <h2>The 401 says nothing about which check failed</h2>
 *
 * <p>Missing header, malformed token, wrong signature, expired, revoked: all
 * become the same {@code invalid-access-token}. This mirrors
 * {@code AccessTokenVerifier}'s own flattening, for its reason — naming the
 * failed check tells someone probing with forged tokens exactly how close they
 * came, and "expired" versus "revoked" in particular tells them whether the
 * account they are holding a token for is still live.
 *
 * <p>The body is written with a plain {@link ObjectMapper} rather than the
 * message converters, because those live above the security chain and are not
 * available at this point in the request.
 */
@Component
class ProblemErrorResponses implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final URI INVALID_ACCESS_TOKEN = URI.create("https://edutrack/errors/invalid-access-token");
    private static final URI FORBIDDEN = URI.create("https://edutrack/errors/forbidden");

    private final ObjectMapper objectMapper;

    ProblemErrorResponses(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Unauthenticated. Deliberately identical in wording to
     * {@code AuthExceptionHandler#handleInvalidAccessToken}, so a caller cannot
     * tell whether the chain or a controller refused them — that difference
     * would map directly onto which routes exist.
     */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         org.springframework.security.core.AuthenticationException ignored) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        problem.setType(INVALID_ACCESS_TOKEN);
        problem.setTitle("Not signed in");
        problem.setDetail("A valid access token is required for this request.");
        write(request, response, HttpStatus.UNAUTHORIZED, problem);
    }

    /**
     * Authenticated but not permitted.
     *
     * <p>Barely reachable today — A-032's chain only ever asks for
     * authentication — but present so A-033's {@code @PreAuthorize} denials and
     * A-035's route-level rules inherit the right shape rather than each
     * inventing one. <b>Row-level scope failures must not arrive here:</b> A-035
     * requires an out-of-scope ticket id to answer 404, because a 403 confirms
     * the row exists.
     */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       org.springframework.security.access.AccessDeniedException ignored) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setType(FORBIDDEN);
        problem.setTitle("Not permitted");
        problem.setDetail("Your role does not permit this action.");
        write(request, response, HttpStatus.FORBIDDEN, problem);
    }

    private void write(HttpServletRequest request, HttpServletResponse response,
                       HttpStatus status, ProblemDetail problem) throws IOException {
        // `instance` is set by ResponseEntityExceptionHandler for controller
        // errors; set here too, or the same problem type carries the path in one
        // half of the application and not the other.
        problem.setInstance(URI.create(request.getRequestURI()));

        response.setStatus(status.value());
        // No charset parameter, deliberately: JSON is UTF-8 by definition and
        // Spring's own ProblemDetail responses emit a bare
        // `application/problem+json`. Setting one here would make the chain's
        // refusals differ in their Content-Type from a controller's, which is
        // the sort of difference a client eventually branches on.
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
