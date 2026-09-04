package com.edunext.edutrack.api.feature.onboarding.journeys;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * C-102 · RFC 9457 problem documents for the four
 * {@code onboarding-journeys} controllers in this package
 * ({@code CONVENTIONS.md} §3), scoped by {@code assignableTypes} on
 * {@code AssignExceptionHandler}'s own advice: a repository-wide handler is
 * shared surface every stream would edit, and this package is Stream C's
 * alone.
 */
@RestControllerAdvice(assignableTypes = {
        ObJourneyTemplateController.class,
        ObJourneyTemplateStepController.class,
        ObJourneyTemplateStepItemController.class,
        ObJourneyTemplateStepDocController.class
})
class ObJourneyTemplateExceptionHandler {

    private static final URI VALIDATION = URI.create("https://edutrack/errors/validation");
    private static final URI CONFLICT = URI.create("https://edutrack/errors/conflict");
    private static final URI STEP_HAS_DEPENDENTS = URI.create("https://edutrack/errors/step-has-dependents");
    private static final URI TEMPLATE_HAS_NO_STEPS = URI.create("https://edutrack/errors/template-has-no-steps");

    /** No {@code ob_journey_templates}/{@code _steps}/{@code _step_items}/{@code _step_docs} row for the given id. */
    @ExceptionHandler({
            TemplateNotFoundException.class,
            StepNotFoundException.class,
            StepItemNotFoundException.class,
            StepDocNotFoundException.class
    })
    ResponseEntity<ProblemDetail> handleNotFound(RuntimeException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Not found");
        problem.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    /**
     * The four ways this service refuses to write over a row's own state:
     * a second template for a product that already has one, publishing
     * twice, editing anything that has ever been published, and revising a
     * version that is not currently active. All four are "the row exists,
     * but not in a state this call accepts" — {@code 409}, on
     * {@code CONVENTIONS.md} §3's line for it.
     */
    @ExceptionHandler({
            TemplateAlreadyExistsException.class,
            TemplateAlreadyPublishedException.class,
            TemplateNotEditableException.class,
            TemplateNotActiveException.class
    })
    ResponseEntity<ProblemDetail> handleConflict(RuntimeException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(CONFLICT);
        problem.setTitle("Conflict");
        problem.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /** {@code 409}, naming the dependent step ids — see the exception's own javadoc. */
    @ExceptionHandler(StepHasDependentsException.class)
    ResponseEntity<ProblemDetail> handleStepHasDependents(StepHasDependentsException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(STEP_HAS_DEPENDENTS);
        problem.setTitle("Step has dependents");
        problem.setDetail(e.getMessage());
        problem.setProperty("dependentStepIds", e.dependentStepIds());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * {@code 422} — the workflow forbids this move, on
     * {@code UnprocessableTransition}'s own line in {@code CONVENTIONS.md}
     * §3, applied here to a business rule rather than a ticket stage: a
     * published template with no steps could never activate a journey.
     */
    @ExceptionHandler(TemplateHasNoStepsException.class)
    ResponseEntity<ProblemDetail> handleNoSteps(TemplateHasNoStepsException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(TEMPLATE_HAS_NO_STEPS);
        problem.setTitle("Cannot publish a template with no steps");
        problem.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(problem);
    }

    /** {@code 400} — the reorder list is not exactly the template's current step set. */
    @ExceptionHandler(StepReorderMismatchException.class)
    ResponseEntity<ProblemDetail> handleReorderMismatch(StepReorderMismatchException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(VALIDATION);
        problem.setTitle("Reorder list does not match the template's current steps");
        problem.setDetail(e.getMessage());
        return ResponseEntity.badRequest().body(problem);
    }
}
