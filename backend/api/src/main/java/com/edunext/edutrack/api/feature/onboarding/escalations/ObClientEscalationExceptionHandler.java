package com.edunext.edutrack.api.feature.onboarding.escalations;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * C-126 · RFC 9457 problem documents for {@link ObClientEscalationController}
 * ({@code CONVENTIONS.md} §3), scoped by {@code assignableTypes} on {@code
 * ObEscalationExceptionHandler}'s own precedent one class over.
 */
@RestControllerAdvice(assignableTypes = ObClientEscalationController.class)
class ObClientEscalationExceptionHandler {

    private static final URI ESCALATION_RESOLVED =
            URI.create("https://edutrack/errors/client-escalation-already-resolved");

    /** 404 — no such row, or one out of the caller's A-112 scope. */
    @ExceptionHandler(ObClientEscalationNotFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(ObClientEscalationNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Not found");
        problem.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    /** 422 — already resolved. */
    @ExceptionHandler(ObClientEscalationAlreadyResolvedException.class)
    ResponseEntity<ProblemDetail> handleAlreadyResolved(ObClientEscalationAlreadyResolvedException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(ESCALATION_RESOLVED);
        problem.setTitle("This escalation is already resolved");
        problem.setDetail(e.getMessage());
        return ResponseEntity.unprocessableEntity().body(problem);
    }
}
