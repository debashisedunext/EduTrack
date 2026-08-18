package com.edunext.edutrack.api.feature.tickets.effort;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

import java.net.URI;

/**
 * C-035 · 404. No such effort log on this ticket.
 *
 * <p>Thrown identically for a {@code correctsEntryId} that does not exist and
 * one that exists on a <b>different</b> ticket — following
 * {@code CommentNotFoundException} exactly, and for the same reason: the row is
 * keyed by nothing but a {@code BIGINT}, so without this check
 * {@code POST /tickets/CRM-26-00347/effort} with a correction naming an id from
 * another ticket would silently reverse an entry over there instead of refusing
 * here, which is both a cross-ticket data leak and a write to a ticket this
 * caller was never scoped against.
 *
 * <p>An {@link ErrorResponseException} rather than an entry in an
 * {@code @ExceptionHandler}, following {@code TicketNotFoundException} and
 * {@code CommentNotFoundException}: {@code spring.mvc.problemdetails.enabled}
 * renders it as RFC 9457 unaided.
 */
class EffortLogNotFoundException extends ErrorResponseException {

    private static final URI TYPE = URI.create("https://edutrack/errors/effort-log-not-found");

    EffortLogNotFoundException() {
        super(HttpStatus.NOT_FOUND, problem(), null);
    }

    private static ProblemDetail problem() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(TYPE);
        problem.setTitle("Effort log not found");
        problem.setDetail("No such effort log on this ticket.");
        return problem;
    }
}
