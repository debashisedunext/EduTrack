package com.edunext.edutrack.api.feature.portal.onboarding;

import com.edunext.edutrack.api.feature.onboarding.attachments.ObAttachmentTooLargeException;
import com.edunext.edutrack.api.feature.onboarding.signoff.ObSignoffCsatUnavailableException;
import com.edunext.edutrack.api.feature.onboarding.signoff.ObSignoffNotForClientException;
import com.edunext.edutrack.api.feature.onboarding.signoff.ObSignoffNotPendingException;
import com.edunext.edutrack.api.upload.UnsupportedUploadTypeException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.net.URI;

/**
 * C-121 · RFC 9457 problem documents for {@link PortalOnboardingController}
 * ({@code contracts/CONVENTIONS.md} §3), scoped by {@code assignableTypes} on
 * the same precedent every handler in this codebase follows.
 */
@RestControllerAdvice(assignableTypes = PortalOnboardingController.class)
class PortalOnboardingExceptionHandler {

    private static final URI NOT_FOUND = URI.create("https://edutrack/errors/not-found");
    private static final URI NO_PRIMARY_CONTACT =
            URI.create("https://edutrack/errors/portal-no-primary-contact");
    private static final URI STEP_NOT_RUNNING =
            URI.create("https://edutrack/errors/portal-step-not-running");
    private static final URI SIGNOFF_NOT_PENDING =
            URI.create("https://edutrack/errors/portal-signoff-not-pending");
    private static final URI CSAT_UNAVAILABLE =
            URI.create("https://edutrack/errors/portal-csat-unavailable");

    @ExceptionHandler(PortalOnboardingNotFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(PortalOnboardingNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(NOT_FOUND);
        problem.setTitle("Not found");
        problem.setDetail("No resource was found at this path.");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @ExceptionHandler(PortalNoPrimaryContactException.class)
    ResponseEntity<ProblemDetail> handleNoPrimaryContact(PortalNoPrimaryContactException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(NO_PRIMARY_CONTACT);
        problem.setTitle("No active primary contact");
        problem.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(problem);
    }

    /**
     * 404 — a sign-off that does not exist, and the same 404 for one that
     * exists on another client's onboarding. The no-existence-leak rule, which
     * is why this shares {@code PortalOnboardingNotFoundException}'s body
     * verbatim rather than saying anything about sign-offs specifically.
     */
    @ExceptionHandler(ObSignoffNotForClientException.class)
    ResponseEntity<ProblemDetail> handleSignoffNotFound(ObSignoffNotForClientException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(NOT_FOUND);
        problem.setTitle("Not found");
        problem.setDetail("No resource was found at this path.");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    /**
     * 422 — the sign-off is this client's, and it is already decided or
     * withdrawn.
     *
     * <p>The row's status is carried as a property so the screen can say which
     * way it went without parsing the detail string. Not a disclosure: it is
     * this client's own row and {@code listPortalSignoffs} already serves the
     * field.
     *
     * <p><b>The property is {@code signoffStatus}, not {@code status}.</b> RFC
     * 9457 defines {@code status} as the HTTP status code and {@link
     * ProblemDetail} serializes it from the response itself; setting a property
     * of that name does not override it, it emits the member twice — one object
     * with two {@code status} keys, where which one a parser keeps is its own
     * business. The first version of this handler did exactly that.
     */
    @ExceptionHandler(ObSignoffNotPendingException.class)
    ResponseEntity<ProblemDetail> handleSignoffNotPending(ObSignoffNotPendingException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(SIGNOFF_NOT_PENDING);
        problem.setTitle("This sign-off is no longer open");
        problem.setDetail(e.getMessage());
        problem.setProperty("signoffStatus", e.status().name());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(problem);
    }

    /** 422 — the go-live survey was answered on a sign-off that is not offering one. */
    @ExceptionHandler(ObSignoffCsatUnavailableException.class)
    ResponseEntity<ProblemDetail> handleCsatUnavailable(ObSignoffCsatUnavailableException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(CSAT_UNAVAILABLE);
        problem.setTitle("This survey is not available");
        problem.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(problem);
    }

    /** 422 — escalating a step that is not currently running. */
    @ExceptionHandler(PortalStepNotRunningException.class)
    ResponseEntity<ProblemDetail> handleStepNotRunning(PortalStepNotRunningException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(STEP_NOT_RUNNING);
        problem.setTitle("This service is not currently running");
        problem.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(problem);
    }

    @ExceptionHandler(ObAttachmentTooLargeException.class)
    ResponseEntity<ProblemDetail> handleTooLarge(ObAttachmentTooLargeException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.PAYLOAD_TOO_LARGE);
        problem.setTitle("File too large");
        problem.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(problem);
    }

    @ExceptionHandler(UnsupportedUploadTypeException.class)
    ResponseEntity<ProblemDetail> handleUnsupportedType(UnsupportedUploadTypeException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        problem.setTitle("Unsupported file type");
        problem.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(problem);
    }

    /** The container-level cap, hit before this package ever sees the body — {@code ObClientExceptionHandler}'s own note. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ProblemDetail> handleContainerLimit(MaxUploadSizeExceededException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.PAYLOAD_TOO_LARGE);
        problem.setTitle("File too large");
        problem.setDetail("That file is larger than this server accepts for one document.");
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(problem);
    }
}
