package com.edunext.edutrack.api.feature.tickets.bulkreassign;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.Map;

/**
 * C-063 · RFC 9457 problem documents for {@code POST /tickets/bulk-reassign}
 * ({@code CONVENTIONS.md} §3).
 *
 * <p>Scoped by {@code assignableTypes}, following every other ticket
 * sub-feature's own advice ({@code QuickUpdateExceptionHandler},
 * {@code EffortLogExceptionHandler}): a repository-wide
 * {@code @RestControllerAdvice} is shared surface four streams would edit
 * daily.
 */
@RestControllerAdvice(assignableTypes = BulkReassignController.class)
class BulkReassignExceptionHandler {

    private static final URI VALIDATION = URI.create("https://edutrack/errors/validation");

    /** 400, keyed onto {@code toUserId} — the field the wizard's target picker owns. */
    @ExceptionHandler(UnknownUserException.class)
    ResponseEntity<ProblemDetail> handleUnknownUser(UnknownUserException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(VALIDATION);
        problem.setTitle("Validation failed");
        problem.setDetail("No resource with that id");
        problem.setProperty("errors", Map.of("toUserId", new String[]{"No resource with that id"}));
        return ResponseEntity.badRequest().body(problem);
    }
}
