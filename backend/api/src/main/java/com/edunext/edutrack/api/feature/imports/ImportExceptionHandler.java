package com.edunext.edutrack.api.feature.imports;

import org.springframework.http.HttpHeaders;
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
     * B-032's three refusals, each with a {@code type} of its own.
     *
     * <p>Distinct URIs rather than one {@code import-failed} because the frontend
     * switches on {@code type} — CONVENTIONS.md §3 calls it "a stable URI the
     * frontend may switch on", against a {@code detail} that "is for humans and
     * may change". The step-2 screen behaves differently for each: over a limit
     * offers splitting the file, the wrong type offers Save As, and unreadable
     * offers picking a different file. One type would make those three
     * indistinguishable without parsing English.
     */
    private static final URI TOO_LARGE = URI.create("https://edutrack/errors/import-too-large");
    private static final URI UNSUPPORTED = URI.create("https://edutrack/errors/import-unsupported-file");
    private static final URI UNREADABLE = URI.create("https://edutrack/errors/import-unreadable-file");
    private static final URI STAGING_FULL = URI.create("https://edutrack/errors/import-staging-full");

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

    /**
     * B-032 · 413, for a file over any of §4B.3's step-2 ceilings.
     *
     * <p>The ceiling and the value that broke it are properties on the body, not
     * only prose in {@code detail}. The screen needs to say "5,000 rows" in its
     * own words next to a Split the file suggestion, and re-deriving a number the
     * server already knows by parsing a sentence is how a message and a limit
     * drift apart.
     */
    @ExceptionHandler(ImportLimitExceededException.class)
    ResponseEntity<ProblemDetail> handleTooLarge(ImportLimitExceededException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.PAYLOAD_TOO_LARGE);
        problem.setType(TOO_LARGE);
        problem.setTitle("Import file is too large");
        problem.setDetail(e.getMessage());
        problem.setProperty("limit", e.limit());
        problem.setProperty("ceiling", e.ceiling());
        problem.setProperty("actual", e.actual());

        return problem(HttpStatus.PAYLOAD_TOO_LARGE, problem);
    }

    /**
     * B-032 · 415, for a type with no reader — {@code .xls} above all.
     *
     * <p>415 and not 422: the request is well-formed and the content is
     * presumably fine, we simply do not read this kind of file. The distinction
     * is what lets the screen offer "open it and Save As .xlsx" for this and
     * "check the file, it may be corrupt" for the other.
     */
    @ExceptionHandler(UnsupportedImportFileException.class)
    ResponseEntity<ProblemDetail> handleUnsupported(UnsupportedImportFileException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        problem.setType(UNSUPPORTED);
        problem.setTitle("Unsupported file type");
        problem.setDetail(e.getMessage());
        problem.setProperty("extension", e.extension());
        problem.setProperty("accepted", java.util.List.of("xlsx", "csv"));

        return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, problem);
    }

    /**
     * B-032 · 422, for the right extension over content that will not parse.
     *
     * <p>{@code sheets} is present only for the unknown-sheet case, where the
     * caller asked for a sheet the workbook does not contain — usually because
     * they changed sheets and then changed file. Listing what the file does have
     * turns a dead end into a correction.
     */
    @ExceptionHandler(UnreadableImportFileException.class)
    ResponseEntity<ProblemDetail> handleUnreadable(UnreadableImportFileException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(UNREADABLE);
        problem.setTitle("File could not be read");
        problem.setDetail(e.getMessage());
        if (!e.sheets().isEmpty()) {
            problem.setProperty("sheets", e.sheets());
        }

        return problem(HttpStatus.UNPROCESSABLE_ENTITY, problem);
    }

    /**
     * B-032 · 503, when every staging slot is taken.
     *
     * <p>Temporary, expected, and nothing the caller did wrong — so it is a 503
     * with {@code Retry-After} rather than the 500 this became before B-032 gave
     * it a type. The message the store wrote is already addressed to a person;
     * what was missing was a status that says "come back", and a header that
     * says when.
     *
     * <p>Thirty seconds because the ceiling clears as other imports finish or
     * expire, not on a schedule. It is long enough not to have every refused
     * client retry in lockstep and short enough that an admin waiting on it is
     * not left guessing.
     */
    @ExceptionHandler(ImportStagingFullException.class)
    ResponseEntity<ProblemDetail> handleStagingFull(ImportStagingFullException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        problem.setType(STAGING_FULL);
        problem.setTitle("Too many imports in progress");
        problem.setDetail(e.getMessage());
        problem.setProperty("ceiling", e.ceiling());

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "30")
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    /** The content type is stated on every one of these, for the reason given above. */
    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, ProblemDetail body) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }
}
