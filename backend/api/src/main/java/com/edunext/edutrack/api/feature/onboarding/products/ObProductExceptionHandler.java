package com.edunext.edutrack.api.feature.onboarding.products;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * A-124 · turns the catalogue's two refusals into problem responses.
 *
 * <p>Scoped to this controller rather than added to a global advice: both
 * exceptions are about product codes and mean nothing anywhere else, and a
 * global handler for a feature-local type is how an advice class becomes a
 * junk drawer every stream edits.
 */
@RestControllerAdvice(assignableTypes = ObProductController.class)
class ObProductExceptionHandler {

    private static final URI DUPLICATE = URI.create("https://edutrack/errors/ob-product-code-duplicate");
    private static final URI IMMUTABLE = URI.create("https://edutrack/errors/ob-product-code-immutable");

    /**
     * 409, keyed on the field. The index would refuse this too — the column's
     * collation is case-insensitive — but with a message naming a MySQL
     * constraint, which is what a caller cannot act on.
     */
    @ExceptionHandler(DuplicateProductCodeException.class)
    ProblemDetail duplicate(DuplicateProductCodeException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(DUPLICATE);
        problem.setTitle("Product code already used");
        problem.setDetail(e.getMessage());
        problem.setProperty("field", "code");
        problem.setProperty("code", e.code());
        return problem;
    }

    /** 422: the request is well-formed and asks for something the catalogue does not permit. */
    @ExceptionHandler(ProductCodeImmutableException.class)
    ProblemDetail immutable(ProductCodeImmutableException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(IMMUTABLE);
        problem.setTitle("A product code cannot change");
        problem.setDetail(e.getMessage());
        problem.setProperty("field", "code");
        return problem;
    }
}
