package com.edunext.edutrack.api.feature.onboarding.signoff;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * B-116 · RFC 9457 problem documents for {@link ObSignoffCertificateController}.
 *
 * <p>Scoped by {@code assignableTypes}, on {@code ObClientExceptionHandler}'s
 * own precedent: a repository-wide {@code @RestControllerAdvice} is shared
 * surface four streams would edit, and no stream introduces one unilaterally.
 * The {@code type} URI is the same {@code not-found} one every onboarding 404
 * already publishes — CONVENTIONS.md §3 says clients branch on {@code type},
 * and "no such row" is one decision made once, whichever surface refuses it.
 */
@RestControllerAdvice(assignableTypes = ObSignoffCertificateController.class)
class ObSignoffCertificateExceptionHandler {

    private static final URI NOT_FOUND = URI.create("https://edutrack/errors/not-found");

    /** 404 — see the exception for the four cases this cannot and must not distinguish. */
    @ExceptionHandler(ObSignoffCertificateNotFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(ObSignoffCertificateNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(NOT_FOUND);
        problem.setTitle("Not found");
        problem.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }
}
