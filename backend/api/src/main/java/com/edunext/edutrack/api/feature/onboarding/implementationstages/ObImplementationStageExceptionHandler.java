package com.edunext.edutrack.api.feature.onboarding.implementationstages;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * Turns the master's one refusal into a problem response.
 *
 * <p>Scoped to this controller rather than added to a global advice, for
 * {@code ObProductExceptionHandler}'s reason: a duplicate stage name means
 * nothing anywhere else, and a global handler for a feature-local type is how
 * an advice class becomes a junk drawer every stream edits.
 */
@RestControllerAdvice(assignableTypes = ObImplementationStageController.class)
class ObImplementationStageExceptionHandler {

    private static final URI DUPLICATE =
            URI.create("https://edutrack/errors/ob-implementation-stage-name-duplicate");

    /**
     * 409, keyed on the field. {@code uq_ob_implementation_stages_name} would
     * refuse this too — the column's collation is case-insensitive — but with a
     * message naming a MySQL constraint, which is what a caller cannot act on.
     */
    @ExceptionHandler(DuplicateImplementationStageNameException.class)
    ProblemDetail duplicate(DuplicateImplementationStageNameException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(DUPLICATE);
        problem.setTitle("Implementation stage already exists");
        problem.setDetail(e.getMessage());
        problem.setProperty("field", "name");
        problem.setProperty("name", e.name());
        return problem;
    }
}
