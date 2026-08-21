package com.edunext.edutrack.api.feature.transitions;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.Map;

/**
 * C-047 · RFC 9457 problem documents for the skip route
 * ({@code CONVENTIONS.md} §3) — {@code ForceMoveExceptionHandler}'s own shape,
 * one route over.
 *
 * <p><b>Scoped by {@code assignableTypes}</b>, for the reason every sibling
 * handler in this package gives: a repository-wide
 * {@code @RestControllerAdvice} is shared surface no stream should introduce
 * unilaterally.
 *
 * <p>No 403 here, on {@code ForceMoveExceptionHandler}'s reasoning:
 * {@code ticket.skip_stage} is Admin and PM's alone, so a caller without it
 * never reaches {@link SkipController}.
 */
@RestControllerAdvice(assignableTypes = SkipController.class)
class SkipExceptionHandler {

    private static final URI VALIDATION = URI.create("https://edutrack/errors/validation");
    private static final URI STAGE_NOT_OPTIONAL = URI.create("https://edutrack/errors/stage-not-optional");
    private static final URI STAGE_OWNER_REQUIRED = URI.create("https://edutrack/errors/stage-owner-required");
    private static final URI NO_OPEN_STAGE = URI.create("https://edutrack/errors/no-open-stage");

    /**
     * 422 — the stage the ticket is standing in is not skippable. Not
     * field-keyed: {@link StageNotOptionalException}'s own javadoc explains
     * why, and in the common case the request carries no stage code at all for
     * a field error to attach to.
     */
    @ExceptionHandler(StageNotOptionalException.class)
    ResponseEntity<ProblemDetail> handleNotOptional(StageNotOptionalException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(STAGE_NOT_OPTIONAL);
        problem.setTitle("This stage may not be skipped");
        problem.setDetail(e.getMessage());
        return ResponseEntity.unprocessableEntity().body(problem);
    }

    /**
     * 422 — C-043's golden rule, {@link NotCurrentStageOwnerException}'s own
     * contract type. Defensive rather than load-bearing on this route: both
     * roles holding {@code ticket.skip_stage} already satisfy
     * {@code StageOwnership.mayAdvance} — see {@link SkipController}.
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

    /**
     * 400, field-keyed onto {@code toStageCode} — the ticket is in the
     * template's last stage, or carries no template at all, so there is no
     * next stage to default to and the caller must name one.
     * {@link NoNextStageException}'s message says exactly that.
     */
    @ExceptionHandler(NoNextStageException.class)
    ResponseEntity<ProblemDetail> handleNoNextStage(NoNextStageException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(VALIDATION);
        problem.setTitle("Validation failed");
        problem.setDetail(e.getMessage());
        problem.setProperty("errors", Map.of("toStageCode", new String[]{e.getMessage()}));
        return ResponseEntity.badRequest().body(problem);
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
