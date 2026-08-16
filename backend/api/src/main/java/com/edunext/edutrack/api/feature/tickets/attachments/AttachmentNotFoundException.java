package com.edunext.edutrack.api.feature.tickets.attachments;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

import java.net.URI;

/**
 * C-028 · 404. No such attachment on this ticket.
 *
 * <p>Thrown for an id that does not exist <b>and</b> for one that exists on a
 * different ticket, from the same line, for the reason
 * {@code TicketNotFoundException} gives about ids in general: the two must be
 * indistinguishable, or the response tells a caller which attachment ids are real.
 * Guessing ids across tickets is exactly the probe this closes — the row is keyed
 * by nothing but a {@code BIGINT}, so {@code /tickets/9/attachments/41} would
 * otherwise answer differently depending on whether 41 belongs to ticket 12.
 *
 * <p>An {@link ErrorResponseException} rather than another entry in
 * {@link AttachmentExceptionHandler}, following {@code TicketNotFoundException}:
 * {@code spring.mvc.problemdetails.enabled} renders it as RFC 9457 unaided, and a
 * self-describing exception cannot drift from a handler that also has to be kept
 * in step.
 *
 * <p>Note what this is <em>not</em> used for. A caller who may see the attachment
 * but may not remove it gets 403 from
 * {@link AttachmentDeletionNotPermittedException} — that class carries the
 * argument for why conceding existence is right in that one place and wrong here.
 */
class AttachmentNotFoundException extends ErrorResponseException {

    private static final URI TYPE = URI.create("https://edutrack/errors/attachment-not-found");

    AttachmentNotFoundException() {
        super(HttpStatus.NOT_FOUND, problem(), null);
    }

    /**
     * A fixed string, never derived from the id or the caller — deriving it is
     * how two callers get two different bodies for one refusal, and the
     * difference is the thing being concealed.
     */
    private static ProblemDetail problem() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(TYPE);
        problem.setTitle("Attachment not found");
        problem.setDetail("No such attachment on this ticket, or it is not one you can see.");
        return problem;
    }
}
