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
    private static final URI MODULE_SERVICE_IN_USE = URI.create("https://edutrack/errors/module-service-in-use");
    private static final URI MODULE_SERVICE_HAS_DEPENDENTS =
            URI.create("https://edutrack/errors/module-service-has-dependents");

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
     * The three ways this service refuses to write over a row's own state:
     * publishing twice, editing anything that has ever been published, and
     * revising a version that is not currently active. All three are "the row
     * exists, but not in a state this call accepts" — {@code 409}, on
     * {@code CONVENTIONS.md} §3's line for it.
     *
     * <p>There were four. {@code TemplateAlreadyExistsException} refused a
     * second service on a product that already had one, and it is deleted
     * rather than retained: a product sells several named services by design,
     * and the refusal was forced by an index keyed on
     * {@code (product_id, version)} rather than by any rule anybody wanted.
     * {@code V20260909_1900} re-keys that index and the exception has nothing
     * left to describe.
     */
    @ExceptionHandler({
            TemplateAlreadyPublishedException.class,
            TemplateNotEditableException.class,
            TemplateNotActiveException.class,
            DuplicateModuleServiceNameException.class
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

    /** {@code 400} — C-123's catalogue-wide reorder, {@code StepReorderMismatchException}'s own shape. */
    @ExceptionHandler(CatalogueReorderMismatchException.class)
    ResponseEntity<ProblemDetail> handleCatalogueReorderMismatch(CatalogueReorderMismatchException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(VALIDATION);
        problem.setTitle("Reorder list does not match the catalogue's active templates");
        problem.setDetail(e.getMessage());
        return ResponseEntity.badRequest().body(problem);
    }

    /** {@code 409} — C-123's cycle-free depends-on picker, enforced server-side. */
    @ExceptionHandler(TemplateDependencyCycleException.class)
    ResponseEntity<ProblemDetail> handleDependencyCycle(TemplateDependencyCycleException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(CONFLICT);
        problem.setTitle("That dependency would close a cycle");
        problem.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * C-124 · {@code 409}, carrying the count the OB-07 card shows.
     *
     * <p>Its own {@code type} rather than the generic {@code CONFLICT} one,
     * because a client that wants to say <i>why</i> Edit and Delete are refused
     * has to tell this apart from a name collision — and {@code journeyCount}
     * is the number that sentence needs. {@code StepHasDependentsException}
     * sets the same precedent one resource down.
     */
    @ExceptionHandler(ModuleServiceInUseException.class)
    ResponseEntity<ProblemDetail> handleModuleServiceInUse(ModuleServiceInUseException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(MODULE_SERVICE_IN_USE);
        problem.setTitle("Module Service is in use");
        problem.setDetail(e.getMessage());
        problem.setProperty("journeyCount", e.journeyCount());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /** C-124 · {@code 409}, naming the services to re-point — {@code StepHasDependentsException}'s shape. */
    @ExceptionHandler(ModuleServiceHasDependentsException.class)
    ResponseEntity<ProblemDetail> handleModuleServiceHasDependents(ModuleServiceHasDependentsException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(MODULE_SERVICE_HAS_DEPENDENTS);
        problem.setTitle("Module Service has dependents");
        problem.setDetail(e.getMessage());
        problem.setProperty("dependentServiceNames", e.dependentServiceNames());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }
}
