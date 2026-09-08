package com.edunext.edutrack.api.feature.onboarding.communications;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * C-112 · RFC 9457 problem documents for the two communication controllers
 * ({@code CONVENTIONS.md} §3), scoped by {@code assignableTypes} on {@code
 * ObEscalationExceptionHandler}'s own precedent — a repository-wide handler
 * is shared surface every stream would edit, and this package is Stream C's
 * alone.
 */
@RestControllerAdvice(assignableTypes = {
        ObStepCommunicationController.class,
        ObClientCommunicationController.class,
})
class ObCommunicationExceptionHandler {

    /** 404 — no such service, or one out of the caller's A-112 scope. See the exception's own javadoc. */
    @ExceptionHandler(CommunicationStepNotFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(CommunicationStepNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Not found");
        problem.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }
}
