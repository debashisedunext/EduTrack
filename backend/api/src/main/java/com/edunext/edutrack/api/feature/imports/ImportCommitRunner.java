package com.edunext.edutrack.api.feature.imports;

import com.edunext.edutrack.domain.imports.ImportBatchStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * B-035 · the background half of step 5 — walk the approved rows and upsert them.
 *
 * <p>Everything entity-specific is behind {@link ImportSchemaDefinition#upsert},
 * so this file mentions neither clients nor resources. B-038 registers the second
 * schema and this does not change; that is B-030's whole design and this is the
 * last step that could have broken it.
 *
 * <h2>One row, one transaction</h2>
 *
 * <p>{@code upsert} is {@code @Transactional} on the registration, and it is
 * called through the Spring proxy from a thread with no ambient transaction — so
 * each row commits on its own. That is deliberate three times over:
 *
 * <ul>
 *   <li><b>A bad row costs one row.</b> A file of five hundred where row 314
 *       breaks a constraint no validator declared — a column widened in the
 *       master since the registration was written, a unique index on something
 *       the engine does not know is unique — must not lose the other 499. The
 *       row is counted rejected and the walk continues.
 *   <li><b>Progress is real.</b> A run inside one transaction is invisible to
 *       every reader until it ends, so the poll a progress bar makes would
 *       return zeros and then jump. Worse, the counters would be describing
 *       writes that could still be rolled back.
 *   <li><b>No connection is held for minutes.</b>
 * </ul>
 *
 * <p>The cost is that a run interrupted halfway leaves half the file imported,
 * and that is the correct trade for this feature rather than a compromise: the
 * operation is an <em>upsert on the natural key</em>, so re-running the same
 * file finishes the job instead of duplicating what landed. That property is
 * what makes partial application safe, and it is the one thing this whole
 * feature is judged on.
 *
 * <h2>Counters are flushed in batches</h2>
 *
 * <p>Per row would be one UPDATE of {@code import_batches} for every UPDATE of
 * the master — doubling the write load of the feature so that a progress bar
 * polled every two seconds can be accurate to the row, which nobody can read.
 * Every {@link #FLUSH_EVERY} rows, plus once at the end, and the end flush is in
 * a {@code finally} so a run that dies still leaves its counters where it got to
 * rather than at the last multiple of fifty.
 */
@Component
class ImportCommitRunner {

    private static final Logger log = LoggerFactory.getLogger(ImportCommitRunner.class);

    /**
     * Fifty rows between progress writes.
     *
     * <p>A 5,000-row file therefore costs 100 extra UPDATEs rather than 5,000,
     * and a bar polled every two seconds still moves several times a second on
     * any file large enough for the bar to matter. On a small file the run is
     * over before the first flush, which is why the {@code finally} exists.
     */
    static final int FLUSH_EVERY = 50;

    private final ImportBatchService batches;

    ImportCommitRunner(ImportBatchService batches) {
        this.batches = batches;
    }

    /**
     * @param rows     the rows the dry run judged writable, in file order, already
     *                 mapped and already detached from the staging entry — this
     *                 runs long after the request that started it, and the
     *                 staging TTL is thirty minutes
     * @param rejected what the dry run already refused. Carried in rather than
     *                 recomputed so the counters this writes and the counts the
     *                 user approved are the same numbers
     */
    void run(ImportSchemaDefinition definition, long batchId,
             List<ImportRowVerdict> rows, int rejected) {

        int created = 0;
        int updated = 0;
        int failed = 0;
        int sinceFlush = 0;
        ImportBatchStatus outcome = ImportBatchStatus.COMPLETED;

        batches.markRunning(batchId);

        try {
            for (ImportRowVerdict verdict : rows) {
                try {
                    definition.upsert(new ImportRow(verdict.rowNumber(), verdict.values()), batchId);
                    if (verdict.verdict() == ImportVerdict.WILL_CREATE) {
                        created++;
                    } else {
                        updated++;
                    }
                } catch (RuntimeException rowFailed) {
                    // Counted, logged with the row number the user can find in
                    // their own spreadsheet, and the walk continues. Anything
                    // else here means one row's constraint violation discards
                    // work the user has already approved and watched land.
                    failed++;
                    log.warn("Import batch {} — row {} could not be written: {}",
                            batchId, verdict.rowNumber(), rowFailed.toString());
                }

                if (++sinceFlush >= FLUSH_EVERY) {
                    sinceFlush = 0;
                    batches.progress(batchId, created, updated, rejected + failed);
                }
            }

        } catch (RuntimeException fatal) {
            // Not a row failing — those are caught above. This is the job itself
            // dying: the database gone, the schema bean disposed mid-run. FAILED
            // is a different thing from COMPLETED-with-rejections and the two
            // must not be collapsed, because only one of them means "the run did
            // not finish and the rest of your file was never attempted".
            log.error("Import batch {} failed after {} created and {} updated",
                    batchId, created, updated, fatal);
            outcome = ImportBatchStatus.FAILED;
        }

        // Outside the try on purpose: the terminal write is the same call on
        // both paths, so there is one place a status is decided and one place it
        // is stored. Written even for FAILED, so a dead run still reports what it
        // managed rather than the counters from its last multiple of fifty.
        batches.finish(batchId, outcome, created, updated, rejected + failed);
    }
}
