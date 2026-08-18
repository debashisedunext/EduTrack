package com.edunext.edutrack.api.feature.imports;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * B-035 · {@code GET /import-batches/{batchId}} — the progress bar's route.
 *
 * <h2>Why this is a second controller</h2>
 *
 * <p>The path root is different. A batch outlives the wizard that started it —
 * it is the record of a run, readable long after the upload it came from
 * expired — so the contract puts it at {@code /import-batches} rather than under
 * {@code /imports/{schema}}, where it would have had to carry a schema segment
 * that is already stored on the row. Spring will not serve two roots from one
 * {@code @RequestMapping}, and nesting this under the wizard's path to save a
 * class would mean inventing a schema for every read of the history.
 *
 * <p>It is named on {@link ImportExceptionHandler}'s {@code assignableTypes}
 * alongside {@link ImportController}, so both share the package's problem
 * documents rather than this one growing a second advice.
 *
 * <h2>The ETag is the point of the route, not decoration</h2>
 *
 * <p>This is polled every couple of seconds for the length of an import.
 * {@link ImportCommitRunner} flushes its counters every fifty rows, so most of
 * those polls are asking a question whose answer provably has not moved — and
 * the validator says so for the cost of a hash. Same shape as
 * {@code DashboardController}, which polls summary tables that refresh every
 * five minutes.
 *
 * <h2>Permission</h2>
 *
 * <p>{@code master.write}, like the wizard that starts the job — a run's
 * progress is not more public than the operation it reports on.
 *
 * <p><b>403 rather than 404 for a role without it</b>, and this is one of the
 * few rowless-403 entries where the row genuinely exists. The refusal still does
 * not depend on it: the capability is decided before the id is looked up, so a
 * Developer cannot distinguish a real batch id from an invented one either way,
 * and an import batch is org-wide master data with no assignee, project or
 * client on it for {@code ScopeResolver} to answer about. Recorded in
 * {@code check-conventions.py}'s {@code ROWLESS_403} with that reason, the same
 * way B-033 records the presets.
 */
@RestController
@RequestMapping("/api/v1/import-batches")
@Tag(name = "imports")
class ImportBatchController {

    private final ImportBatchService batches;

    ImportBatchController(ImportBatchService batches) {
        this.batches = batches;
    }

    /**
     * @param ifNoneMatch the validator from the previous poll, if any. A match is
     *                    a 304 with no body — see the class javadoc for why that
     *                    is the ordinary case rather than an optimisation
     */
    @GetMapping(path = "/{batchId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('master.write')")
    @Operation(operationId = "getImportBatch",
            summary = "Progress of one import run, and its error report (S-34 step 5)")
    ResponseEntity<ImportDtos.BatchResponse> batch(
            @PathVariable long batchId,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {

        ImportDtos.Batch batch = batches.find(batchId);
        String etag = batch.etag();

        if (ifNoneMatch != null && matches(ifNoneMatch, etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }

        return ResponseEntity.ok().eTag(etag).body(new ImportDtos.BatchResponse(batch));
    }

    /**
     * {@code *} matches anything, per RFC 9110, and a list is matched
     * element-wise — {@code If-None-Match} is defined as a comma-separated set,
     * and a client sending two validators is entitled to a 304 on either.
     *
     * <p>{@code DashboardController} and {@code CalendarController} each carry a
     * near-identical helper, which makes this the third. The shared one belongs
     * in {@code common/} — Stream A's directory — rather than being extracted
     * across two streams' paths by whoever happened to write the third copy.
     * Raised rather than taken.
     */
    private static boolean matches(String ifNoneMatch, String current) {
        for (String candidate : ifNoneMatch.split(",")) {
            String trimmed = candidate.trim();
            if ("*".equals(trimmed)
                    || trimmed.replace("W/", "").replace("\"", "").equals(current)) {
                return true;
            }
        }
        return false;
    }
}
