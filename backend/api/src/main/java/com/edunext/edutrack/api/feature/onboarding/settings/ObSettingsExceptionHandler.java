package com.edunext.edutrack.api.feature.onboarding.settings;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.Map;

/**
 * B-113 · RFC 9457 problem documents for {@link ObSettingsController}
 * ({@code CONVENTIONS.md} §3).
 *
 * <p>Scoped by {@code assignableTypes}, on the precedent every handler in this
 * repository follows: a repository-wide {@code @RestControllerAdvice} is shared
 * surface four streams would edit, and no stream introduces one unilaterally.
 */
@RestControllerAdvice(assignableTypes = {ObSettingsController.class, ObTemplateController.class})
class ObSettingsExceptionHandler {

    private static final URI VALIDATION_FAILED =
            URI.create("https://edutrack/errors/validation-failed");
    private static final URI NOT_FOUND = URI.create("https://edutrack/errors/not-found");
    private static final URI TEMPLATE_MANDATORY =
            URI.create("https://edutrack/errors/ob-template-mandatory");

    @ExceptionHandler(ObTemplateNotFoundException.class)
    ResponseEntity<ProblemDetail> handleTemplateNotFound(ObTemplateNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(NOT_FOUND);
        problem.setTitle("No such notification template");
        problem.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    /**
     * <p>409 rather than 403: nothing is wrong with the caller, and the request
     * would be fine against a different template. The screen should render the
     * toggle as a locked statement so this is never reached from OB-12 — see
     * {@link MandatoryTemplateException}.
     */
    @ExceptionHandler(MandatoryTemplateException.class)
    ResponseEntity<ProblemDetail> handleMandatory(MandatoryTemplateException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(TEMPLATE_MANDATORY);
        problem.setTitle("This notification cannot be switched off");
        problem.setDetail(e.getMessage());
        problem.setProperty("forceable", false);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * <p>{@code errors.bodyTemplate} names the field so the editor can put the
     * message beside the box the tag was typed into, and {@code unknownTags}
     * carries the tags themselves so it can highlight them rather than asking
     * the admin to re-read their own paragraph.
     */
    @ExceptionHandler(UnknownMergeTagException.class)
    ResponseEntity<ProblemDetail> handleUnknownTag(UnknownMergeTagException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(VALIDATION_FAILED);
        problem.setTitle("Unknown merge tag");
        problem.setDetail(e.getMessage());
        problem.setProperty("unknownTags", e.tags());
        problem.setProperty("errors", Map.of("bodyTemplate", new String[]{e.getMessage()}));
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(InvalidLadderException.class)
    ResponseEntity<ProblemDetail> handleLadder(InvalidLadderException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(VALIDATION_FAILED);
        problem.setTitle("The escalation ladder is not valid");
        problem.setDetail(e.getMessage());
        problem.setProperty("errors", Map.of("ladder", new String[]{e.getMessage()}));
        return ResponseEntity.badRequest().body(problem);
    }
}
