package com.edunext.edutrack.api.feature.imports;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * B-031 · RFC 9457 problem documents for the import wizard
 * ({@code CONVENTIONS.md} §3).
 *
 * <p><b>Scoped to {@link ImportController}</b>, for the reason
 * {@code ClientExceptionHandler} and {@code CalendarExceptionHandler} both give:
 * a repository-wide {@code @RestControllerAdvice} is shared surface four streams
 * would edit daily, and no stream should introduce one unilaterally.
 *
 * <p>This is the handler B-030's README said would "arrive with the first
 * endpoint". It could not be written then:
 * {@code @RestControllerAdvice(assignableTypes = …)} needs a controller class to
 * name, and advice on a controller that does not exist is dead code until it
 * silently is not.
 */
@RestControllerAdvice(assignableTypes = ImportController.class)
class ImportExceptionHandler {

    /** The same {@code type} every other 404 in the product uses. */
    private static final URI NOT_FOUND = URI.create("https://edutrack/errors/not-found");

    /**
     * 404, because {@code schema} is a path segment.
     *
     * <p>An unregistered key does not make the request malformed — it makes the
     * resource absent, which is what {@link UnknownImportSchemaException}'s own
     * javadoc has said since B-030. {@code /imports/users/template} is the live
     * case: the contract declares {@code users} and B-038 has not registered it,
     * so the honest answer today is "there is no such template", not "your
     * request was wrong".
     *
     * <p><b>The registered keys go in the body.</b> Nothing here is a secret —
     * they are in the contract, in the generated client and in the URL enum the
     * caller built its request from — and a 404 that names the alternatives is
     * the difference between a typo fixed in ten seconds and a bug report.
     */
    @ExceptionHandler(UnknownImportSchemaException.class)
    ResponseEntity<ProblemDetail> handleUnknownSchema(UnknownImportSchemaException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(NOT_FOUND);
        problem.setTitle("Unknown import schema");
        problem.setDetail(e.getMessage());
        problem.setProperty("schema", e.key());

        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                // Stated rather than negotiated. The handler this advises
                // declares `produces` an .xlsx media type, and a problem
                // document answered without an explicit type is the one place
                // that mapping could turn a 404 into a 406.
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
