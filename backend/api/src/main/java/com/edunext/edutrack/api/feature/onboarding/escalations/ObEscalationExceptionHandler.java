package com.edunext.edutrack.api.feature.onboarding.escalations;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * C-115 · RFC 9457 problem documents for {@link ObEscalationController}
 * ({@code CONVENTIONS.md} §3), scoped by {@code assignableTypes} on {@code
 * ObJourneyStepLifecycleExceptionHandler}'s own precedent — a
 * repository-wide handler is shared surface every stream would edit, and
 * this package is Stream C's alone.
 */
@RestControllerAdvice(assignableTypes = ObEscalationController.class)
class ObEscalationExceptionHandler {

    private static final URI ESCALATION_RESOLVED = URI.create("https://edutrack/errors/escalation-already-resolved");

    /** 404 — no such row, or one out of the caller's A-112 scope. See the exception's own javadoc. */
    @ExceptionHandler(EscalationNotFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(EscalationNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Not found");
        problem.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    /** 422 — already resolved, on both {@code acknowledge} and {@code resolve}. */
    @ExceptionHandler(EscalationAlreadyResolvedException.class)
    ResponseEntity<ProblemDetail> handleAlreadyResolved(EscalationAlreadyResolvedException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(ESCALATION_RESOLVED);
        problem.setTitle("This rung is already resolved");
        problem.setDetail(e.getMessage());
        return ResponseEntity.unprocessableEntity().body(problem);
    }
}
