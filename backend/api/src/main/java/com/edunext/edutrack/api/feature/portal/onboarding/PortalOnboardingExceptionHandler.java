package com.edunext.edutrack.api.feature.portal.onboarding;

import com.edunext.edutrack.api.feature.onboarding.attachments.ObAttachmentTooLargeException;
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
