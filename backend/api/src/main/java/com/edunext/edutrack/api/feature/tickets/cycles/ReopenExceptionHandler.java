package com.edunext.edutrack.api.feature.tickets.cycles;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.Map;

/**
 * C-038 · RFC 9457 problem documents for the reopen route
 * ({@code CONVENTIONS.md} §3), matching the contract's 422 and 400.
 *
 * <p><b>Scoped by {@code assignableTypes}</b>, for the reason
 * {@code CommentExceptionHandler}, {@code AttachmentExceptionHandler} and
 * {@code TicketExceptionHandler} all give: a repository-wide
 * {@code @RestControllerAdvice} is shared surface four streams would edit daily,
 * and no stream should introduce one unilaterally.
 *
 * <p>{@code TicketNotFoundException} is deliberately absent — it extends
 * {@code ErrorResponseException} and Spring renders it as A-035's 404 without
 * help. A handler for it here would be a second place the out-of-scope response
 * is decided, which is the drift {@code ScopedTickets} exists to prevent.
 *
 * <p>There is no 403, and that is not an omission either. The capability is the
 * whole of the authorisation question for this route — see
 * {@code ReopenController} — so a caller without {@code ticket.reopen} is
 * refused by {@code @PreAuthorize} before the service runs, and every caller who
 * reaches the service may reopen the tickets they can see.
 */
@RestControllerAdvice(assignableTypes = ReopenController.class)
class ReopenExceptionHandler {

    private static final URI VALIDATION = URI.create("https://edutrack/errors/validation");

    /**
     * A stable URI the client may switch on (CONVENTIONS.md §3). S-22's dialog
     * closes and reloads the ticket on this one rather than marking a field, so
     * it has to be distinguishable from the validation failure without parsing
     * {@code detail} — prose gets reworded, and a client matching on it breaks
     * the first time somebody improves a sentence.
     */
    private static final URI NOT_CLOSED = URI.create("https://edutrack/errors/ticket-not-closed");

    /**
     * 422, matching the contract's own response on {@code reopenTicket}: "Ticket
     * is not closed, so there is nothing to reopen."
     *
     * <p><b>Not field-keyed</b>, unlike the 400 below. A 400 says "this value is
     * wrong, change it and resend", which is why it points at an input. Nothing
     * the caller sent is wrong here — the reason may be perfect and the ticket is
     * still open — so putting the message under {@code errors.reason} would mark
     * the textarea red and invite the one response that cannot work, which is
     * rewording the reason and trying again.
     *
     * <p>The ticket's status is echoed as a property because it is the one fact
     * that tells the user what to do instead: {@code RESOLVED} means "sign it off
     * first, or send it back for rework", and {@code REOPENED} means somebody
     * else got there first. Safe to publish — the caller has just read the ticket.
     *
     * <p><b>Named {@code ticketStatus} and not {@code status}</b>, which is not
     * fussiness. {@code status} is a reserved RFC 9457 member holding the HTTP
     * code, and {@code setProperty("status", …)} does not replace it — Spring
     * serialises both, so the document goes out with the key twice:
     * {@code {"status":422, … ,"status":"IN_PROGRESS"}}. Duplicate keys are legal
     * JSON and every parser in practice keeps the <em>last</em>, so a client
     * reading {@code problem.status} gets the string where the generated client's
     * type says number. Caught by hand against a running server; the handler test
     * below now pins the shape so it cannot come back.
     */
    @ExceptionHandler(TicketNotClosedException.class)
    ResponseEntity<ProblemDetail> handleNotClosed(TicketNotClosedException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(NOT_CLOSED);
        problem.setTitle("That ticket is not closed");
        problem.setDetail(e.getMessage());
        problem.setProperty("ticketStatus", e.status());
        return ResponseEntity.unprocessableEntity().body(problem);
    }

    /**
     * 400 in the contract's {@code ValidationFailed} shape, keyed onto
     * {@code restartStageCode} so S-22 puts the message against the restart-stage
     * picker rather than in a banner over a form whose other four fields are fine.
     */
    @ExceptionHandler(UnknownRestartStageException.class)
    ResponseEntity<ProblemDetail> handleUnknownStage(UnknownRestartStageException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(VALIDATION);
        problem.setTitle("Validation failed");
        problem.setDetail(e.getMessage());
        problem.setProperty("errors", Map.of("restartStageCode", new String[]{e.getMessage()}));
        return ResponseEntity.badRequest().body(problem);
    }
}
