package com.edunext.edutrack.api.feature.tickets;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.Map;

/**
 * RFC 9457 problem documents for the ticket routes ({@code CONVENTIONS.md} §3).
 *
 * <p>{@link UnknownProjectException} was written by C-011 with a note that its
 * handler "lives with C-010's {@code TicketController} … there is no ticket
 * route yet, and a handler with no controller to advise cannot be tested."
 * C-012's preview is the first ticket route, so this is that handler. Every
 * later ticket failure belongs here too rather than in a new advice class.
 *
 * <p><b>Scoped by {@code assignableTypes}</b>, for the reason
 * {@code AuthExceptionHandler} and {@code CalendarExceptionHandler} both give: a
 * repository-wide {@code @RestControllerAdvice} is shared surface four streams
 * would edit daily, and no stream should introduce one unilaterally. The list
 * grows as this feature gains controllers.
 *
 * <p>{@code type} is the stable part clients branch on; {@code title} and
 * {@code detail} are prose and may be reworded.
 */
@RestControllerAdvice(assignableTypes = {
        PlannedCloseDateController.class,
        // C-020 · joins the list rather than opening a second advice, which is
        // what the paragraph above asks of every later ticket controller. It
        // needs UnknownLevelException's 400 unchanged — the same message under
        // the same key, so a client that handles a bad level on the create
        // form's preview handles it on the detail page's chip too.
        PriorityChangeController.class,
})
class TicketExceptionHandler {

    private static final URI NOT_FOUND = URI.create("https://edutrack/errors/not-found");
    private static final URI VALIDATION = URI.create("https://edutrack/errors/validation");

    /**
     * <b>404, never 403</b>, and the body says nothing more than "not found".
     *
     * <p>The project id is on the exception for the server log and is
     * deliberately not echoed: once A-034's {@code ScopeResolver} lands, a
     * project outside the caller's scope arrives here as "does not exist", and a
     * response that distinguished the two would confirm which project ids are
     * real to anyone willing to enumerate them.
     */
    @ExceptionHandler(UnknownProjectException.class)
    ResponseEntity<ProblemDetail> handleUnknownProject(UnknownProjectException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(NOT_FOUND);
        problem.setTitle("Not found");
        problem.setDetail("Not found");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    /**
     * The same {@code errors: {field: [messages]}} shape the contract's
     * {@code ValidationFailed} uses, so the form can put the message against the
     * level picker rather than in a banner.
     */
    @ExceptionHandler(UnknownLevelException.class)
    ResponseEntity<ProblemDetail> handleUnknownLevel(UnknownLevelException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(VALIDATION);
        problem.setTitle("Validation failed");
        problem.setDetail(e.getMessage());
        problem.setProperty("errors", Map.of("level", new String[]{e.getMessage()}));
        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * C-020 · §4B.1's mandatory reason, keyed onto {@code reason} so S-20's
     * dialog marks the textarea rather than showing a banner over a form whose
     * only other field — the level the user just picked — is perfectly fine.
     *
     * <p>400 and not 422, which is the opposite call from
     * {@code ReopenExceptionHandler}'s {@code TicketNotClosedException} and is
     * the right one for the opposite reason: there, nothing the caller sent was
     * wrong and rewording could not help; here the request is missing a field it
     * needed, and resending with it works. {@link LevelReasonRequiredException}
     * carries the argument.
     */
    @ExceptionHandler(LevelReasonRequiredException.class)
    ResponseEntity<ProblemDetail> handleReasonRequired(LevelReasonRequiredException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(VALIDATION);
        problem.setTitle("Validation failed");
        problem.setDetail(e.getMessage());
        problem.setProperty("errors", Map.of("reason", new String[]{e.getMessage()}));
        return ResponseEntity.badRequest().body(problem);
    }
}
