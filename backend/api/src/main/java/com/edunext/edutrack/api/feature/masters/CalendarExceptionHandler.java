package com.edunext.edutrack.api.feature.masters;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.Map;

/**
 * B-023 · RFC 9457 problem documents for the calendar endpoints
 * ({@code CONVENTIONS.md} §3).
 *
 * <p><b>Scoped to {@link CalendarController}</b>, for the reason
 * {@code AuthExceptionHandler} gives: a repository-wide
 * {@code @RestControllerAdvice} is shared surface that four streams would edit
 * daily, and no stream should introduce one unilaterally.
 *
 * <p>{@code type} is the stable part clients branch on. {@code title} and
 * {@code detail} are prose and may be reworded; the URIs below must not change
 * once the calendar screen switches on them.
 */
@RestControllerAdvice(assignableTypes = CalendarController.class)
class CalendarExceptionHandler {

    private static final URI VALIDATION = URI.create("https://edutrack/errors/validation");
    private static final URI DUPLICATE = URI.create("https://edutrack/errors/duplicate");

    /**
     * A field-level rule the caller can fix, carrying the same
     * {@code errors: {field: [messages]}} shape the contract's
     * {@code ValidationFailed} response uses — so the screen can put the message
     * against the input that caused it rather than in a banner.
     */
    @ExceptionHandler(CalendarService.CalendarValidationException.class)
    ResponseEntity<ProblemDetail> handleValidation(CalendarService.CalendarValidationException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(VALIDATION);
        problem.setTitle("Validation failed");
        problem.setDetail(e.getMessage());
        problem.setProperty("errors", Map.of(e.field(), new String[]{e.getMessage()}));
        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * 409 rather than 400: the request is well formed and would have been
     * accepted a moment earlier. {@code uq_holidays (holiday_date, project_id)}
     * is what actually decides this, so the message names the clash rather than
     * a field the caller should change.
     */
    @ExceptionHandler(CalendarService.DuplicateHolidayException.class)
    ResponseEntity<ProblemDetail> handleDuplicate(CalendarService.DuplicateHolidayException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(DUPLICATE);
        problem.setTitle("Duplicate holiday");
        problem.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }
}
