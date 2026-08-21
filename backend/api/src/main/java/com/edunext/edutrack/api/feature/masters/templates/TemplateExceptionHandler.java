package com.edunext.edutrack.api.feature.masters.templates;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.Map;

/**
 * B-041 · RFC 9457 problem documents for the Workflow Template Master
 * ({@code CONVENTIONS.md} §3).
 *
 * <p><b>Scoped to {@link TemplateController}</b>, for the reason
 * {@code CalendarExceptionHandler} gives and every masters handler since repeats:
 * a repository-wide {@code @RestControllerAdvice} is shared surface four streams
 * would edit daily, and no stream should introduce one unilaterally.
 *
 * <p>Six refusals, and they are six because each has a different remedy tab 3 can
 * act on without parsing prose — "pick another name", "that pair belongs to
 * another template", "hand the default over first", "give it a stage first",
 * "re-point the rules that name it", "that id does not exist".
 */
@RestControllerAdvice(assignableTypes = TemplateController.class)
class TemplateExceptionHandler {

    private static final URI VALIDATION = URI.create("https://edutrack/errors/validation");
    private static final URI DUPLICATE = URI.create("https://edutrack/errors/duplicate");
    private static final URI TEMPLATE_IN_USE =
            URI.create("https://edutrack/errors/template-in-use");
    private static final URI MAPPING_CLAIMED =
            URI.create("https://edutrack/errors/mapping-claimed");
    private static final URI LAST_DEFAULT = URI.create("https://edutrack/errors/last-default");
    private static final URI EMPTY_TEMPLATE = URI.create("https://edutrack/errors/empty-template");

    @ExceptionHandler(TemplateService.DuplicateTemplateException.class)
    ResponseEntity<ProblemDetail> handleDuplicate(TemplateService.DuplicateTemplateException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(DUPLICATE);
        problem.setTitle("Duplicate template name");
        problem.setDetail(e.getMessage());
        problem.setProperty("errors", Map.of("name", new String[]{e.getMessage()}));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * A template that is in service, refused for deactivation or for deletion.
     *
     * <p><b>One type for both verbs, and the counts are what tell them apart.</b>
     * That is B-042's call on {@code return-target-direction} — one type covering
     * a reorder and a retire — and the same reasoning: the screen highlights the
     * same thing either way, only the sentence differs, and both sentences are
     * built from properties already on the document.
     *
     * <p>{@code canDeactivate} rides along on the delete's refusal for the reason
     * {@code stage-in-use} carries its own flag: an Admin told "no" with no
     * alternative concludes the row cannot be got rid of at all. It is
     * {@code false} when the refusal <em>was</em> the deactivation, which is the
     * case where there is genuinely no second offer to make — the remedy is on the
     * rules, not on this row.
     */
    @ExceptionHandler(TemplateService.TemplateInUseException.class)
    ResponseEntity<ProblemDetail> handleInUse(TemplateService.TemplateInUseException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(TEMPLATE_IN_USE);
        problem.setTitle("Template in use");
        problem.setDetail(e.getMessage());
        problem.setProperty("ticketCount", e.ticketCount());
        problem.setProperty("mappingCount", e.mappingCount());
        // A template with history but no rules can still be switched off, which is
        // the ordinary way to retire one. A flag rather than prose to be matched
        // on, so a rewording cannot change what the screen offers.
        problem.setProperty("canDeactivate", e.mappingCount() == 0 && e.ticketCount() > 0);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * A pair already routed by another template.
     *
     * <p>Its own {@code type} rather than {@code duplicate}, which is the
     * neighbouring refusal and reads as though it would fit. It does not: that one
     * is about a field the caller sent being taken, keyed to {@code name}, and its
     * remedy is to type something else. This is about a row on <b>another
     * template's</b> screen, and the remedy is to go there — so the offending
     * template's id and name travel as properties, and the screen renders a link
     * rather than asking the Admin to find it.
     */
    @ExceptionHandler(TemplateService.MappingClaimedException.class)
    ResponseEntity<ProblemDetail> handleClaimed(TemplateService.MappingClaimedException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(MAPPING_CLAIMED);
        problem.setTitle("That pair already routes somewhere");
        problem.setDetail(e.getMessage());
        problem.setProperty("claimedByTemplateId", e.templateId());
        problem.setProperty("claimedByTemplateName", e.templateName());
        problem.setProperty("projectId", e.projectId());
        problem.setProperty("taskTypeId", e.taskTypeId());
        problem.setProperty("errors", Map.of("mappings", new String[]{e.getMessage()}));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * The default template, refused for clearing, deactivating or deleting.
     *
     * <p>409 rather than 400 because nothing about the request is malformed: the
     * template exists, the caller may write it, and the verb is right. What
     * refuses it is the state the <em>system</em> would be left in — every
     * unmapped project × task type resolving to nothing — and the remedy is on a
     * different row than the one the Admin is looking at, which is why it is not
     * keyed to a field.
     */
    @ExceptionHandler(TemplateService.LastDefaultException.class)
    ResponseEntity<ProblemDetail> handleLastDefault(TemplateService.LastDefaultException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(LAST_DEFAULT);
        problem.setTitle("This is the default template");
        problem.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * A template with no live stage, refused the default flag — and the inactive
     * variant of the same refusal.
     *
     * <p>Both share a {@code type} because both mean "this template cannot route a
     * ticket yet" and both are fixed on this screen, one tab over. A ribbon with
     * nothing live routes nothing, and no screen would notice: the ticket would be
     * created, resolve to the template, and find no first stage to enter. That is
     * B-042's last-live-stage argument arriving one table up.
     */
    @ExceptionHandler({TemplateService.EmptyTemplateException.class,
            TemplateService.InactiveDefaultException.class})
    ResponseEntity<ProblemDetail> handleEmpty(RuntimeException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(EMPTY_TEMPLATE);
        problem.setTitle("This template cannot be the default");
        problem.setDetail(e.getMessage());
        problem.setProperty("errors", Map.of("isDefault", new String[]{e.getMessage()}));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * An id naming nothing.
     *
     * <p>400 rather than 404, and the distinction is which noun the request is
     * about. The route's own subject is the template in the path, and that one
     * does exist — what is wrong is a <em>value inside the body</em>, which is a
     * validation failure keyed to the field carrying it. A 404 here would tell the
     * caller the template was missing, which is the one thing it is not.
     *
     * <p>The foreign keys would refuse the same id at flush time as an
     * uncategorised SQL exception with no field name on it. This is what turns
     * that into a highlighted row.
     */
    @ExceptionHandler(TemplateService.UnknownReferenceException.class)
    ResponseEntity<ProblemDetail> handleUnknown(TemplateService.UnknownReferenceException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(VALIDATION);
        problem.setTitle("Unknown reference");
        problem.setDetail(e.getMessage());
        problem.setProperty("errors", Map.of(e.field(), new String[]{e.getMessage()}));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }
}
