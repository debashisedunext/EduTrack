package com.edunext.edutrack.api.feature.onboarding.signoff;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.Map;

/**
 * RFC 9457 problem documents for {@link ObSignoffAdminController}.
 *
 * <p>Scoped by {@code assignableTypes}, on {@code ObSignoffCertificateExceptionHandler}'s
 * own precedent one class over: a repository-wide {@code @RestControllerAdvice}
 * is shared surface four streams would edit, and no stream introduces one
 * unilaterally.
 *
 * <p>The {@code type} URIs are the codes the contract names, because
 * CONVENTIONS.md §3 says clients branch on {@code type} rather than on prose.
 * {@code ob-signoff-already-pending}, {@code ob-signoff-journey-incomplete} and
 * {@code ob-signoff-step-not-signoffable} are spelled in the contract's own
 * words and are published exactly as written there — a code that differs from
 * its documentation by a hyphen is a code no caller can match on.
 */
@RestControllerAdvice(assignableTypes = ObSignoffAdminController.class)
class ObSignoffAdminExceptionHandler {

    private static final URI NOT_FOUND = URI.create("https://edutrack/errors/not-found");
    private static final URI VALIDATION_FAILED = URI.create("https://edutrack/errors/validation-failed");
    private static final URI ALREADY_PENDING = URI.create("https://edutrack/errors/ob-signoff-already-pending");
    private static final URI JOURNEY_INCOMPLETE = URI.create("https://edutrack/errors/ob-signoff-journey-incomplete");
    private static final URI NOT_SIGNOFFABLE = URI.create("https://edutrack/errors/ob-signoff-step-not-signoffable");
    private static final URI NOT_OPEN = URI.create("https://edutrack/errors/ob-signoff-not-open");
    private static final URI CONTACT_INVALID = URI.create("https://edutrack/errors/ob-signoff-contact-invalid");

    /** 404 — see the exception for the cases this cannot and must not distinguish. */
    @ExceptionHandler(ObSignoffNotFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(ObSignoffNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, NOT_FOUND, "Not found", e.getMessage());
    }

    /**
     * 400 — {@code kind} and {@code stepId} disagree.
     *
     * <p>{@code errors} is field-keyed because the contract's 400 body is
     * {@code ValidationProblem}, and this refusal is about one named field even
     * though it took two to detect. The screen can put the message on the
     * control rather than at the top of the form.
     */
    @ExceptionHandler(ObSignoffKindMismatchException.class)
    ResponseEntity<ProblemDetail> handleKindMismatch(ObSignoffKindMismatchException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(VALIDATION_FAILED);
        problem.setTitle("That request does not make sense");
        problem.setDetail(e.getMessage());
        problem.setProperty("errors", Map.of(e.field(), e.getMessage()));
        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * 409 — one decision, one live link. The existing sign-off's id travels so
     * the panel can offer resend on the row that is actually blocking rather
     * than making the operator go and find it.
     */
    @ExceptionHandler(ObSignoffAlreadyPendingException.class)
    ResponseEntity<ProblemDetail> handleAlreadyPending(ObSignoffAlreadyPendingException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(ALREADY_PENDING);
        problem.setTitle("This is already waiting on the client");
        problem.setDetail(e.getMessage());
        problem.setProperty("signoffId", e.existingId());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /** 422 {@code ob-signoff-journey-incomplete} — a premature go-live request. */
    @ExceptionHandler(ObSignoffJourneyIncompleteException.class)
    ResponseEntity<ProblemDetail> handleJourneyIncomplete(ObSignoffJourneyIncompleteException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, JOURNEY_INCOMPLETE,
                "This journey is not finished yet", e.getMessage());
    }

    /** 422 {@code ob-signoff-step-not-signoffable} — the step's template never asked for one. */
    @ExceptionHandler(ObSignoffNotSignoffableException.class)
    ResponseEntity<ProblemDetail> handleNotSignoffable(ObSignoffNotSignoffableException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, NOT_SIGNOFFABLE,
                "This service does not need a client sign-off", e.getMessage());
    }

    /** 422 — resend or cancel on a sign-off whose decision is already made. */
    @ExceptionHandler(ObSignoffSettledException.class)
    ResponseEntity<ProblemDetail> handleSettled(ObSignoffSettledException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, NOT_OPEN,
                "This sign-off is already settled", e.getMessage());
    }

    /** 422 — the named contact is not an active SPOC on this client. */
    @ExceptionHandler(ObSignoffContactInvalidException.class)
    ResponseEntity<ProblemDetail> handleContact(ObSignoffContactInvalidException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, CONTACT_INVALID,
                "That contact cannot be sent this sign-off", e.getMessage());
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
