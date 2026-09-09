package com.edunext.edutrack.api.feature.onboarding.dashboard;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * B-127 · RFC 9457 problem documents for {@link ObDashboardController},
 * scoped by {@code assignableTypes} on {@code ObEscalationExceptionHandler}'s
 * own precedent — a repository-wide handler is shared surface every stream
 * would edit, and this package is Stream B's alone.
 */
@RestControllerAdvice(assignableTypes = ObDashboardController.class)
class ObDashboardExceptionHandler {

    /** 400 — the contract's own wording for both failures this route can make on its input. */
    @ExceptionHandler({UnrecognisedCardKeyException.class, InvalidCursorException.class})
    ResponseEntity<ProblemDetail> handleBadInput(RuntimeException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Bad request");
        problem.setDetail(e.getMessage());
        return ResponseEntity.badRequest().body(problem);
    }
}
