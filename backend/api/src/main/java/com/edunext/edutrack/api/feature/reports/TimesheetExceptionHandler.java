package com.edunext.edutrack.api.feature.reports;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * B-065 · RFC 9457 problem documents for {@link TimesheetController}
 * ({@code CONVENTIONS.md} §3).
 *
 * <p>Scoped to this controller, on {@code TemplateExceptionHandler}'s own
 * precedent: a repository-wide {@code @RestControllerAdvice} is shared
 * surface every stream would edit, and no stream should introduce one
 * unilaterally.
 */
@RestControllerAdvice(assignableTypes = TimesheetController.class)
class TimesheetExceptionHandler {

    private static final URI ALREADY_APPROVED = URI.create("https://edutrack/errors/already-approved");

    @ExceptionHandler(TimesheetApprovalService.AlreadyApprovedException.class)
    ResponseEntity<ProblemDetail> handleAlreadyApproved(TimesheetApprovalService.AlreadyApprovedException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(ALREADY_APPROVED);
        problem.setTitle("This week has already been reviewed");
        problem.setDetail(e.getMessage());
        problem.setProperty("approvedBy", e.existing().approvedBy());
        problem.setProperty("approvedAt", e.existing().approvedAt());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }
}
