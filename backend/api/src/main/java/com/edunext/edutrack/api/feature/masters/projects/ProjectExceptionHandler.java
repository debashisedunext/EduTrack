package com.edunext.edutrack.api.feature.masters.projects;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.Locale;
import java.util.Map;

/**
 * B-016 · RFC 9457 problem documents for the Project Master
 * ({@code CONVENTIONS.md} §3).
 *
 * <p><b>Scoped to {@link ProjectController}</b>, for the reason
 * {@code ResourceExceptionHandler} and {@code CalendarExceptionHandler} both
 * give: a repository-wide {@code @RestControllerAdvice} is shared surface four
 * streams would edit daily, and no stream should introduce one unilaterally.
 *
 * <p>{@code type} is the stable part clients branch on. {@code title} and
 * {@code detail} are prose and may be reworded without notice.
 */
@RestControllerAdvice(assignableTypes = ProjectController.class)
class ProjectExceptionHandler {

    private static final URI VALIDATION = URI.create("https://edutrack/errors/validation");
    private static final URI DUPLICATE = URI.create("https://edutrack/errors/duplicate");
    private static final URI IMMUTABLE_CODE =
            URI.create("https://edutrack/errors/immutable-project-code");

    /**
     * A field-level rule the caller can fix, in the {@code errors: {field:
     * [messages]}} shape the contract's {@code ValidationFailed} uses — so the
     * form puts the message against the input rather than in a banner.
     */
    @ExceptionHandler(ProjectService.ProjectValidationException.class)
    ResponseEntity<ProblemDetail> handleValidation(ProjectService.ProjectValidationException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(VALIDATION);
        problem.setTitle("Validation failed");
        problem.setDetail(e.getMessage());
        problem.setProperty("errors", Map.of(e.field(), new String[]{e.getMessage()}));
        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * 409 rather than 400: the request is well formed and would have been
     * accepted a moment earlier.
     */
    @ExceptionHandler(ProjectService.DuplicateCodeException.class)
    ResponseEntity<ProblemDetail> handleDuplicate(ProjectService.DuplicateCodeException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(DUPLICATE);
        problem.setTitle("Already in use");
        problem.setDetail(e.getMessage());
        problem.setProperty("errors", Map.of(
                ProjectService.DuplicateCodeException.FIELD, new String[]{e.getMessage()}));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * The immutability refusal, with its own {@code type}.
     *
     * <p>Distinct from {@code duplicate} because one is fixed by choosing
     * another code and the other cannot be fixed at all — and a client telling
     * them apart by reading {@code detail} is branching on the part of the
     * document that may be reworded without notice. {@code ticketsIssued} is on
     * the body so the form can say <i>why</i> rather than only <i>no</i>.
     */
    @ExceptionHandler(ProjectService.ImmutableCodeException.class)
    ResponseEntity<ProblemDetail> handleImmutableCode(ProjectService.ImmutableCodeException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(IMMUTABLE_CODE);
        problem.setTitle("The project code can no longer change");
        problem.setDetail(e.getMessage());
        problem.setProperty("ticketsIssued", e.ticketsIssued());
        problem.setProperty("errors", Map.of(
                ProjectService.ImmutableCodeException.FIELD, new String[]{e.getMessage()}));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * The same race, caught at the index instead of the check.
     *
     * <p>{@link ProjectService} asks whether a code is taken before writing, and
     * that answer is stale by the time the statement runs: two admins submitting
     * {@code CRM} in the same millisecond both pass. One then meets
     * {@code uq_projects_code}, and without this handler it would surface as a
     * 500 for a request whose only fault was losing a race. The check is the
     * good error message; the index is the thing that is actually true.
     */
    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<ProblemDetail> handleConstraint(DuplicateKeyException e) {
        String message = String.valueOf(e.getMostSpecificCause().getMessage()).toLowerCase(Locale.ROOT);

        String detail = message.contains("uq_projects_code")
                ? "that project code is already in use"
                : "that record already exists";
        String field = message.contains("uq_projects_code")
                ? ProjectService.DuplicateCodeException.FIELD
                : "_";

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(DUPLICATE);
        problem.setTitle("Already in use");
        problem.setDetail(detail);
        problem.setProperty("errors", Map.of(field, new String[]{detail}));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }
}
