package com.edunext.edutrack.api.feature.masters.projects;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.Map;

/**
 * B-019 · RFC 9457 problem documents for the Settings tab ({@code CONVENTIONS.md}
 * §3).
 *
 * <p><b>Scoped to {@link ProjectSettingsController}</b>, and separate from the
 * three handlers already in this package for the reason all of them carry: a
 * repository-wide {@code @RestControllerAdvice} is shared surface four streams
 * would edit daily, and no stream should introduce one unilaterally. Fourth
 * handler in one feature package is the cost of that, and it is still a smaller
 * cost than the alternative.
 */
@RestControllerAdvice(assignableTypes = ProjectSettingsController.class)
class ProjectSettingsExceptionHandler {

    private static final URI VALIDATION = URI.create("https://edutrack/errors/validation");

    /** 404 for the project id in the path — never 403, and never a leak. */
    @ExceptionHandler(ProjectSettingsService.NoSuchProjectException.class)
    ResponseEntity<ProblemDetail> handleNoSuchProject(ProjectSettingsService.NoSuchProjectException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Not found");
        problem.setDetail("No such project.");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    /**
     * A rule the caller can fix, in the {@code errors: {field: [messages]}}
     * shape the contract's {@code ValidationFailed} uses.
     *
     * <p>The field is the request property — {@code autoAssignRule},
     * {@code mandatoryFields}, {@code allowedTaskTypeIds} — and not an index
     * into either array. B-018's handler makes the same call for the same
     * reason: the body is assembled from a checkbox list, so
     * {@code allowedTaskTypeIds[4]} is a position no control on the screen has,
     * and the message already names the task type that identifies it.
     */
    @ExceptionHandler(ProjectSettingsService.SettingsValidationException.class)
    ResponseEntity<ProblemDetail> handleValidation(ProjectSettingsService.SettingsValidationException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(VALIDATION);
        problem.setTitle("Validation failed");
        problem.setDetail(e.getMessage());
        problem.setProperty("errors", Map.of(e.field(), new String[]{e.getMessage()}));
        return ResponseEntity.badRequest().body(problem);
    }
}
