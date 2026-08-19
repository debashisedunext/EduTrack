package com.edunext.edutrack.api.feature.masters.stages;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.Map;

/**
 * B-040 · RFC 9457 problem documents for the Stage Master
 * ({@code CONVENTIONS.md} §3).
 *
 * <p><b>Scoped to {@link StageController}</b>, for the reason
 * {@code CalendarExceptionHandler} gives and every masters handler since repeats:
 * a repository-wide {@code @RestControllerAdvice} is shared surface four streams
 * would edit daily, and no stream should introduce one unilaterally.
 *
 * <p>{@code type} is the stable part clients branch on. Five refusals, and they
 * are five because each has a different remedy the S-13 form can act on without
 * parsing prose — "pick another code", "you cannot rename this one", "that order
 * breaks a return path", "no such template", "fix this field".
 */
@RestControllerAdvice(assignableTypes = StageController.class)
class StageExceptionHandler {

    private static final URI VALIDATION = URI.create("https://edutrack/errors/validation");
    private static final URI DUPLICATE = URI.create("https://edutrack/errors/duplicate");
    private static final URI IMMUTABLE = URI.create("https://edutrack/errors/immutable-field");
    private static final URI NOT_FOUND = URI.create("https://edutrack/errors/not-found");
    private static final URI RETURN_DIRECTION =
            URI.create("https://edutrack/errors/return-target-direction");

    @ExceptionHandler(StageService.DuplicateStageException.class)
    ResponseEntity<ProblemDetail> handleDuplicate(StageService.DuplicateStageException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(DUPLICATE);
        problem.setTitle("Duplicate stage code");
        problem.setDetail(e.getMessage());
        problem.setProperty("errors", Map.of(e.field(), new String[]{e.getMessage()}));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * A rename refused because the stage is in use.
     *
     * <p>Both counts travel as properties beside the prose, the way
     * {@code StatusExceptionHandler} carries {@code ticketCount}. The screen builds
     * its own sentence from them — and the numbers are the whole argument here, so
     * a client that had only the prose would be re-parsing English to tell an
     * Admin how much history the rename would have broken.
     */
    @ExceptionHandler(StageService.ImmutableStageCodeException.class)
    ResponseEntity<ProblemDetail> handleImmutable(StageService.ImmutableStageCodeException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(IMMUTABLE);
        problem.setTitle("Stage code cannot be changed");
        problem.setDetail(e.getMessage());
        problem.setProperty("transitionCount", e.transitionCount());
        problem.setProperty("openTicketCount", e.openTicketCount());
        problem.setProperty("errors", Map.of("stageCode", new String[]{e.getMessage()}));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * A reorder that would leave a return target pointing forwards.
     *
     * <p>Its own {@code type} rather than folding into {@code validation}, because
     * the failure is not about a field's shape: every id in {@code stageIds} is
     * legal, the list is complete, and what is wrong is a consequence of the order
     * for rows the caller did not send. The offending pairs travel as a property
     * so the screen can highlight both ends of each one on the ribbon it is
     * already drawing — an Admin told only "that order is invalid" would have
     * eight rows to inspect and no indication which two.
     */
    @ExceptionHandler(StageService.ReturnTargetDirectionException.class)
    ResponseEntity<ProblemDetail> handleDirection(StageService.ReturnTargetDirectionException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(RETURN_DIRECTION);
        problem.setTitle("That order breaks a return path");
        problem.setDetail(e.getMessage());
        problem.setProperty("pairs", e.pairs());
        problem.setProperty("errors", Map.of("stageIds", new String[]{e.getMessage()}));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(StageService.TemplateNotFoundException.class)
    ResponseEntity<ProblemDetail> handleNoTemplate(StageService.TemplateNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(NOT_FOUND);
        problem.setTitle("No such workflow template");
        problem.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @ExceptionHandler(StageService.StageNotFoundException.class)
    ResponseEntity<ProblemDetail> handleNoStage(StageService.StageNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(NOT_FOUND);
        problem.setTitle("No such stage");
        problem.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    /**
     * 400 and field-keyed, so the rules Bean Validation cannot express — an owner
     * role that matches nobody, a return target that is not on this ribbon or
     * points the wrong way, an incomplete reorder — land on an input exactly the
     * way a {@code @Pattern} failure would. A client that handles the standard 400
     * needs no new branch.
     */
    @ExceptionHandler(StageService.StageValidationException.class)
    ResponseEntity<ProblemDetail> handleValidation(StageService.StageValidationException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(VALIDATION);
        problem.setTitle("Validation failed");
        problem.setDetail(e.getMessage());
        problem.setProperty("errors", Map.of(e.field(), new String[]{e.getMessage()}));
        return ResponseEntity.badRequest().body(problem);
    }
}
