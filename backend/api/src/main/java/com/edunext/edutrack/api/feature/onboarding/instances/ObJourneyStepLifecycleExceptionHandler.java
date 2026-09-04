package com.edunext.edutrack.api.feature.onboarding.instances;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * C-104 · RFC 9457 problem documents for {@link ObJourneyStepLifecycleController}
 * ({@code CONVENTIONS.md} §3), scoped by {@code assignableTypes} on {@code
 * ObJourneyTemplateExceptionHandler}'s own precedent one module over: a
 * repository-wide handler is shared surface every stream would edit, and
 * this package is Stream C's alone.
 */
@RestControllerAdvice(assignableTypes = ObJourneyStepLifecycleController.class)
class ObJourneyStepLifecycleExceptionHandler {

    private static final URI STEP_OWNER_REQUIRED = URI.create("https://edutrack/errors/step-owner-required");
    private static final URI INVALID_STEP_TRANSITION = URI.create("https://edutrack/errors/invalid-step-transition");
    private static final URI JOURNEY_NOT_OPEN = URI.create("https://edutrack/errors/journey-not-open");

    /** No {@code ob_journey_steps} row for the given id. */
    @ExceptionHandler(JourneyStepNotFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(JourneyStepNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Not found");
        problem.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    /**
     * 422 — {@link ObStepOwnership#mayAct} refused. Not field-keyed, on
     * {@code HandoffExceptionHandler}'s reasoning for its own 422: nothing
     * the caller sent is wrong, they are simply not this step's owner or
     * backup owner.
     */
    @ExceptionHandler(NotStepOwnerException.class)
    ResponseEntity<ProblemDetail> handleNotStepOwner(NotStepOwnerException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(STEP_OWNER_REQUIRED);
        problem.setTitle("Only the step's owner or backup owner may update it");
        problem.setDetail(e.getMessage());
        return ResponseEntity.unprocessableEntity().body(problem);
    }

    /** 422 — the step's current status does not admit the requested action. */
    @ExceptionHandler(InvalidStepTransitionException.class)
    ResponseEntity<ProblemDetail> handleInvalidTransition(InvalidStepTransitionException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(INVALID_STEP_TRANSITION);
        problem.setTitle("This step cannot make that move from its current status");
        problem.setDetail(e.getMessage());
        return ResponseEntity.unprocessableEntity().body(problem);
    }

    /** 422 — the journey's gate is still locked, or it is held by another journey. */
    @ExceptionHandler(JourneyNotOpenException.class)
    ResponseEntity<ProblemDetail> handleJourneyNotOpen(JourneyNotOpenException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(JOURNEY_NOT_OPEN);
        problem.setTitle("This journey is not open for step activity yet");
        problem.setDetail(e.getMessage());
        return ResponseEntity.unprocessableEntity().body(problem);
    }
}
