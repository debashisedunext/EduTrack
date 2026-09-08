package com.edunext.edutrack.api.feature.onboarding.prereqs;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * B-124 · RFC 9457 problem documents for the two OB-14 controllers
 * ({@code CONVENTIONS.md} §3), scoped by {@code assignableTypes} on
 * {@code ObJourneyTemplateExceptionHandler}'s own precedent: a
 * repository-wide handler is shared surface every stream would edit, and
 * this package is Stream B's alone.
 */
@RestControllerAdvice(assignableTypes = {
        ObPrereqTemplateController.class,
        ObPrereqTemplateTaskController.class
})
class ObPrereqTemplateExceptionHandler {

    private static final URI VALIDATION = URI.create("https://edutrack/errors/validation");
    private static final URI CONFLICT = URI.create("https://edutrack/errors/conflict");
    private static final URI NO_MANDATORY_TASK =
            URI.create("https://edutrack/errors/prereq-template-has-no-mandatory-task");

    /** No {@code ob_prereq_template_tasks}/{@code _task_docs} row for the given id. */
    @ExceptionHandler({
            PrereqTemplateTaskNotFoundException.class,
            PrereqTemplateTaskDocNotFoundException.class
    })
    ResponseEntity<ProblemDetail> handleNotFound(RuntimeException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Not found");
        problem.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    /**
     * The three ways this service refuses to write over the master's own
     * state: no draft to write into, a second draft when one is already
     * open, and a task belonging to a version that has been published. All
     * three are "the row exists, but not in a state this call accepts" —
     * {@code 409}, on {@code CONVENTIONS.md} §3's line for it, and each is
     * one of the 409s the contract declares on these operations.
     */
    @ExceptionHandler({
            PrereqNoDraftException.class,
            PrereqDraftAlreadyExistsException.class,
            PrereqTaskNotEditableException.class
    })
    ResponseEntity<ProblemDetail> handleConflict(RuntimeException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(CONFLICT);
        problem.setTitle("Conflict");
        problem.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * {@code 422} — the content forbids this move, on
     * {@code TemplateHasNoStepsException}'s own line one package over. A
     * published checklist with nothing mandatory would clear its own gate
     * at every boarding, so the gate would be present and do nothing.
     */
    @ExceptionHandler(PrereqTemplateHasNoMandatoryTaskException.class)
    ResponseEntity<ProblemDetail> handleNoMandatoryTask(PrereqTemplateHasNoMandatoryTaskException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(NO_MANDATORY_TASK);
        problem.setTitle("Cannot publish a checklist with no mandatory task");
        problem.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(problem);
    }

    /** {@code 400} — the request is malformed against state the caller can see. */
    @ExceptionHandler({
            PrereqTaskReorderMismatchException.class,
            PrereqAttachmentNotOwnedByTaskException.class
    })
    ResponseEntity<ProblemDetail> handleBadRequest(RuntimeException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(VALIDATION);
        problem.setTitle("Validation failed");
        problem.setDetail(e.getMessage());
        return ResponseEntity.badRequest().body(problem);
    }
}
