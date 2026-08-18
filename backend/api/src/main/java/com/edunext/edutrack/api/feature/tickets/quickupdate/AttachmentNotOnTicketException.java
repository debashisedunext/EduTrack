package com.edunext.edutrack.api.feature.tickets.quickupdate;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

import java.net.URI;

/**
 * C-036 · 404. {@code attachmentIds} named a file this ticket does not have.
 *
 * <p>Files are already on the ticket by the time this route sees them — {@code
 * useTicketAttachments} uploads straight to {@code POST
 * /tickets/{ticketId}/attachments} the moment they are picked, so the list here
 * is a claim about what already happened rather than an instruction. This is
 * the check that catches the claim being wrong: an id from another ticket, a
 * stale id from a file since removed, or a client bug — following {@code
 * EffortLogNotFoundException}'s identical reasoning for {@code
 * correctsEntryId}, an unchecked foreign id here would be a cross-ticket read
 * with no scope guard in front of it.
 *
 * <p>An {@link ErrorResponseException} rather than an {@code
 * @ExceptionHandler} entry, following {@code EffortLogNotFoundException}:
 * {@code spring.mvc.problemdetails.enabled} renders it as RFC 9457 unaided.
 */
class AttachmentNotOnTicketException extends ErrorResponseException {

    private static final URI TYPE = URI.create("https://edutrack/errors/attachment-not-on-ticket");

    AttachmentNotOnTicketException() {
        super(HttpStatus.NOT_FOUND, problem(), null);
    }

    private static ProblemDetail problem() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(TYPE);
        problem.setTitle("Attachment not found");
        problem.setDetail("One of the attached files is not on this ticket.");
        return problem;
    }
}
