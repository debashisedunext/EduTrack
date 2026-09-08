package com.edunext.edutrack.api.feature.onboarding.moduleaccess;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

import static com.edunext.edutrack.api.feature.onboarding.moduleaccess.ObModuleAccessExceptions.AlreadyRevokedException;
import static com.edunext.edutrack.api.feature.onboarding.moduleaccess.ObModuleAccessExceptions.DuplicateGrantException;
import static com.edunext.edutrack.api.feature.onboarding.moduleaccess.ObModuleAccessExceptions.GrantNotFoundException;
import static com.edunext.edutrack.api.feature.onboarding.moduleaccess.ObModuleAccessExceptions.GrantValidationException;
import static com.edunext.edutrack.api.feature.onboarding.moduleaccess.ObModuleAccessExceptions.LastAdminGrantException;
import static com.edunext.edutrack.api.feature.onboarding.moduleaccess.ObModuleAccessExceptions.NotAnOnboardingAdminException;

/**
 * A-117 · RFC 9457 problem documents for {@link ObModuleAccessController}
 * (CONVENTIONS.md §3).
 *
 * <p>Scoped by {@code assignableTypes}, on the precedent every handler in this
 * repository follows: a repository-wide {@code @RestControllerAdvice} is shared
 * surface four streams would edit, and no stream introduces one unilaterally.
 *
 * <p>The {@code type} URIs are the API. CONVENTIONS.md §3 is explicit that
 * clients branch on {@code type} and never on prose, and OB-08 has real
 * branching to do: the two 422s below want different screens — one is "you
 * clicked twice", the other is "grant somebody else first" — and a client that
 * could only read the status could not tell them apart.
 */
@RestControllerAdvice(assignableTypes = ObModuleAccessController.class)
class ObModuleAccessExceptionHandler {

    private static final URI NOT_FOUND = URI.create("https://edutrack/errors/not-found");
    private static final URI FORBIDDEN = URI.create("https://edutrack/errors/forbidden");
    private static final URI VALIDATION_FAILED = URI.create("https://edutrack/errors/validation-failed");
    private static final URI DUPLICATE_GRANT =
            URI.create("https://edutrack/errors/ob-module-access-duplicate");
    private static final URI ALREADY_REVOKED =
            URI.create("https://edutrack/errors/ob-module-access-already-revoked");
    private static final URI LAST_ADMIN =
            URI.create("https://edutrack/errors/ob-module-access-last-admin");

    /**
     * 403 — not an OB Admin.
     *
     * <p>The one place in the onboarding module where a role refusal is a 403
     * rather than a 404. See {@link ObModuleAccessExceptions.NotAnOnboardingAdminException}
     * for why that does not leak anything: there is no row here whose existence
     * a 404 would be protecting.
     */
    @ExceptionHandler(NotAnOnboardingAdminException.class)
    ResponseEntity<ProblemDetail> handleNotAdmin(NotAnOnboardingAdminException e) {
        return problem(HttpStatus.FORBIDDEN, FORBIDDEN, "Onboarding administrators only", e);
    }

    /** 404 — no grant with that id. An Admin sees every grant, so this is never a scope refusal. */
    @ExceptionHandler(GrantNotFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(GrantNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, NOT_FOUND, "Not found", e);
    }

    /** 400 — an unknown user, module or role in the body. */
    @ExceptionHandler(GrantValidationException.class)
    ResponseEntity<ProblemDetail> handleValidation(GrantValidationException e) {
        return problem(HttpStatus.BAD_REQUEST, VALIDATION_FAILED, "The grant was not saved", e);
    }

    /** 409 — a live grant for this (user, module) already exists. */
    @ExceptionHandler(DuplicateGrantException.class)
    ResponseEntity<ProblemDetail> handleDuplicate(DuplicateGrantException e) {
        return problem(HttpStatus.CONFLICT, DUPLICATE_GRANT, "Access already granted", e);
    }

    /** 422 — the grant is already revoked. */
    @ExceptionHandler(AlreadyRevokedException.class)
    ResponseEntity<ProblemDetail> handleAlreadyRevoked(AlreadyRevokedException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, ALREADY_REVOKED, "Already revoked", e);
    }

    /** 422 — the last live {@code OB_ADMIN} grant in the organisation. */
    @ExceptionHandler(LastAdminGrantException.class)
    ResponseEntity<ProblemDetail> handleLastAdmin(LastAdminGrantException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, LAST_ADMIN, "The last administrator", e);
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, URI type,
                                                         String title, RuntimeException e) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(type);
        problem.setTitle(title);
        problem.setDetail(e.getMessage());
        return ResponseEntity.status(status).body(problem);
    }
}
