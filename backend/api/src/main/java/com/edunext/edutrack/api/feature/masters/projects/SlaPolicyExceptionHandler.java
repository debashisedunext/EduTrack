package com.edunext.edutrack.api.feature.masters.projects;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.Map;

/**
 * B-018 · RFC 9457 problem documents for the SLA tab ({@code CONVENTIONS.md}
 * §3).
 *
 * <p><b>Scoped to {@link SlaPolicyController}</b>, and separate from
 * {@link ProjectExceptionHandler} and {@link ProjectMemberExceptionHandler} for
 * the reason all three of them carry: a repository-wide
 * {@code @RestControllerAdvice} is shared surface four streams would edit daily,
 * and no stream should introduce one unilaterally. Third handler in one feature
 * package is the cost of that, and it is a smaller cost than the alternative.
 */
@RestControllerAdvice(assignableTypes = SlaPolicyController.class)
class SlaPolicyExceptionHandler {

    private static final URI VALIDATION = URI.create("https://edutrack/errors/validation");

    /** 404 for the project id in the path — never 403, and never a leak. */
    @ExceptionHandler(SlaMatrixService.NoSuchProjectException.class)
    ResponseEntity<ProblemDetail> handleNoSuchProject(SlaMatrixService.NoSuchProjectException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Not found");
        problem.setDetail("No such project.");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    /**
     * A rule the caller can fix, in the {@code errors: {field: [messages]}}
     * shape the contract's {@code ValidationFailed} uses.
     *
     * <p>The field is the <i>kind</i> of thing that is wrong — {@code taskTypeId},
     * {@code level}, {@code responseHrs} — and not an index into the array, even
     * though the array is what was sent. An index would be the more precise
     * answer and a worse one: the body is assembled from a grid, so
     * {@code overrides[17]} is a position no cell on the screen has, and the
     * message already names the task type and level that identify the cell.
     */
    @ExceptionHandler(SlaMatrixService.SlaValidationException.class)
    ResponseEntity<ProblemDetail> handleValidation(SlaMatrixService.SlaValidationException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(VALIDATION);
        problem.setTitle("Validation failed");
        problem.setDetail(e.getMessage());
        problem.setProperty("errors", Map.of(e.field(), new String[]{e.getMessage()}));
        return ResponseEntity.badRequest().body(problem);
    }
}
