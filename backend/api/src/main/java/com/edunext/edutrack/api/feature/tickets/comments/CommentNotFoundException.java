package com.edunext.edutrack.api.feature.tickets.comments;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

import java.net.URI;

/**
 * C-033 · 404. No such comment on this ticket.
 *
 * <p>Thrown for an id that does not exist <b>and</b> for one that exists on a
 * different ticket, from the same line — {@code AttachmentNotFoundException}
 * carries the full argument and it holds verbatim here. The row is keyed by
 * nothing but a {@code BIGINT}, so without the ticket check
 * {@code /tickets/9/comments/41} would answer differently depending on whether
 * comment 41 belongs to ticket 12, and that difference is a way to enumerate
 * which comment ids are real across every ticket in the product.
 *
 * <p>An {@link ErrorResponseException} rather than another entry in
 * {@link CommentExceptionHandler}, following {@code TicketNotFoundException} and
 * {@code AttachmentNotFoundException}: {@code spring.mvc.problemdetails.enabled}
 * renders it as RFC 9457 unaided, and a self-describing exception cannot drift
 * from a handler that also has to be kept in step.
 *
 * <p>Not used for a caller who may see the comment and may not change it. Editing
 * answers 422 ({@link CommentNotEditableException}) and deleting answers 403
 * ({@link CommentDeletionNotPermittedException}); both of those classes carry the
 * argument for conceding existence where this one conceals it.
 */
class CommentNotFoundException extends ErrorResponseException {

    private static final URI TYPE = URI.create("https://edutrack/errors/comment-not-found");

    CommentNotFoundException() {
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
        problem.setTitle("Comment not found");
        problem.setDetail("No such comment on this ticket, or it is not one you can see.");
        return problem;
    }
}
