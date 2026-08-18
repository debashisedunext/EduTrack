package com.edunext.edutrack.api.feature.tickets.effort;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.Map;

/**
 * C-035 · RFC 9457 problem documents for the effort-log routes
 * ({@code CONVENTIONS.md} §3).
 *
 * <p><b>Scoped by {@code assignableTypes}</b>, following {@code CommentExceptionHandler}
 * and {@code TicketExceptionHandler}: a repository-wide {@code @RestControllerAdvice}
 * is shared surface four streams would edit daily.
 *
 * <p>{@code EffortLogNotFoundException} is deliberately absent here — it extends
 * {@code ErrorResponseException} and Spring renders it as 404 unaided, exactly
 * as {@code TicketNotFoundException} and {@code CommentNotFoundException} do.
 */
@RestControllerAdvice(assignableTypes = EffortLogController.class)
class EffortLogExceptionHandler {

    private static final URI VALIDATION = URI.create("https://edutrack/errors/validation");

    /**
     * 400, keyed onto {@code correctsEntryId} so the correction dialog marks the
     * right control rather than showing a banner over an otherwise valid form.
     */
    @ExceptionHandler(EffortCorrectionTargetRequiredException.class)
    ResponseEntity<ProblemDetail> handleTargetRequired(EffortCorrectionTargetRequiredException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(VALIDATION);
        problem.setTitle("Validation failed");
        problem.setDetail(e.getMessage());
        problem.setProperty("errors", Map.of("correctsEntryId", new String[]{e.getMessage()}));
        return ResponseEntity.badRequest().body(problem);
    }
}
