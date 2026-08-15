package com.edunext.edutrack.api.feature.masters.notificationtemplates;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.Map;

/**
 * B-022 · RFC 9457 problem documents for the Notification Template Master
 * ({@code CONVENTIONS.md} §3).
 *
 * <p><b>Scoped to {@link NotificationTemplateController}</b>, for the reason
 * {@code CalendarExceptionHandler} gives and {@code RoleExceptionHandler},
 * {@code TaskTypeExceptionHandler} and {@code PriorityExceptionHandler} repeat: a
 * repository-wide {@code @RestControllerAdvice} is shared surface four streams
 * would edit daily, and no stream should introduce one unilaterally.
 *
 * <p>{@code type} is the stable part clients branch on. The four refusals here
 * are genuinely different situations with different remedies — "edit the one
 * that exists", "create a template on the other event instead", "this mail
 * cannot be switched off", "that placeholder resolves to nothing" — and the S-15
 * form tells them apart by URI rather than by parsing prose.
 */
@RestControllerAdvice(assignableTypes = NotificationTemplateController.class)
class NotificationTemplateExceptionHandler {

    private static final URI VALIDATION = URI.create("https://edutrack/errors/validation");
    private static final URI DUPLICATE = URI.create("https://edutrack/errors/duplicate");
    private static final URI IMMUTABLE = URI.create("https://edutrack/errors/immutable-field");
    private static final URI MANDATORY =
            URI.create("https://edutrack/errors/mandatory-notification");
    private static final URI UNKNOWN_MERGE_TAG =
            URI.create("https://edutrack/errors/unknown-merge-tag");

    /**
     * 409 rather than 400: the request is well formed, and the rule is about
     * what the rest of the table already holds.
     */
    @ExceptionHandler(NotificationTemplateService.DuplicateTemplateException.class)
    ResponseEntity<ProblemDetail> handleDuplicate(
            NotificationTemplateService.DuplicateTemplateException e) {

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(DUPLICATE);
        problem.setTitle("That event already has a template on this channel");
        problem.setDetail(e.getMessage());
        problem.setProperty("errors", Map.of("eventCode", new String[]{e.getMessage()}));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(NotificationTemplateService.ImmutableTemplateIdentityException.class)
    ResponseEntity<ProblemDetail> handleImmutable(
            NotificationTemplateService.ImmutableTemplateIdentityException e) {

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(IMMUTABLE);
        problem.setTitle("A template's event and channel cannot be changed");
        problem.setDetail(e.getMessage());
        problem.setProperty("errors", Map.of(e.field(), new String[]{e.getMessage()}));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * §4B.6's never-optional mail, being switched off.
     *
     * <p>Its own {@code type} rather than folding into {@code validation},
     * because the remedy is not "correct this value" — there is no value that
     * would be accepted. The screen uses it to render the toggle as a locked
     * statement rather than a control whose only outcome is this response, the
     * same call B-021's form makes on the escalation flag.
     *
     * <p>Field-keyed to {@code isActive}, which is the only input that can raise
     * it.
     */
    @ExceptionHandler(NotificationTemplateService.MandatoryTemplateException.class)
    ResponseEntity<ProblemDetail> handleMandatory(
            NotificationTemplateService.MandatoryTemplateException e) {

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(MANDATORY);
        problem.setTitle("This mail cannot be switched off");
        problem.setDetail(e.getMessage());
        problem.setProperty("errors", Map.of("isActive", new String[]{e.getMessage()}));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * A placeholder that resolves to nothing.
     *
     * <p>400 rather than 409 — it is a value the caller mistyped, and the fix is
     * in the box they are looking at. Both lists travel as properties beside the
     * prose, the way {@code PriorityExceptionHandler} carries
     * {@code taskTypeNames}: the screen highlights the offending tags in the
     * editor and offers the known ones, and {@code detail} is for a client that
     * does neither.
     */
    @ExceptionHandler(NotificationTemplateService.UnknownMergeTagException.class)
    ResponseEntity<ProblemDetail> handleUnknownMergeTag(
            NotificationTemplateService.UnknownMergeTagException e) {

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(UNKNOWN_MERGE_TAG);
        problem.setTitle("Unknown merge tag");
        problem.setDetail(e.getMessage());
        problem.setProperty("unknownTags", e.unknownTags());
        problem.setProperty("knownTags", e.knownTags());
        problem.setProperty("errors", Map.of("bodyTemplate", new String[]{e.getMessage()}));
        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * 400 and field-keyed, so the rules Bean Validation cannot express land on an
     * input exactly the way a {@code @Pattern} failure would. A client that
     * handles the standard 400 needs no new branch for these.
     */
    @ExceptionHandler(NotificationTemplateService.TemplateValidationException.class)
    ResponseEntity<ProblemDetail> handleValidation(
            NotificationTemplateService.TemplateValidationException e) {

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(VALIDATION);
        problem.setTitle("Validation failed");
        problem.setDetail(e.getMessage());
        problem.setProperty("errors", Map.of(e.field(), new String[]{e.getMessage()}));
        return ResponseEntity.badRequest().body(problem);
    }
}
