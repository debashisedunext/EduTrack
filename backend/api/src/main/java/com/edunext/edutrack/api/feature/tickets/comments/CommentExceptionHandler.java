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
 * <p>There is no 403 in this feature and there should not be. Everything about
 * <em>which</em> tickets a caller may comment on is settled by
 * {@code ScopedTickets} before this package sees the request, and the answer is
 * 404. C-028's single 403 is the exception that proves the rule: it applies to a
 * row the caller is already looking at in a listing they just fetched, which is
 * not a situation this feature has.
 */
@RestControllerAdvice(assignableTypes = CommentController.class)
class CommentExceptionHandler {

    private static final URI VALIDATION = URI.create("https://edutrack/errors/validation");

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
}
