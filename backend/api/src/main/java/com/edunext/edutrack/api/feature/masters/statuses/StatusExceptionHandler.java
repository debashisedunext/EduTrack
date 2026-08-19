package com.edunext.edutrack.api.feature.masters.statuses;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.Map;

/**
 * B-039 · RFC 9457 problem documents for the Status Master
 * ({@code CONVENTIONS.md} §3).
 *
 * <p><b>Scoped to {@link StatusController}</b>, for the reason
 * {@code CalendarExceptionHandler} gives and {@code RoleExceptionHandler},
 * {@code TaskTypeExceptionHandler} and {@code PriorityExceptionHandler} repeat: a
 * repository-wide {@code @RestControllerAdvice} is shared surface four streams
 * would edit daily, and no stream should introduce one unilaterally.
 *
 * <p>{@code type} is the stable part clients branch on. Six refusals, and they are
 * six because each has a different remedy the S-13 form can act on without
 * parsing prose — "pick another code", "you cannot change this one", "those two
 * flags contradict", "move the tickets first", "that code matches nobody",
 * "somebody has to be able to raise a ticket".
 */
@RestControllerAdvice(assignableTypes = StatusController.class)
class StatusExceptionHandler {

    private static final URI VALIDATION = URI.create("https://edutrack/errors/validation");
    private static final URI DUPLICATE = URI.create("https://edutrack/errors/duplicate");
    private static final URI IMMUTABLE = URI.create("https://edutrack/errors/immutable-field");
    private static final URI IN_USE = URI.create("https://edutrack/errors/in-use");
    private static final URI CONTRADICTORY =
            URI.create("https://edutrack/errors/contradictory-state");
    private static final URI NO_CREATE_TRANSITION =
            URI.create("https://edutrack/errors/no-create-transition");

    /**
     * 409 rather than 400: the request is well formed and would have been accepted
     * a moment earlier. Field-keyed all the same — the message belongs on the
     * {@code code} input or on the {@code name} input, and which one it is comes
     * off the exception rather than out of the sentence.
     */
    @ExceptionHandler(StatusService.DuplicateStatusException.class)
    ResponseEntity<ProblemDetail> handleDuplicate(StatusService.DuplicateStatusException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(DUPLICATE);
        problem.setTitle("Duplicate status");
        problem.setDetail(e.getMessage());
        problem.setProperty("errors", Map.of(e.field(), new String[]{e.getMessage()}));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(StatusService.ImmutableStatusCodeException.class)
    ResponseEntity<ProblemDetail> handleImmutable(StatusService.ImmutableStatusCodeException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(IMMUTABLE);
        problem.setTitle("Status code cannot be changed");
        problem.setDetail(e.getMessage());
        problem.setProperty("errors", Map.of("code", new String[]{e.getMessage()}));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * Terminal and open at once.
     *
     * <p>Its own {@code type} rather than folding into {@code validation}, because
     * the failure is not about a field's shape — both values are individually
     * legal and it is their combination that is not. Keyed to {@code isTerminal},
     * which is the switch a user has to move: {@code isOpen} defaulting to true is
     * the ordinary case, and flagging that one would tell them to change the
     * setting they did not touch.
     */
    @ExceptionHandler(StatusService.ContradictoryStatusException.class)
    ResponseEntity<ProblemDetail> handleContradictory(
            StatusService.ContradictoryStatusException e) {

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(CONTRADICTORY);
        problem.setTitle("Those two flags contradict each other");
        problem.setDetail(e.getMessage());
        problem.setProperty("errors", Map.of("isTerminal", new String[]{e.getMessage()}));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * A retire refused because tickets are still in this status.
     *
     * <p>The count travels as a property beside the prose, the way
     * {@code RoleExceptionHandler} carries {@code userCount} and
     * {@code PriorityExceptionHandler} carries {@code taskTypeCount}. The screen
     * renders its own sentence from it — and in this case a link to the ticket
     * list filtered to the status, which is the actual next step.
     */
    @ExceptionHandler(StatusService.StatusInUseException.class)
    ResponseEntity<ProblemDetail> handleInUse(StatusService.StatusInUseException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(IN_USE);
        problem.setTitle("Status is in use");
        problem.setDetail(e.getMessage());
        problem.setProperty("ticketCount", e.ticketCount());
        problem.setProperty("errors", Map.of("isActive", new String[]{e.getMessage()}));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * A matrix cell naming a code nothing resolves, a self-transition, or the same
     * cell twice.
     *
     * <p>409 rather than 400, and the distinction is worth stating because the
     * first of the three looks like a validation failure. It is not: the body is
     * well formed and every value is a legal string. What is wrong is that the
     * value does not match anything in another table — a state of the world, not a
     * shape. And it is the failure mode the database cannot catch, since neither
     * column has a foreign key.
     */
    @ExceptionHandler(StatusTransitionService.InvalidTransitionException.class)
    ResponseEntity<ProblemDetail> handleInvalidTransition(
            StatusTransitionService.InvalidTransitionException e) {

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(VALIDATION);
        problem.setTitle("That transition cannot be stored");
        problem.setDetail(e.getMessage());
        problem.setProperty("errors", Map.of(e.field(), new String[]{e.getMessage()}));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * The one edit that can lock the product out of itself.
     *
     * <p>Its own {@code type}, and it earns one more than any other refusal here:
     * the remedy is not "fix this field" but "restore a row you are about to
     * delete", and it is the only message on this screen whose consequence is
     * global rather than local. A client that lumped it in with the other
     * transition refusals would show an Admin a field-level error for something
     * that would have stopped every user in the organisation raising a ticket.
     */
    @ExceptionHandler(StatusTransitionService.NoCreateTransitionException.class)
    ResponseEntity<ProblemDetail> handleNoCreate(
            StatusTransitionService.NoCreateTransitionException e) {

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(NO_CREATE_TRANSITION);
        problem.setTitle("Somebody has to be able to raise a ticket");
        problem.setDetail(e.getMessage());
        problem.setProperty("errors", Map.of("transitions", new String[]{e.getMessage()}));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * 400 and field-keyed, so the rule Bean Validation cannot express — the closed
     * eight-code set — lands on an input exactly the way a {@code @Pattern}
     * failure would. A client that handles the standard 400 needs no new branch.
     */
    @ExceptionHandler(StatusService.StatusValidationException.class)
    ResponseEntity<ProblemDetail> handleValidation(StatusService.StatusValidationException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(VALIDATION);
        problem.setTitle("Validation failed");
        problem.setDetail(e.getMessage());
        problem.setProperty("errors", Map.of(e.field(), new String[]{e.getMessage()}));
        return ResponseEntity.badRequest().body(problem);
    }
}
