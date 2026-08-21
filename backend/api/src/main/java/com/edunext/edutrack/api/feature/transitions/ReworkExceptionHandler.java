package com.edunext.edutrack.api.feature.transitions;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.Map;

/**
 * C-046 · RFC 9457 problem documents for the rework route
 * ({@code CONVENTIONS.md} §3) — {@code HandoffExceptionHandler}'s own shape,
 * one route over, and scoped by {@code assignableTypes} for its reason: a
 * repository-wide {@code @RestControllerAdvice} is shared surface no stream
 * should introduce unilaterally.
 */
@RestControllerAdvice(assignableTypes = ReworkController.class)
class ReworkExceptionHandler {

    private static final URI VALIDATION = URI.create("https://edutrack/errors/validation");
    private static final URI STAGE_OWNER_REQUIRED = URI.create("https://edutrack/errors/stage-owner-required");
    private static final URI NO_OPEN_STAGE = URI.create("https://edutrack/errors/no-open-stage");
    private static final URI RETURN_NOT_ALLOWED = URI.create("https://edutrack/errors/return-not-allowed");

    /**
     * 422 — the destination is a real stage but not one this stage may return
     * to. Not field-keyed: {@link StageMayNotReturnToException}'s own javadoc
     * explains why a perfectly valid dropdown value must not be rendered as a
     * field error.
     */
    @ExceptionHandler(StageMayNotReturnToException.class)
    ResponseEntity<ProblemDetail> handleReturnNotAllowed(StageMayNotReturnToException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(RETURN_NOT_ALLOWED);
        problem.setTitle("This stage may not return to that one");
        problem.setDetail(e.getMessage());
        return ResponseEntity.unprocessableEntity().body(problem);
    }

    /** 422 — C-043's golden rule, {@code NotCurrentStageOwnerException}'s own contract type. */
    @ExceptionHandler(NotCurrentStageOwnerException.class)
    ResponseEntity<ProblemDetail> handleNotStageOwner(NotCurrentStageOwnerException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(STAGE_OWNER_REQUIRED);
        problem.setTitle("Only the current stage owner may move this ticket");
        problem.setDetail(e.getMessage());
        return ResponseEntity.unprocessableEntity().body(problem);
    }

    /** 422 — no open hop to move from. */
    @ExceptionHandler(NoOpenStageException.class)
    ResponseEntity<ProblemDetail> handleNoOpenStage(NoOpenStageException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(NO_OPEN_STAGE);
        problem.setTitle("This ticket has no open stage to move from");
        problem.setDetail(e.getMessage());
        return ResponseEntity.unprocessableEntity().body(problem);
    }

    /** 400, field-keyed onto {@code toStageCode} — not a stage of this ticket's template at all. */
    @ExceptionHandler(UnknownTransitionStageException.class)
    ResponseEntity<ProblemDetail> handleUnknownStage(UnknownTransitionStageException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(VALIDATION);
        problem.setTitle("Validation failed");
        problem.setDetail(e.getMessage());
        problem.setProperty("errors", Map.of("toStageCode", new String[]{e.getMessage()}));
        return ResponseEntity.badRequest().body(problem);
    }

    /** 400, field-keyed onto {@code action} — not one of §4A.1's four backward actions. */
    @ExceptionHandler(NotABackwardActionException.class)
    ResponseEntity<ProblemDetail> handleNotBackward(NotABackwardActionException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(VALIDATION);
        problem.setTitle("Validation failed");
        problem.setDetail(e.getMessage());
        problem.setProperty("errors", Map.of("action", new String[]{e.getMessage()}));
        return ResponseEntity.badRequest().body(problem);
    }
}
