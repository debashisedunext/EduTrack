package com.edunext.edutrack.api.feature.masters.tasktypes;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.Map;

/**
 * B-020 · RFC 9457 problem documents for the Task Type Master
 * ({@code CONVENTIONS.md} §3).
 *
 * <p><b>Scoped to {@link TaskTypeController}</b>, for the reason
 * {@code CalendarExceptionHandler} gives and {@code RoleExceptionHandler}
 * repeats: a repository-wide {@code @RestControllerAdvice} is shared surface
 * four streams would edit daily, and no stream should introduce one
 * unilaterally.
 *
 * <p>{@code type} is the stable part clients branch on. Both 409s here are
 * genuinely different situations with different remedies — "pick another code"
 * versus "you cannot change this one" — and the S-11 form tells them apart by
 * URI rather than by prose.
 */
@RestControllerAdvice(assignableTypes = TaskTypeController.class)
class TaskTypeExceptionHandler {

    private static final URI VALIDATION = URI.create("https://edutrack/errors/validation");
    private static final URI DUPLICATE = URI.create("https://edutrack/errors/duplicate");
    private static final URI IMMUTABLE = URI.create("https://edutrack/errors/immutable-field");

    /**
     * 409 rather than 400: the request is well formed and would have been
     * accepted a moment earlier. Field-keyed all the same — the message belongs
     * on the {@code code} input or on the {@code name} input, and which one it
     * is comes off the exception rather than out of the sentence.
     */
    @ExceptionHandler(TaskTypeService.DuplicateTaskTypeException.class)
    ResponseEntity<ProblemDetail> handleDuplicate(TaskTypeService.DuplicateTaskTypeException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(DUPLICATE);
        problem.setTitle("Duplicate task type");
        problem.setDetail(e.getMessage());
        problem.setProperty("errors", Map.of(e.field(), new String[]{e.getMessage()}));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(TaskTypeService.ImmutableTaskTypeCodeException.class)
    ResponseEntity<ProblemDetail> handleImmutable(
            TaskTypeService.ImmutableTaskTypeCodeException e) {

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(IMMUTABLE);
        problem.setTitle("Task type code cannot be changed");
        problem.setDetail(e.getMessage());
        problem.setProperty("errors", Map.of("code", new String[]{e.getMessage()}));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * 400 and field-keyed, so the two rules Bean Validation cannot express land
     * on an input exactly the way a {@code @Pattern} failure would. A client
     * that handles the standard 400 needs no new branch for these.
     */
    @ExceptionHandler(TaskTypeService.TaskTypeValidationException.class)
    ResponseEntity<ProblemDetail> handleValidation(
            TaskTypeService.TaskTypeValidationException e) {

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(VALIDATION);
        problem.setTitle("Validation failed");
        problem.setDetail(e.getMessage());
        problem.setProperty("errors", Map.of(e.field(), new String[]{e.getMessage()}));
        return ResponseEntity.badRequest().body(problem);
    }
}
