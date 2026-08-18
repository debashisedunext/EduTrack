package com.edunext.edutrack.api.feature.imports;

import com.edunext.edutrack.domain.imports.ImportBatch;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * B-035 · blueprint §4B.3 step 5 — commit, as a background job.
 *
 * <p>The step everything before it was making safe. Steps 1 to 4 wrote nothing
 * at all; this one writes the client master in bulk, and the only reason that is
 * an acceptable thing for a screen to do is that the user has just been shown,
 * row by row, exactly what it will do.
 *
 * <h2>The preview is re-derived, never accepted</h2>
 *
 * <p>This request carries an upload id and a mapping — the same two things
 * {@code /validate} takes — and <b>no verdicts</b>. It could have carried the
 * preview the user approved, and that would have been a mistake of the kind that
 * is invisible until it is exploited: the rows a commit writes would then be
 * whatever the caller said they were, and the dry run's guarantee would be a
 * client-side convention rather than a server-side property.
 *
 * <p>Re-running it costs one pass over rows already in heap and one existence
 * probe. The same file and the same mapping reach the same judgements, so the
 * thing the user approved and the thing that gets written are the same set by
 * construction rather than by agreement.
 *
 * <h2>The order the work happens in</h2>
 *
 * <ol>
 *   <li><b>Resolve and refuse</b> — {@link ImportRequestResolver}, the same four
 *       422s step 4 answers, in the same order. Nothing has been written and no
 *       batch row exists, so a refusal here leaves the wizard exactly where it
 *       was and the staged file still staged.
 *   <li><b>Validate</b> — and refuse a run with nothing to write, or a strict
 *       run over a file with rejections.
 *   <li><b>Read the rows out of staging, then release it.</b> Both before the
 *       response, because the job outlives the thirty-minute staging TTL.
 *   <li><b>Open the batch row</b>, {@code QUEUED}.
 *   <li><b>Submit</b>, and answer 202 with the batch.
 * </ol>
 *
 * <p><b>Staging is released before the job starts, not after it finishes.</b>
 * The job holds its own immutable list of rows and never looks the upload up
 * again — so nothing it needs can expire underneath it, and the slot is freed
 * for the next admin rather than held for the length of a run. The visible
 * consequence is that committing the same {@code uploadId} twice answers
 * {@code import-upload-unavailable}, which is the right refusal for the request
 * that would otherwise have written the file twice.
 */
@Service
class ImportCommitService {

    private final ImportRequestResolver resolver;
    private final ImportValidationEngine engine;
    private final ImportBatchService batches;
    private final ImportCommitRunner runner;
    private final ExecutorService executor;
    private final ImportCommitConfig.ImportCommitCeiling ceiling;

    ImportCommitService(ImportRequestResolver resolver,
                        ImportValidationEngine engine,
                        ImportBatchService batches,
                        ImportCommitRunner runner,
                        @Qualifier(ImportCommitConfig.EXECUTOR) ExecutorService executor,
                        ImportCommitConfig.ImportCommitCeiling ceiling) {
        this.resolver = resolver;
        this.engine = engine;
        this.batches = batches;
        this.runner = runner;
        this.executor = executor;
        this.ceiling = ceiling;
    }

    /**
     * @param userId the caller, best-effort — recorded on the batch and on no
     *               key. An unidentifiable caller commits with a null
     *               {@code imported_by} rather than being refused, the same
     *               trade B-033 made for a preset's {@code created_by}
     * @return the batch as it stands the instant it was queued. Every counter is
     *         at its starting value; the client polls
     *         {@code GET /import-batches/{batchId}} from here
     */
    ImportDtos.Batch commit(String schemaKey, ImportDtos.CommitRequest request, Long userId) {
        ImportRequestResolver.Resolved resolved = resolver.resolve(
                schemaKey, request.uploadId(), request.sheet(), request.mapping());

        ImportPreview preview = engine.validate(resolved.definition(), resolved.rows());
        List<ImportRowVerdict> writable = preview.writable();

        // Both refusals happen before the staging entry is released and before a
        // batch row exists, so the user can flip skipRejected or fix the file and
        // press the button again without re-uploading.
        if (!request.skipRejectedOrDefault() && (preview.rejected() > 0 || preview.duplicates() > 0)) {
            throw new RejectedRowsPresentException(resolved.definition().key(),
                    preview.rows().size(), preview.rejected(), preview.duplicates());
        }
        if (writable.isEmpty()) {
            throw new NothingToCommitException(resolved.definition().key(),
                    preview.rows().size(), preview.rejected(), preview.duplicates());
        }

        // B-036 · the rows the run will not write, kept rather than counted.
        // Their count is what the batch opens with — the same number
        // `preview.rejected() + preview.duplicates()` gave before — and the rows
        // themselves are what the error report is built from. This is the last
        // moment they exist: the staging entry is released four lines down and
        // the preview is never stored, so a report generated any later would have
        // nothing to describe.
        List<ImportRowVerdict> unwritten = preview.rows().stream()
                .filter(row -> !row.isWritable())
                .toList();

        ImportBatch batch = batches.open(
                resolved.definition().entityCode(),
                resolved.upload().fileName(),
                preview.rows().size(),
                unwritten.size(),
                userId);

        // The rows are in hand; the staging entry is not needed by anything
        // downstream and holding it would keep a slot for the length of the run.
        resolver.release(resolved.upload().uploadId());

        submit(resolved.definition(), batch.getId(), writable, unwritten);

        return ImportDtos.Batch.of(batch);
    }

    /**
     * Hands the run to the pool, or turns a full queue into a 503.
     *
     * <p>The batch is marked {@code FAILED} rather than deleted — see
     * {@link ImportCommitQueueFullException}. A refused attempt that left no
     * trace is indistinguishable from an attempt nobody made, and every attempt
     * being identified is what {@code import_batches} is for.
     */
    private void submit(ImportSchemaDefinition definition, long batchId,
                        List<ImportRowVerdict> writable, List<ImportRowVerdict> unwritten) {
        try {
            executor.execute(() -> runner.run(definition, batchId, writable, unwritten));
        } catch (RejectedExecutionException full) {
            batches.fail(batchId);
            throw new ImportCommitQueueFullException(ceiling.value(), batchId);
        }
    }
}
