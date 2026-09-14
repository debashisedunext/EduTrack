package com.edunext.edutrack.api.feature.onboarding.projects;

import com.edunext.edutrack.api.feature.onboarding.instances.UnknownModuleServiceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RFC 9457 problem documents for {@link ObProjectController}
 * ({@code CONVENTIONS.md} §3).
 *
 * <p>Scoped by {@code assignableTypes}, on the precedent every handler in this
 * repository follows: a repository-wide {@code @RestControllerAdvice} is shared
 * surface four streams would edit, and no stream introduces one unilaterally.
 *
 * <p>The {@code type} URIs are the contract's own error names. CONVENTIONS.md §3
 * is explicit that clients branch on {@code type} and never on prose, so these
 * strings are the API and the sentences beside them are not.
 */
@RestControllerAdvice(assignableTypes = ObProjectController.class)
class ObProjectExceptionHandler {

    private static final URI NOT_FOUND = URI.create("https://edutrack/errors/not-found");
    private static final URI FORBIDDEN = URI.create("https://edutrack/errors/forbidden");
    private static final URI VALIDATION_FAILED = URI.create("https://edutrack/errors/validation-failed");
    private static final URI PROJECT_DUPLICATE = URI.create("https://edutrack/errors/ob-project-duplicate");
    private static final URI PROJECT_IN_USE = URI.create("https://edutrack/errors/ob-project-in-use");
    private static final URI PROJECT_STATUS_NOT_EARNED =
            URI.create("https://edutrack/errors/ob-project-status-not-earned");
    private static final URI NO_PREREQ_MASTER =
            URI.create("https://edutrack/errors/ob-client-no-prereq-master");
    private static final URI UNKNOWN_MODULE_SERVICE =
            URI.create("https://edutrack/errors/ob-project-unknown-module-service");

    /** 404 — no such project, or one out of the caller's scope. Indistinguishable, by design. */
    @ExceptionHandler(ObProjectNotFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(ObProjectNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, NOT_FOUND, "Not found", e.getMessage());
    }

    /** 404 — no standing to create a project. See the exception for why this is not 403. */
    @ExceptionHandler(NotAnOnboardingProjectWriterException.class)
    ResponseEntity<ProblemDetail> handleNotAWriter(NotAnOnboardingProjectWriterException e) {
        return problem(HttpStatus.NOT_FOUND, NOT_FOUND, "Not found", e.getMessage());
    }

    /** 403 — a project this caller can see and may not change. See the exception for why not 404. */
    @ExceptionHandler(ObProjectReadOnlyException.class)
    ResponseEntity<ProblemDetail> handleReadOnly(ObProjectReadOnlyException e) {
        return problem(HttpStatus.FORBIDDEN, FORBIDDEN, "Read-only in onboarding", e.getMessage());
    }

    /**
     * 400, field-keyed so each message lands on its own input.
     *
     * <p>{@code errors} maps to <b>string arrays</b>, which is what
     * {@code ValidationProblem} declares and what {@code ApiError.fieldErrors}
     * on the frontend reads. A bare string deserialises into a shape the form's
     * {@code messages[0]} silently indexes character by character.
     */
    @ExceptionHandler(ObProjectValidationException.class)
    ResponseEntity<ProblemDetail> handleValidation(ObProjectValidationException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(VALIDATION_FAILED);
        problem.setTitle("The project was not saved");
        problem.setDetail(e.getMessage());

        Map<String, String[]> errors = new LinkedHashMap<>();
        e.errors().forEach((field, message) -> errors.put(field, new String[]{message}));
        problem.setProperty("errors", errors);

        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * 409 — this client already has a project for this product.
     *
     * <p>{@code existingProjectId} rides on the problem so the form can offer
     * "open it" rather than only saying no. See the exception for why naming it
     * discloses nothing.
     */
    @ExceptionHandler(DuplicateProjectException.class)
    ResponseEntity<ProblemDetail> handleDuplicate(DuplicateProjectException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(PROJECT_DUPLICATE);
        problem.setTitle("Already a project for that product");
        problem.setDetail(e.getMessage());
        problem.setProperty("existingProjectId", e.existingProjectId());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * 409 — something records that this project ran, so it cannot be deleted.
     *
     * <p>{@code blockers} rides on the problem as an array so the screen can
     * name them and offer Dropped instead, rather than reprinting one sentence.
     */
    @ExceptionHandler(ObProjectInUseException.class)
    ResponseEntity<ProblemDetail> handleInUse(ObProjectInUseException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(PROJECT_IN_USE);
        problem.setTitle("The project has history");
        problem.setDetail(e.getMessage());
        problem.setProperty("blockers", e.blockers().toArray(String[]::new));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /** 422 — COMPLETED is earned. */
    @ExceptionHandler(ProjectStatusNotEarnedException.class)
    ResponseEntity<ProblemDetail> handleStatusNotEarned(ProjectStatusNotEarnedException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, PROJECT_STATUS_NOT_EARNED,
                "That status is earned, not set", e.getMessage());
    }

    /**
     * 422 — no prerequisite master, so the journeys could never start.
     *
     * <p>The same {@code type} the clients package publishes for the identical
     * refusal, deliberately reused rather than given a twin: CONVENTIONS.md §3
     * says clients branch on {@code type}, and "publish a prerequisite master
     * first" is one instruction whichever screen provoked it.
     */
    @ExceptionHandler(NoPublishedPrerequisitesException.class)
    ResponseEntity<ProblemDetail> handleNoPrereqMaster(NoPublishedPrerequisitesException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, NO_PREREQ_MASTER,
                "No prerequisites are published", e.getMessage());
    }

    /**
     * 409 — a checked Module Service that is no longer an active service of the
     * product.
     *
     * <p>409 rather than 400 because the request was valid when the form was
     * drawn: the catalogue moved underneath it. The remedy is to reload, which
     * is what a conflict means, and {@code templateId} rides along so the form
     * can say which row to look at.
     */
    @ExceptionHandler(UnknownModuleServiceException.class)
    ResponseEntity<ProblemDetail> handleUnknownService(UnknownModuleServiceException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(UNKNOWN_MODULE_SERVICE);
        problem.setTitle("That module service is no longer available");
        problem.setDetail(e.getMessage());
        problem.setProperty("templateId", e.getTemplateId());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, URI type,
                                                         String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(type);
        problem.setTitle(title);
        problem.setDetail(detail);
        return ResponseEntity.status(status).body(problem);
    }
}
