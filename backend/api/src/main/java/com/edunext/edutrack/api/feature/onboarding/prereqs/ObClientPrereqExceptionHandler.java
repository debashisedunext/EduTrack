package com.edunext.edutrack.api.feature.onboarding.prereqs;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * B-125 · RFC 9457 problem documents for the two instance controllers
 * ({@code CONVENTIONS.md} §3), scoped by {@code assignableTypes} on
 * {@code ObPrereqTemplateExceptionHandler}'s own precedent.
 *
 * <p>Separate from that handler although both live in this package: an
 * advice names the controllers it covers, and mixing the master's four
 * conflict types with the instance's four transition types in one class
 * would make each harder to read than either is apart.
 */
@RestControllerAdvice(assignableTypes = {
        ObClientPrereqController.class,
        ObPrereqTaskController.class
})
class ObClientPrereqExceptionHandler {

    private static final URI CONFLICT = URI.create("https://edutrack/errors/conflict");
    private static final String PROBLEM_BASE = "https://edutrack/errors/";

    /**
     * No such task, or one on a client this caller cannot see.
     *
     * <p><b>The two are the same answer on purpose.</b> Blueprint §2's rule —
     * out-of-scope ids return 404, not 403 — so no request can distinguish a
     * task that does not exist from one that is not theirs.
     */
    @ExceptionHandler({
            PrereqTaskNotFoundException.class,
            ClientPrereqsNotFoundException.class
    })
    ResponseEntity<ProblemDetail> handleNotFound(RuntimeException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Not found");
        problem.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    /** The row exists, but not in a state this call accepts. */
    @ExceptionHandler({
            PrereqsAlreadyInstantiatedException.class,
            NoActivePrereqMasterException.class
    })
    ResponseEntity<ProblemDetail> handleConflict(RuntimeException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(CONFLICT);
        problem.setTitle("Conflict");
        problem.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /**
     * {@code 422} — the workflow forbids this move.
     *
     * <p><b>The body names the status the task is actually in</b>, which the
     * contract asks for specifically: "not submittable" without saying what
     * it is instead sends the caller to re-read the task to find out, which
     * is the round trip the status code was supposed to save. The
     * {@code type} carries the contract's own slug so a client can branch on
     * it without parsing prose.
     */
    @ExceptionHandler(PrereqTransitionException.class)
    ResponseEntity<ProblemDetail> handleTransition(PrereqTransitionException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(URI.create(PROBLEM_BASE + e.problemCode()));
        problem.setTitle("Transition not allowed");
        problem.setDetail(e.getMessage());
        problem.setProperty("status", e.status().name());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(problem);
    }

    /**
     * {@code 422} and its own slug — {@code ob-prereq-mandatory-not-skippable}.
     *
     * <p>Not a 403, and the distinction is the point: 403 would say "not you",
     * inviting the caller to find somebody with a bigger role. Plan §5.3
     * leaves no such person. A mandatory task is unskippable by anybody, so
     * the refusal is about the task rather than the caller.
     */
    @ExceptionHandler(MandatoryTaskNotSkippableException.class)
    ResponseEntity<ProblemDetail> handleMandatorySkip(MandatoryTaskNotSkippableException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(URI.create(PROBLEM_BASE + "ob-prereq-mandatory-not-skippable"));
        problem.setTitle("A mandatory prerequisite cannot be skipped");
        problem.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(problem);
    }

    /** {@code 422} — a settled task is not reworded. */
    @ExceptionHandler(PrereqTaskSettledException.class)
    ResponseEntity<ProblemDetail> handleSettled(PrereqTaskSettledException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(URI.create(PROBLEM_BASE + "ob-prereq-settled"));
        problem.setTitle("Task is settled");
        problem.setDetail(e.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(problem);
    }
}
