package com.edunext.edutrack.api.feature.tickets.comments;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.Map;

/**
 * C-029 · RFC 9457 problem documents for the comment routes
 * ({@code CONVENTIONS.md} §3), matching the contract's 400.
 *
 * <p><b>Scoped by {@code assignableTypes}</b>, for the reason
 * {@code AttachmentExceptionHandler}, {@code TicketExceptionHandler} and
 * {@code CalendarExceptionHandler} all give: a repository-wide
 * {@code @RestControllerAdvice} is shared surface four streams would edit daily,
 * and no stream should introduce one unilaterally.
 *
 * <p>{@code TicketNotFoundException} is deliberately absent. It extends
 * {@code ErrorResponseException} and Spring renders it as A-035's 404 without
 * help — and a handler for it here would create a second place where the
 * out-of-scope response is decided, which is the drift that class's javadoc
 * exists to prevent.
 *
 * <p>C-029 said here that this feature has no 403 and should not, because
 * everything about <em>which</em> tickets a caller may comment on is settled by
 * {@code ScopedTickets} and answered 404. That is still true of the ticket, and
 * C-033 added the situation the note itself described as the exception: a caller
 * who may read a comment and may not remove it is looking at it in a thread they
 * just fetched, so there is no existence left to conceal. See
 * {@link CommentDeletionNotPermittedException}, and
 * {@link CommentNotEditableException} for why the edit's refusal is a 422 rather
 * than the same 403.
 */
@RestControllerAdvice(assignableTypes = CommentController.class)
class CommentExceptionHandler {

    private static final URI VALIDATION = URI.create("https://edutrack/errors/validation");

    /*
     * Stable URIs the client may switch on (CONVENTIONS.md §3). Distinct from
     * each other because the affordances they close are different — the editor
     * shuts and locks for one, the × disappears for the other — and a client
     * matching on `detail` would break the first time the wording improved.
     */
    private static final URI NOT_EDITABLE = URI.create("https://edutrack/errors/comment-not-editable");
    private static final URI DELETE_NOT_PERMITTED =
            URI.create("https://edutrack/errors/comment-delete-not-permitted");

    /**
     * 400 and field-keyed, matching the contract's {@code ValidationFailed} and
     * the shape Bean Validation produces for the same route.
     *
     * <p>One shape for all three refusals is the point: the comment box does not
     * branch on which one it got, it puts {@code errors.body} under the editor
     * and {@code errors.attachmentIds} under the picker. A client that had to
     * parse {@code detail} to find out where the message goes is a client that
     * will render it in the wrong place the first time the wording changes.
     */
    @ExceptionHandler(InvalidCommentException.class)
    ResponseEntity<ProblemDetail> handleInvalid(InvalidCommentException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(VALIDATION);
        problem.setTitle("That comment cannot be posted");
        problem.setDetail(e.getMessage());
        problem.setProperty("errors", Map.of(e.field(), new String[]{e.getMessage()}));
        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * C-033 · 422, matching the contract's own response on {@code editComment}.
     *
     * <p><b>Not field-keyed</b>, unlike the 400 above, and the difference is not
     * cosmetic. A 400 says "this value is wrong, change it and resend", which is
     * why it points at an input. None of these three refusals is about the value
     * the caller sent — the wording may be perfect and the comment is still
     * locked, or somebody else's, or gone. Putting the message under
     * {@code errors.body} would mark the editor red and invite the one response
     * that cannot work, which is editing the text and trying again.
     */
    @ExceptionHandler(CommentNotEditableException.class)
    ResponseEntity<ProblemDetail> handleNotEditable(CommentNotEditableException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(NOT_EDITABLE);
        problem.setTitle("That comment can no longer be edited");
        problem.setDetail(e.getMessage());
        return ResponseEntity.unprocessableEntity().body(problem);
    }

    /**
     * C-033 · 403, following C-028's {@code AttachmentDeletionNotPermittedException}
     * exactly — including the {@code check-conventions.py} exemption, which is now
     * this repository's second row-scoped entry in {@code ROWLESS_403} and should
     * be read together with the first.
     */
    @ExceptionHandler(CommentDeletionNotPermittedException.class)
    ResponseEntity<ProblemDetail> handleDeletionRefused(CommentDeletionNotPermittedException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setType(DELETE_NOT_PERMITTED);
        problem.setTitle("That comment is not yours to remove");
        problem.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    }
}
