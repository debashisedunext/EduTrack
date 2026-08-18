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
 *
 * <p><b>B-035 added the second controller to the list rather than a second
 * advice.</b> {@link ImportBatchController} serves a different path root and the
 * same feature; a 404 for an absent batch and a 404 for an absent preset are one
 * condition to a caller, and two advices would be two places for the package's
 * problem types to be spelled.
 */
@RestControllerAdvice(assignableTypes = {ImportController.class, ImportBatchController.class})
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
     * B-033's two, and the second is deliberately <em>not</em> new.
     *
     * <p>{@code import-unknown-field} is its own type because step 3 does
     * something specific with it — it names the entries it would have to drop
     * from a preset. {@code validation-failed} is the type every other form in
     * the product answers with, and an empty mapping is that failure reached by a
     * path {@code @NotEmpty} cannot see, not a new kind of refusal.
     */
    private static final URI UNKNOWN_FIELD = URI.create("https://edutrack/errors/import-unknown-field");
    private static final URI VALIDATION_FAILED = URI.create("https://edutrack/errors/validation-failed");

    /**
     * B-034's three, and the split is by <b>remedy</b> rather than by cause.
     *
     * <p>All three are 422 and all three mean "the request refers to something
     * that is not there", so a single type would be defensible on the status
     * alone. It would also make step 4 parse English to decide between the only
     * three sentences it can usefully say: upload the file again, map the
     * missing column, or fix the mapping that names a column your sheet does not
     * have. Those are three different buttons.
     */
    private static final URI UPLOAD_UNAVAILABLE =
            URI.create("https://edutrack/errors/import-upload-unavailable");
    private static final URI INCOMPLETE_MAPPING =
            URI.create("https://edutrack/errors/import-incomplete-mapping");
    private static final URI UNKNOWN_COLUMN =
            URI.create("https://edutrack/errors/import-unknown-column");

    /**
     * B-035's three, and the split is by remedy again.
     *
     * <p>{@code import-nothing-to-commit} and {@code import-rejected-rows-present}
     * are both "this file has bad rows" and are not one type, because the
     * remedies are opposite: the first has no valid rows at all and the user must
     * go back to their spreadsheet, the second has plenty and the user can simply
     * stop asking for all-or-nothing. One type would put a "import the valid rows
     * only" button on a screen where there are none.
     *
     * <p>{@code import-commit-queue-full} is the sibling of
     * {@code import-staging-full}: temporary, blameless and answered 503 with
     * {@code Retry-After}, not 500.
     */
    private static final URI NOTHING_TO_COMMIT =
            URI.create("https://edutrack/errors/import-nothing-to-commit");
    private static final URI REJECTED_ROWS_PRESENT =
            URI.create("https://edutrack/errors/import-rejected-rows-present");
    private static final URI COMMIT_QUEUE_FULL =
            URI.create("https://edutrack/errors/import-commit-queue-full");

    /**
     * B-037's three, and they are three because the remedies differ.
     *
     * <p>{@code import-batch-not-finished} clears itself in a moment and the
     * screen should wait; {@code import-batch-already-reversed} never clears and
     * the screen should stop offering the button; {@code import-schema-unavailable}
     * is not the caller's problem at all and needs an operator. One "cannot
     * reverse" type would put a Try again on two cases that will refuse forever,
     * which is the argument B-035 recorded for splitting the two above.
     */
    private static final URI BATCH_NOT_FINISHED =
            URI.create("https://edutrack/errors/import-batch-not-finished");
    private static final URI BATCH_ALREADY_REVERSED =
            URI.create("https://edutrack/errors/import-batch-already-reversed");
    private static final URI SCHEMA_UNAVAILABLE =
            URI.create("https://edutrack/errors/import-schema-unavailable");

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

    /**
     * B-033 · 404 for a preset that is not there under this schema.
     *
     * <p>The same {@code type} as the unknown-schema 404 above, because to a
     * caller they are one condition: the addressed resource is absent. The
     * {@code presetId} property is what lets step 3 drop a stale entry from its
     * picker rather than only reporting the failure — a preset another Admin
     * deleted between the list read and the click is the ordinary case here.
     */
    @ExceptionHandler(MappingPresetNotFoundException.class)
    ResponseEntity<ProblemDetail> handlePresetNotFound(MappingPresetNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(NOT_FOUND);
        problem.setTitle("Mapping preset not found");
        problem.setDetail(e.getMessage());
        problem.setProperty("schema", e.schemaKey());
        problem.setProperty("presetId", e.presetId());

        return problem(HttpStatus.NOT_FOUND, problem);
    }

    /**
     * B-033 · 422 when a preset names a target field the schema does not declare.
     *
     * <p>422 rather than 400: the body is well-formed JSON of the declared shape,
     * and what is wrong is that it refers to something absent. The realistic cause
     * is a preset built against an older registration rather than a typo, so both
     * lists go on the body — the screen can then say which entries it would have
     * to drop, instead of asking the user to compare two column lists by eye.
     */
    @ExceptionHandler(UnknownImportFieldException.class)
    ResponseEntity<ProblemDetail> handleUnknownField(UnknownImportFieldException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(UNKNOWN_FIELD);
        problem.setTitle("Unknown import field");
        problem.setDetail(e.getMessage());
        problem.setProperty("schema", e.schemaKey());
        problem.setProperty("unknownFields", e.unknownFields());
        problem.setProperty("fields", e.declaredFields());

        return problem(HttpStatus.UNPROCESSABLE_ENTITY, problem);
    }

    /**
     * B-033 · 400 for a preset that maps nothing.
     *
     * <p>The same status, {@code type} and {@code errors} shape Spring's own
     * {@code @Valid} failure produces, keyed on {@code mapping} — because this
     * <em>is</em> that failure, reached by a route {@code @NotEmpty} cannot see:
     * a map whose every value is the empty string is non-empty and maps nothing.
     * A caller should not have to handle "you sent no mapping" twice.
     */
    @ExceptionHandler(ImportMappingPresetService.EmptyMappingException.class)
    ResponseEntity<ProblemDetail> handleEmptyMapping(
            ImportMappingPresetService.EmptyMappingException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(VALIDATION_FAILED);
        problem.setTitle("Mapping preset was not saved");
        problem.setDetail(e.getMessage());
        problem.setProperty("errors", java.util.Map.of("mapping", new String[]{e.getMessage()}));

        return problem(HttpStatus.BAD_REQUEST, problem);
    }

    /**
     * B-034 · 422 when the staged upload the dry run names is not there.
     *
     * <p>Expired, or holding a different sheet — one condition, because the
     * remedy is one action. {@code stagedSheet} is present only for the second,
     * and is what lets the screen say which sheet it would have read instead of
     * only that something disagreed.
     *
     * <p>Not a 404: the id is in the body rather than the path, and the path's
     * own 404 means something else entirely (no such schema). Two 404s with
     * different remedies on one route is how a client ends up branching on
     * prose.
     */
    @ExceptionHandler(ImportUploadNotAvailableException.class)
    ResponseEntity<ProblemDetail> handleUploadUnavailable(ImportUploadNotAvailableException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(UPLOAD_UNAVAILABLE);
        problem.setTitle("Uploaded file is no longer available");
        problem.setDetail(e.getMessage());
        problem.setProperty("uploadId", e.uploadId());
        if (e.stagedSheet() != null) {
            problem.setProperty("sheet", e.stagedSheet());
            problem.setProperty("requestedSheet", e.requestedSheet());
        }

        return problem(HttpStatus.UNPROCESSABLE_ENTITY, problem);
    }

    /**
     * B-034 · 422 when a required column has no column mapped to it.
     *
     * <p>Both lists go on the body — the field names for a client that wants to
     * highlight its own rows, the headers for the sentence it writes. Step 3's
     * table is keyed by field name and its warning is written in headers, and
     * deriving one from the other means shipping a copy of the schema to do it.
     */
    @ExceptionHandler(IncompleteMappingException.class)
    ResponseEntity<ProblemDetail> handleIncompleteMapping(IncompleteMappingException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(INCOMPLETE_MAPPING);
        problem.setTitle("Required columns are not mapped");
        problem.setDetail(e.getMessage());
        problem.setProperty("schema", e.schemaKey());
        problem.setProperty("missingFields", e.missingFields());
        problem.setProperty("missingHeaders", e.missingHeaders());

        return problem(HttpStatus.UNPROCESSABLE_ENTITY, problem);
    }

    /**
     * B-034 · 422 when the mapping reads a column the sheet does not have.
     *
     * <p>The sheet's own headings are on the body for
     * {@link UnknownImportFieldException}'s reason: the realistic cause is a
     * preset saved against a renamed export, so the useful response is the list
     * to choose the right column from, not an instruction to go and compare two
     * lists by eye.
     */
    @ExceptionHandler(UnknownSourceColumnException.class)
    ResponseEntity<ProblemDetail> handleUnknownColumn(UnknownSourceColumnException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(UNKNOWN_COLUMN);
        problem.setTitle("Unknown column in the mapping");
        problem.setDetail(e.getMessage());
        problem.setProperty("sheet", e.sheet());
        problem.setProperty("unknownColumns", e.unknownColumns());
        problem.setProperty("headers", e.headers());

        return problem(HttpStatus.UNPROCESSABLE_ENTITY, problem);
    }

    /**
     * B-035 · 404 for a batch id that names no run.
     *
     * <p>The same {@code type} as the unknown-schema and missing-preset 404s
     * above, for the reason those two share it: to a caller the addressed
     * resource is simply absent. The id goes on the body so a screen polling
     * every two seconds knows which of its polls to stop.
     */
    @ExceptionHandler(ImportBatchNotFoundException.class)
    ResponseEntity<ProblemDetail> handleBatchNotFound(ImportBatchNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(NOT_FOUND);
        problem.setTitle("Import batch not found");
        problem.setDetail(e.getMessage());
        problem.setProperty("batchId", e.batchId());

        return problem(HttpStatus.NOT_FOUND, problem);
    }

    /**
     * B-036 · 404 when the batch is real and its error report is not there.
     *
     * <p>Shares the {@code type} with the 404 above rather than declaring one,
     * and that is the exception to this file's own rule about splitting by
     * remedy: neither absence offers the caller a button. A screen only reaches
     * this route from a non-null {@code errorReportUrl}, so the realistic caller
     * is a bookmark or a retry — and to both, "the report is not there" is one
     * answer however it came to be missing.
     *
     * <p>The batch's status goes on the body because it is what makes the
     * sentence honest: {@code RUNNING} means not yet, {@code COMPLETED} means
     * there is none, and a client that wants to tell them apart has the batch
     * itself one route away.
     */
    @ExceptionHandler(ImportErrorReportUnavailableException.class)
    ResponseEntity<ProblemDetail> handleErrorReportUnavailable(ImportErrorReportUnavailableException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(NOT_FOUND);
        problem.setTitle("Import error report not available");
        problem.setDetail(e.getMessage());
        problem.setProperty("batchId", e.batchId());
        problem.setProperty("status", e.status());

        return problem(HttpStatus.NOT_FOUND, problem);
    }

    /**
     * B-035 · 422 when a commit would write nothing.
     *
     * <p>Refused rather than accepted-and-completed-instantly. A batch row saying
     * a file was imported when nothing was is a false entry in the audit trail
     * B-037 is built on, and a green "done" answers the button press rather than
     * the outcome — on a screen that had just told the user nothing was
     * importable.
     *
     * <p>The counts are on the body so the screen can say <em>why</em> without
     * re-running the dry run it just ran.
     */
    @ExceptionHandler(NothingToCommitException.class)
    ResponseEntity<ProblemDetail> handleNothingToCommit(NothingToCommitException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(NOTHING_TO_COMMIT);
        problem.setTitle("Nothing to import");
        problem.setDetail(e.getMessage());
        problem.setProperty("schema", e.schemaKey());
        problem.setProperty("total", e.totalRows());
        problem.setProperty("rejected", e.rejected());
        problem.setProperty("duplicates", e.duplicates());

        return problem(HttpStatus.UNPROCESSABLE_ENTITY, problem);
    }

    /**
     * B-035 · 422 for {@code skipRejected: false} over a file with rejections.
     *
     * <p>A separate {@code type} from the one above and not a variant of it: the
     * remedies are opposite. There, no row is importable and the user has to go
     * back to their spreadsheet; here most rows are fine and the user can simply
     * import the valid ones. A shared type would put an offer on the screen that
     * one of the two cases cannot honour.
     */
    @ExceptionHandler(RejectedRowsPresentException.class)
    ResponseEntity<ProblemDetail> handleRejectedRowsPresent(RejectedRowsPresentException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(REJECTED_ROWS_PRESENT);
        problem.setTitle("The file has rows that cannot be imported");
        problem.setDetail(e.getMessage());
        problem.setProperty("schema", e.schemaKey());
        problem.setProperty("total", e.totalRows());
        problem.setProperty("rejected", e.rejected());
        problem.setProperty("duplicates", e.duplicates());

        return problem(HttpStatus.UNPROCESSABLE_ENTITY, problem);
    }

    /**
     * B-035 · 503 when every commit slot is taken.
     *
     * <p>Modelled on {@link #handleStagingFull} one step earlier in the wizard,
     * down to the thirty seconds: the ceiling clears as running imports finish
     * rather than on a schedule, so the header is a hint and not a promise.
     *
     * <p>{@code batchId} is on the body because the run was opened before it was
     * refused, and is now {@code FAILED}. The user will find it in the history
     * and is entitled to know which entry was theirs.
     */
    @ExceptionHandler(ImportCommitQueueFullException.class)
    ResponseEntity<ProblemDetail> handleCommitQueueFull(ImportCommitQueueFullException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        problem.setType(COMMIT_QUEUE_FULL);
        problem.setTitle("Too many imports are being committed");
        problem.setDetail(e.getMessage());
        problem.setProperty("ceiling", e.ceiling());
        if (e.batchId() != null) {
            problem.setProperty("batchId", e.batchId());
        }

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "30")
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    /**
     * B-037 · 422 for a reversal asked of a run that is still going.
     *
     * <p>{@code status} is on the body so the screen can say <em>which</em> —
     * "queued" and "running" mean different waits to somebody watching, and the
     * one thing this refusal must not do is read as a permanent no.
     */
    @ExceptionHandler(ImportBatchNotFinishedException.class)
    ResponseEntity<ProblemDetail> handleBatchNotFinished(ImportBatchNotFinishedException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(BATCH_NOT_FINISHED);
        problem.setTitle("This import has not finished");
        problem.setDetail(e.getMessage());
        problem.setProperty("batchId", e.batchId());
        problem.setProperty("status", e.status().name());

        return problem(HttpStatus.UNPROCESSABLE_ENTITY, problem);
    }

    /**
     * B-037 · 422 for a second reversal of the same run.
     *
     * <p>{@code reversedAt} is on the body because a caller who reaches this has
     * almost always got a stale history panel open in another tab, and "reversed
     * at 14:02" is the sentence that explains it without a re-read.
     *
     * <p><b>Not answered as a success.</b> The second call would delete nothing —
     * the rows are gone — so quietly returning 200 would be tempting and would
     * overwrite the batch's reversal record with the second attempt's zeroes. See
     * {@link ImportBatchAlreadyReversedException}.
     */
    @ExceptionHandler(ImportBatchAlreadyReversedException.class)
    ResponseEntity<ProblemDetail> handleAlreadyReversed(ImportBatchAlreadyReversedException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(BATCH_ALREADY_REVERSED);
        problem.setTitle("This import has already been reversed");
        problem.setDetail(e.getMessage());
        problem.setProperty("batchId", e.batchId());
        problem.setProperty("reversedAt", e.reversedAt());

        return problem(HttpStatus.UNPROCESSABLE_ENTITY, problem);
    }

    /**
     * B-037 · 422 when the registration that wrote a run is no longer installed.
     *
     * <p>Unreachable in a single-registration build and kept anyway, because the
     * alternative to refusing is guessing which table to delete from. See
     * {@link ImportSchemaUnavailableException}.
     */
    @ExceptionHandler(ImportSchemaUnavailableException.class)
    ResponseEntity<ProblemDetail> handleSchemaUnavailable(ImportSchemaUnavailableException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(SCHEMA_UNAVAILABLE);
        problem.setTitle("This import cannot be reversed here");
        problem.setDetail(e.getMessage());
        problem.setProperty("batchId", e.batchId());
        problem.setProperty("entity", e.entityCode());

        return problem(HttpStatus.UNPROCESSABLE_ENTITY, problem);
    }

    /** The content type is stated on every one of these, for the reason given above. */
    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, ProblemDetail body) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }
}
