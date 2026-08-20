package com.edunext.edutrack.api.feature.tickets.assign;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.Map;

/**
 * C-049 · RFC 9457 problem documents for {@code POST /tickets/{ticketId}/assign}
 * ({@code CONVENTIONS.md} §3).
 *
 * <p>Scoped by {@code assignableTypes}, following every other ticket
 * sub-feature's own advice ({@code BulkReassignExceptionHandler},
 * {@code QuickUpdateExceptionHandler}): a repository-wide
 * {@code @RestControllerAdvice} is shared surface four streams would edit
 * daily.
 */
@RestControllerAdvice(assignableTypes = AssignController.class)
class AssignExceptionHandler {

    private static final URI VALIDATION = URI.create("https://edutrack/errors/validation");

    /** 400, keyed onto {@code assigneeId} — the field the assign dialog's picker owns. */
    @ExceptionHandler(UnknownAssigneeException.class)
    ResponseEntity<ProblemDetail> handleUnknownAssignee(UnknownAssigneeException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(VALIDATION);
        problem.setTitle("Validation failed");
        problem.setDetail("No resource with that id");
        problem.setProperty("errors", Map.of("assigneeId", new String[]{"No resource with that id"}));
        return ResponseEntity.badRequest().body(problem);
    }
}
