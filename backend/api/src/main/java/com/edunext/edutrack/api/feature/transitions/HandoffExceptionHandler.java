package com.edunext.edutrack.api.feature.transitions;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.Map;

/**
 * C-044 · RFC 9457 problem documents for the handoff route
 * ({@code CONVENTIONS.md} §3) — {@code ReopenExceptionHandler}'s and
 * {@code CloseExceptionHandler}'s own shape, one route over.
 *
 * <p><b>Scoped by {@code assignableTypes}</b>, for the reason those two give:
 * a repository-wide {@code @RestControllerAdvice} is shared surface no stream
 * should introduce unilaterally.
 *
 * <p>{@code NotCurrentStageOwnerException}'s own javadoc names its contract
 * type ({@code stage-owner-required}) and says "whichever controller C-044+
 * adds maps this exception onto that body" — this is that controller.
 *
 * <p>No 403 here, on {@code ReopenExceptionHandler}'s identical reasoning:
 * {@code ticket.handoff} is granted to all six roles, so a caller reaching
 * this route already holds the capability, and the entire question of
 * <em>which</em> ticket they may advance is answered by the two 4xx codes
 * below (plus A-035's 404, handled without help — {@code TicketNotFoundException}
 * is deliberately absent here too).
 */
@RestControllerAdvice(assignableTypes = HandoffController.class)
class HandoffExceptionHandler {

    private static final URI VALIDATION = URI.create("https://edutrack/errors/validation");
    private static final URI STAGE_OWNER_REQUIRED = URI.create("https://edutrack/errors/stage-owner-required");
    private static final URI NO_OPEN_STAGE = URI.create("https://edutrack/errors/no-open-stage");

    /**
     * 422 — {@link NotCurrentStageOwnerException}'s own contract type,
     * C-043's golden rule. Not field-keyed, on {@code ReopenExceptionHandler}'s
     * reasoning for its own 422: nothing the caller sent is wrong, they are
     * simply not this ticket's current owner, PM or Admin.
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
     * 400, field-keyed onto {@code effortHours} — {@code EffortHoursRequiredException}'s
     * own javadoc names the default this enforces (G-1, blocking).
     */
    @ExceptionHandler(EffortHoursRequiredException.class)
    ResponseEntity<ProblemDetail> handleEffortRequired(EffortHoursRequiredException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(VALIDATION);
        problem.setTitle("Validation failed");
        problem.setDetail(e.getMessage());
        problem.setProperty("errors", Map.of("effortHours", new String[]{e.getMessage()}));
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
