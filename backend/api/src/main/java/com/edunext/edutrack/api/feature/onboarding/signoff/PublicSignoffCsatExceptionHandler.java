package com.edunext.edutrack.api.feature.onboarding.signoff;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * B-119 · the two {@code 422}s {@code submitObCsat} can answer with.
 *
 * <p>Scoped to {@link PublicSignoffCsatController} by {@code assignableTypes}
 * rather than folded into {@link PublicSignoffExceptionHandler}, whose own
 * javadoc is explicit that it renders "the surface's two refusals, and only
 * these two" — the shared {@code 401} and {@code 429}. A third case there
 * would contradict that sentence; {@code ObEscalationExceptionHandler}'s own
 * precedent one module over is the same call for the same reason: a 422 that
 * only one route can throw is that route's exception handler, not the
 * surface's.
 */
@RestControllerAdvice(assignableTypes = PublicSignoffCsatController.class)
class PublicSignoffCsatExceptionHandler {

    private static final URI NOT_OFFERED =
            URI.create("https://edutrack/errors/csat-not-offered");
    private static final URI ALREADY_SUBMITTED =
            URI.create("https://edutrack/errors/csat-already-submitted");

    @ExceptionHandler(CsatNotOfferedException.class)
    ResponseEntity<ProblemDetail> notOffered(CsatNotOfferedException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(NOT_OFFERED);
        problem.setTitle("Not a go-live session");
        problem.setDetail(e.getMessage());
        return ResponseEntity.unprocessableEntity().body(problem);
    }

    @ExceptionHandler(CsatAlreadySubmittedException.class)
    ResponseEntity<ProblemDetail> alreadySubmitted(CsatAlreadySubmittedException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(ALREADY_SUBMITTED);
        problem.setTitle("Already surveyed");
        problem.setDetail(e.getMessage());
        return ResponseEntity.unprocessableEntity().body(problem);
    }
}
