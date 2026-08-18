package com.edunext.edutrack.api.feature.tickets.cycles;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * C-040 · RFC 9457 problem documents for the close route
 * ({@code CONVENTIONS.md} §3), matching the contract's 422 —
 * {@code ReopenExceptionHandler}'s own shape, one route over.
 *
 * <p>Scoped by {@code assignableTypes}, for the reason
 * {@code ReopenExceptionHandler}'s own header gives: a repository-wide
 * {@code @RestControllerAdvice} is shared surface no stream should introduce
 * unilaterally.
 *
 * <p>No 400 here — unlike reopen's restart-stage lookup, nothing on this route
 * validates against data the caller could get wrong beyond Bean Validation,
 * which Spring already renders in the contract's shape. No 403 either, on
 * {@code ReopenExceptionHandler}'s identical reasoning: the capability is the
 * whole authorisation question, so a caller without {@code ticket.close} never
 * reaches the service.
 */
@RestControllerAdvice(assignableTypes = CloseController.class)
class CloseExceptionHandler {

    private static final URI NOT_RESOLVED = URI.create("https://edutrack/errors/ticket-not-resolved");

    /**
     * 422, matching the contract's generic {@code UnprocessableTransition}
     * response on {@code closeTicket}.
     *
     * <p>Not field-keyed, on {@code ReopenExceptionHandler}'s own reasoning for
     * its twin: nothing the caller sent is wrong, the ticket simply is not
     * RESOLVED, and marking a field would invite rewording a resolution
     * summary that was never the problem.
     *
     * <p>{@code ticketStatus} rather than {@code status}, for the exact
     * duplicate-key reason {@code ReopenExceptionHandler} documents at length —
     * {@code status} is RFC 9457's own member holding the HTTP code.
     */
    @ExceptionHandler(TicketNotResolvedException.class)
    ResponseEntity<ProblemDetail> handleNotResolved(TicketNotResolvedException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(NOT_RESOLVED);
        problem.setTitle("That ticket is not resolved");
        problem.setDetail(e.getMessage());
        problem.setProperty("ticketStatus", e.status());
        return ResponseEntity.unprocessableEntity().body(problem);
    }
}
