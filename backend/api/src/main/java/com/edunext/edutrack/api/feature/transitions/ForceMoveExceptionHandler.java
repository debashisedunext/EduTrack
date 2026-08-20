package com.edunext.edutrack.api.feature.transitions;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.Map;

/**
 * C-048 · RFC 9457 problem documents for the force-move route
 * ({@code CONVENTIONS.md} §3) — {@code HandoffExceptionHandler}'s own shape,
 * one route over.
 *
 * <p><b>Scoped by {@code assignableTypes}</b>, for the reason every sibling
 * handler in this package gives: a repository-wide {@code @RestControllerAdvice}
 * is shared surface no stream should introduce unilaterally.
 *
 * <p>No 403 here, on {@code CloseExceptionHandler}'s reasoning rather than
 * {@code HandoffExceptionHandler}'s: {@code ticket.force_move} is Admin and
 * PM's alone, so the capability itself is the whole authorisation question,
 * and a caller without it never reaches {@link ForceMoveController}.
 */
@RestControllerAdvice(assignableTypes = ForceMoveController.class)
class ForceMoveExceptionHandler {

    private static final URI VALIDATION = URI.create("https://edutrack/errors/validation");
    private static final URI STAGE_OWNER_REQUIRED = URI.create("https://edutrack/errors/stage-owner-required");
    private static final URI NO_OPEN_STAGE = URI.create("https://edutrack/errors/no-open-stage");

    /**
     * 422 — {@link NotCurrentStageOwnerException}'s own contract type. In
     * practice unreachable through this route (see {@code ForceMoveService}'s
     * class javadoc) but mapped anyway, on {@code HandoffExceptionHandler}'s
     * own precedent for the identical exception.
     */
    @ExceptionHandler(NotCurrentStageOwnerException.class)
    ResponseEntity<ProblemDetail> handleNotStageOwner(NotCurrentStageOwnerException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(STAGE_OWNER_REQUIRED);
        problem.setTitle("Only the current stage owner may advance this ticket");
        problem.setDetail(e.getMessage());
        return ResponseEntity.unprocessableEntity().body(problem);
    }

    /** 422 — no open hop to advance from ({@code TransitionService.advance}'s own doc). */
    @ExceptionHandler(NoOpenStageException.class)
    ResponseEntity<ProblemDetail> handleNoOpenStage(NoOpenStageException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(NO_OPEN_STAGE);
        problem.setTitle("This ticket has no open stage to advance from");
        problem.setDetail(e.getMessage());
        return ResponseEntity.unprocessableEntity().body(problem);
    }

    /** 400, field-keyed onto {@code toStageCode} — not a stage of this ticket's template. */
    @ExceptionHandler(UnknownTransitionStageException.class)
    ResponseEntity<ProblemDetail> handleUnknownStage(UnknownTransitionStageException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(VALIDATION);
        problem.setTitle("Validation failed");
        problem.setDetail(e.getMessage());
        problem.setProperty("errors", Map.of("toStageCode", new String[]{e.getMessage()}));
        return ResponseEntity.badRequest().body(problem);
    }
}
