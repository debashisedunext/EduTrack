package com.edunext.edutrack.api.security.scope;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

import java.net.URI;

/**
 * A-035 · the one answer for "no such ticket" and "not one of yours".
 *
 * <p>Blueprint §10.2 and {@code contracts/openapi.yaml} both require that an
 * out-of-scope id is indistinguishable from an id that was never issued. A 403
 * would confirm the ticket exists, which is the existence leak the whole scope
 * design avoids — so there is deliberately no second exception, no reason code
 * and no flag to branch on. <b>The two cases are not "both mapped to 404"; they
 * are the same exception, thrown from the same line.</b> That is what makes the
 * responses byte-identical without anyone having to keep two bodies in step.
 *
 * <h2>No reason field, on purpose</h2>
 *
 * <p>It would be easy to carry a {@code Reason.OUT_OF_SCOPE} for logging, and
 * it is left out: a field that exists is a field that gets serialised
 * eventually — into a log line someone forwards, a {@code detail} string
 * someone makes "more helpful", or a debug header. The distinction cannot leak
 * from a value that was never recorded. Ops questions about who asked for what
 * belong in an access log keyed on the request, not in the response path.
 *
 * <h2>Why this is an {@link ErrorResponseException} and not an advice</h2>
 *
 * <p>{@code spring.mvc.problemdetails.enabled} is on, so Spring renders any
 * {@code ErrorResponse} as RFC 9457 {@code application/problem+json} on its
 * own. The alternative — a {@code @RestControllerAdvice} — would have to be
 * either repository-wide, which {@code AuthExceptionHandler} records as shared
 * surface that is "not Stream A's to introduce unilaterally", or scoped with
 * {@code assignableTypes} to controllers that do not exist yet and belong to
 * Stream C. Throwing a self-describing exception needs neither: Stream C writes
 * no handler, registers nothing, and still cannot produce a 403.
 */
public class TicketNotFoundException extends ErrorResponseException {

    private static final URI TYPE = URI.create("https://edutrack/errors/ticket-not-found");

    public TicketNotFoundException() {
        super(HttpStatus.NOT_FOUND, problem(), null);
    }

    /**
     * {@code detail} is a fixed string, never derived from the id or the
     * caller. Deriving it is how two callers end up with two different bodies
     * for the same refusal, and the difference is exactly the thing being
     * hidden.
     *
     * <p>The wording names both possibilities rather than asserting the ticket
     * does not exist. Saying "no such ticket" to someone looking at a real one
     * is a lie the product does not need to tell, and naming both leaks
     * nothing: it discloses the policy, which is public, not which of the two
     * applies to this id.
     */
    private static ProblemDetail problem() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(TYPE);
        problem.setTitle("Ticket not found");
        problem.setDetail("No ticket with that identifier is available to you.");
        return problem;
    }
}
