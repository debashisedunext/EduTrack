package com.edunext.edutrack.api.feature.masters.priorities;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.Map;

/**
 * B-021 · RFC 9457 problem documents for the Priority Master
 * ({@code CONVENTIONS.md} §3).
 *
 * <p><b>Scoped to {@link PriorityController}</b>, for the reason
 * {@code CalendarExceptionHandler} gives and {@code RoleExceptionHandler} and
 * {@code TaskTypeExceptionHandler} repeat: a repository-wide
 * {@code @RestControllerAdvice} is shared surface four streams would edit daily,
 * and no stream should introduce one unilaterally.
 *
 * <p>{@code type} is the stable part clients branch on. All four refusals here
 * are genuinely different situations with different remedies — "pick another
 * code", "you cannot change this one", "move the escalation flag first",
 * "repoint the task types first" — and the S-12 form tells them apart by URI
 * rather than by parsing prose.
 */
@RestControllerAdvice(assignableTypes = PriorityController.class)
class PriorityExceptionHandler {

    private static final URI VALIDATION = URI.create("https://edutrack/errors/validation");
    private static final URI DUPLICATE = URI.create("https://edutrack/errors/duplicate");
    private static final URI IMMUTABLE = URI.create("https://edutrack/errors/immutable-field");
    private static final URI IN_USE = URI.create("https://edutrack/errors/in-use");
    private static final URI ESCALATION_TARGET =
            URI.create("https://edutrack/errors/escalation-target-required");

    /**
     * 409 rather than 400: the request is well formed and would have been
     * accepted a moment earlier. Field-keyed all the same — the message belongs
     * on the {@code level} input or on the {@code name} input, and which one it
     * is comes off the exception rather than out of the sentence.
     */
    @ExceptionHandler(PriorityService.DuplicatePriorityException.class)
    ResponseEntity<ProblemDetail> handleDuplicate(PriorityService.DuplicatePriorityException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(DUPLICATE);
        problem.setTitle("Duplicate priority level");
        problem.setDetail(e.getMessage());
        problem.setProperty("errors", Map.of(e.field(), new String[]{e.getMessage()}));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(PriorityService.ImmutablePriorityCodeException.class)
    ResponseEntity<ProblemDetail> handleImmutable(
            PriorityService.ImmutablePriorityCodeException e) {

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(IMMUTABLE);
        problem.setTitle("Priority code cannot be changed");
        problem.setDetail(e.getMessage());
        problem.setProperty("errors", Map.of("level", new String[]{e.getMessage()}));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * The §6 escalation target is missing, ambiguous or retired.
     *
     * <p>Its own {@code type} rather than folding into {@code in-use}, because
     * the remedy is completely different and is a single sentence the screen can
     * act on: move the flag to another level, then retry. A client that lumped
     * this in with "something references this row" would tell the admin to go
     * looking for references that do not exist.
     *
     * <p>Field-keyed to whichever input caused it — {@code autoEscalates} when
     * the flag was being cleared, {@code isActive} when a retire would have
     * orphaned it.
     */
    @ExceptionHandler(PriorityService.EscalationTargetException.class)
    ResponseEntity<ProblemDetail> handleEscalationTarget(
            PriorityService.EscalationTargetException e) {

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(ESCALATION_TARGET);
        problem.setTitle("The SLA engine needs exactly one escalation target");
        problem.setDetail(e.getMessage());
        problem.setProperty("errors", Map.of(e.field(), new String[]{e.getMessage()}));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * A retire refused because active task types still default to this level.
     *
     * <p>The count and the names travel as properties beside the prose, the way
     * {@code RoleExceptionHandler} carries {@code userCount} on
     * {@code role-in-use}. The screen renders its own sentence from them; the
     * {@code detail} is for a client that does not.
     */
    @ExceptionHandler(PriorityService.PriorityInUseException.class)
    ResponseEntity<ProblemDetail> handleInUse(PriorityService.PriorityInUseException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(IN_USE);
        problem.setTitle("Priority level is in use");
        problem.setDetail(e.getMessage());
        problem.setProperty("taskTypeCount", e.taskTypeCount());
        problem.setProperty("taskTypeNames", e.taskTypeNames());
        problem.setProperty("errors", Map.of("isActive", new String[]{e.getMessage()}));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * 400 and field-keyed, so the two rules Bean Validation cannot express land
     * on an input exactly the way a {@code @Pattern} failure would. A client
     * that handles the standard 400 needs no new branch for these.
     */
    @ExceptionHandler(PriorityService.PriorityValidationException.class)
    ResponseEntity<ProblemDetail> handleValidation(
            PriorityService.PriorityValidationException e) {

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(VALIDATION);
        problem.setTitle("Validation failed");
        problem.setDetail(e.getMessage());
        problem.setProperty("errors", Map.of(e.field(), new String[]{e.getMessage()}));
        return ResponseEntity.badRequest().body(problem);
    }
}
