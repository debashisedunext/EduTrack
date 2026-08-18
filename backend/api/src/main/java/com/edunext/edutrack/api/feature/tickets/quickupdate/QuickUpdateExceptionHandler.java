package com.edunext.edutrack.api.feature.tickets.quickupdate;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.Map;

/**
 * C-036 · RFC 9457 problem documents for the quick-update route ({@code
 * CONVENTIONS.md} §3).
 *
 * <p>Scoped by {@code assignableTypes}, following {@code
 * EffortLogExceptionHandler} and every ticket sub-feature's own advice: a
 * repository-wide {@code @RestControllerAdvice} is shared surface four streams
 * would edit daily.
 *
 * <p>{@code AttachmentNotOnTicketException} is deliberately absent — it extends
 * {@code ErrorResponseException} and Spring renders it as 404 unaided, exactly
 * as {@code EffortLogNotFoundException} does.
 */
@RestControllerAdvice(assignableTypes = QuickUpdateController.class)
class QuickUpdateExceptionHandler {

    private static final URI VALIDATION = URI.create("https://edutrack/errors/validation");

    /**
     * 400, keyed onto {@code revisedEtaReason} so the panel marks the reason
     * field rather than showing a banner over an otherwise valid form.
     */
    @ExceptionHandler(RevisedEtaReasonRequiredException.class)
    ResponseEntity<ProblemDetail> handleReasonRequired(RevisedEtaReasonRequiredException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(VALIDATION);
        problem.setTitle("Validation failed");
        problem.setDetail(e.getMessage());
        problem.setProperty("errors", Map.of("revisedEtaReason", new String[]{e.getMessage()}));
        return ResponseEntity.badRequest().body(problem);
    }
}
