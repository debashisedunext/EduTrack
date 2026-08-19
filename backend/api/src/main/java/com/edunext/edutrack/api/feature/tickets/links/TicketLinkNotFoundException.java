package com.edunext.edutrack.api.feature.tickets.links;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

import java.net.URI;

/**
 * C-064 · 404. No such link on this ticket.
 *
 * <p>Thrown for a {@code linkId} that does not exist <b>and</b> for one that
 * touches neither end of this ticket, from the same line, on
 * {@code AttachmentNotFoundException}'s precedent: the two must be
 * indistinguishable, or the response tells a caller which link ids are real
 * on tickets they cannot otherwise see.
 *
 * <p>An {@link ErrorResponseException} rather than a
 * {@link TicketLinkExceptionHandler} entry, following
 * {@code AttachmentNotFoundException}: {@code spring.mvc.problemdetails
 * .enabled} renders it as RFC 9457 unaided.
 */
class TicketLinkNotFoundException extends ErrorResponseException {

    private static final URI TYPE = URI.create("https://edutrack/errors/ticket-link-not-found");

    TicketLinkNotFoundException() {
        super(HttpStatus.NOT_FOUND, problem(), null);
    }

    private static ProblemDetail problem() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(TYPE);
        problem.setTitle("Link not found");
        problem.setDetail("No such link on this ticket, or it is not one you can see.");
        return problem;
    }
}
