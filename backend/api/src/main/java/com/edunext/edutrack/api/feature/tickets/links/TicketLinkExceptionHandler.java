package com.edunext.edutrack.api.feature.tickets.links;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * C-064 · RFC 9457 problem documents for {@code /tickets/{ticketId}/links}
 * ({@code CONVENTIONS.md} §3).
 *
 * <p><b>Scoped by {@code assignableTypes}</b>, for the reason
 * {@code TicketExceptionHandler} and {@code AttachmentExceptionHandler} both
 * give: a repository-wide {@code @RestControllerAdvice} is shared surface
 * four streams would edit daily.
 *
 * <p>{@code TicketNotFoundException} and {@code TicketLinkNotFoundException}
 * are deliberately absent — both extend {@code ErrorResponseException} and
 * Spring renders them as A-035's 404 unaided, on
 * {@code AttachmentExceptionHandler}'s own precedent for
 * {@code AttachmentNotFoundException}.
 */
@RestControllerAdvice(assignableTypes = TicketLinkController.class)
class TicketLinkExceptionHandler {

    private static final URI VALIDATION = URI.create("https://edutrack/errors/validation");
    private static final URI CONFLICT = URI.create("https://edutrack/errors/ticket-link-conflict");

    @ExceptionHandler(UnknownLinkTypeException.class)
    ResponseEntity<ProblemDetail> handleUnknownType(UnknownLinkTypeException e) {
        return validationFailure("linkType", e.getMessage());
    }

    @ExceptionHandler(NotSubmittableLinkTypeException.class)
    ResponseEntity<ProblemDetail> handleNotSubmittable(NotSubmittableLinkTypeException e) {
        return validationFailure("linkType", e.getMessage());
    }

    @ExceptionHandler(SelfTicketLinkException.class)
    ResponseEntity<ProblemDetail> handleSelfLink(SelfTicketLinkException e) {
        return validationFailure("targetTicketId", e.getMessage());
    }

    /**
     * 409, matching {@code uq_ticket_links}. Not keyed onto a field —
     * unlike the 400s above, resending with the same body will not help;
     * the caller has to change what they are asking for.
     */
    @ExceptionHandler(DuplicateTicketLinkException.class)
    ResponseEntity<ProblemDetail> handleDuplicate(DuplicateTicketLinkException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(CONFLICT);
        problem.setTitle("Already linked");
        problem.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    private static ResponseEntity<ProblemDetail> validationFailure(String field, String message) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(VALIDATION);
        problem.setTitle("Validation failed");
        problem.setDetail(message);
        problem.setProperty("errors", java.util.Map.of(field, new String[]{message}));
        return ResponseEntity.badRequest().body(problem);
    }
}
