package com.edunext.edutrack.api.feature.tickets.attachments;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.net.URI;

/**
 * C-025 · RFC 9457 problem documents for the attachment routes
 * ({@code CONVENTIONS.md} §3), matching the contract's 413 and 415.
 *
 * <p><b>Scoped by {@code assignableTypes}</b>, for the reason
 * {@code TicketExceptionHandler}, {@code AuthExceptionHandler} and
 * {@code CalendarExceptionHandler} all give: a repository-wide
 * {@code @RestControllerAdvice} is shared surface four streams would edit daily,
 * and no stream should introduce one unilaterally.
 *
 * <p>{@code TicketNotFoundException} is deliberately absent. It extends
 * {@code ErrorResponseException} and Spring renders it as A-035's 404 without
 * help — and writing a handler for it here would create a second place where the
 * out-of-scope response is decided, which is exactly the drift that class's
 * javadoc exists to prevent.
 */
@RestControllerAdvice(assignableTypes = AttachmentController.class)
class AttachmentExceptionHandler {

    private static final URI UNSUPPORTED_TYPE = URI.create("https://edutrack/errors/unsupported-attachment-type");
    private static final URI TOO_LARGE = URI.create("https://edutrack/errors/attachment-too-large");

    /**
     * 415, per the contract: "Extension or sniffed MIME type not allowed."
     *
     * <p>The {@code detail} is the exception's message, which is written for the
     * person who chose the file — see {@link UnsupportedAttachmentTypeException}
     * on what it does and does not say.
     */
    @ExceptionHandler(UnsupportedAttachmentTypeException.class)
    ResponseEntity<ProblemDetail> handleUnsupportedType(UnsupportedAttachmentTypeException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        problem.setType(UNSUPPORTED_TYPE);
        problem.setTitle("File type not allowed");
        problem.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(problem);
    }

    /** 413, per the contract: "File or ticket limit exceeded." */
    @ExceptionHandler(AttachmentLimitExceededException.class)
    ResponseEntity<ProblemDetail> handleLimit(AttachmentLimitExceededException e) {
        return payloadTooLarge(e.getMessage());
    }

    /**
     * The same 413 for the cap the servlet container enforces before we ever see
     * the body.
     *
     * <p>{@code spring.servlet.multipart.max-file-size} refuses an oversized
     * upload during parsing, so {@link AttachmentService} never runs and the
     * default rendering is a bare 500 with Spring's own message. Two different
     * responses for one rule — 413 just under the cap and 500 just over it —
     * would send whoever debugs it looking for a server fault instead of a large
     * file, which is why this handler exists at all.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ProblemDetail> handleContainerLimit(MaxUploadSizeExceededException e) {
        return payloadTooLarge("That file is larger than this server accepts for one attachment.");
    }

    private ResponseEntity<ProblemDetail> payloadTooLarge(String detail) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.PAYLOAD_TOO_LARGE);
        problem.setType(TOO_LARGE);
        problem.setTitle("Attachment too large");
        problem.setDetail(detail);
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(problem);
    }
}
