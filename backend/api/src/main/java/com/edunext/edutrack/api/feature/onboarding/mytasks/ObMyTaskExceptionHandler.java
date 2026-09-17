package com.edunext.edutrack.api.feature.onboarding.mytasks;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * RFC 9457 problem documents for {@link ObMyTaskController}
 * ({@code CONVENTIONS.md} §3).
 *
 * <p>Scoped by {@code assignableTypes}, on the precedent every handler in this
 * repository follows: a repository-wide {@code @RestControllerAdvice} is shared
 * surface four streams would edit, and no stream introduces one unilaterally.
 */
@RestControllerAdvice(assignableTypes = ObMyTaskController.class)
class ObMyTaskExceptionHandler {

    private static final URI NOT_FOUND = URI.create("https://edutrack/errors/not-found");

    /** 404 — no such task, or one belonging to somebody else. Indistinguishable, by design. */
    @ExceptionHandler(ObMyTaskNotFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(ObMyTaskNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(NOT_FOUND);
        problem.setTitle("Not found");
        problem.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }
}
