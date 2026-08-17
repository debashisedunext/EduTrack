package com.edunext.edutrack.api.feature.imports;

import com.edunext.edutrack.domain.imports.ImportBatch;
import com.edunext.edutrack.domain.imports.ImportBatchRepository;
import com.edunext.edutrack.domain.imports.ImportBatchStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * B-035 · {@code import_batches}, read and written.
 *
 * <p>One class for both directions because it is one table and the two halves
 * are three lines each; splitting them would put the ETag's inputs in a
 * different file from the code that moves them.
 *
 * <h2>Every write is its own transaction, and that is the point</h2>
 *
 * <p>The commit job runs for as long as the file takes. A transaction spanning
 * it would hold a connection for minutes, would make the progress a client polls
 * invisible until the end (nothing is committed, so nothing is readable), and
 * would roll the whole run back over one bad row — which is precisely the
 * behaviour {@link ImportCommitRunner} exists to avoid.
 *
 * <p>So the job calls these one at a time, from a thread with no ambient
 * transaction, and each call commits before the next row is touched. The
 * counters a poll reads are therefore always a real prefix of the run rather
 * than a projection of one.
 *
 * <h2>Nothing here is append-only</h2>
 *
 * <p>Worth stating because the word "batch" invites the comparison.
 * {@code import_batches} is not one of CLAUDE.md's three hash-chained tables and
 * carries no A-008 trigger: it is a job record whose counters climb, and the
 * rows it explains — clients — are ordinary mutable master data. The append-only
 * rule is about {@code ticket_history}, {@code ticket_effort_logs} and
 * {@code ticket_stage_transitions}, none of which this feature touches.
 */
@Service
class ImportBatchService {

    private final ImportBatchRepository batches;

    ImportBatchService(ImportBatchRepository batches) {
        this.batches = batches;
    }

    /**
     * Opens the run — {@code QUEUED}, before anything is submitted.
     *
     * <p>{@code totalRows} is every data row of the sheet and {@code rejected}
     * is what the dry run already refused, both stamped now rather than
     * discovered. That is what lets a progress bar start partly filled on a file
     * with bad rows instead of jumping at the end, and it means a run that dies
     * on its first row still says how big it was.
     */
    @Transactional
    ImportBatch open(String entityCode, String fileName, int totalRows, int rejected, Long userId) {
        ImportBatch batch = new ImportBatch();
        batch.setEntity(entityCode);
        batch.setFileName(fileName);
        batch.setTotalRows(totalRows);
        batch.setRejectedRows(rejected);
        batch.setStatus(ImportBatchStatus.QUEUED);
        batch.setImportedBy(userId);
        return batches.save(batch);
    }

    @Transactional
    void markRunning(long batchId) {
        batches.findById(batchId).ifPresent(batch -> {
            batch.setStatus(ImportBatchStatus.RUNNING);
            batches.save(batch);
        });
    }

    /**
     * A progress flush — absolute counts, never increments.
     *
     * <p>Absolute because the runner holds the truth and this is a projection of
     * it. An increment would have to be exactly-once to be correct, and a flush
     * that is retried, or one that raced another, would leave the row
     * permanently wrong with nothing to reconcile it against.
     */
    @Transactional
    void progress(long batchId, int created, int updated, int rejected) {
        batches.findById(batchId).ifPresent(batch -> {
            batch.setCreatedRows(created);
            batch.setUpdatedRows(updated);
            batch.setRejectedRows(rejected);
            batches.save(batch);
        });
    }

    /**
     * The terminal write.
     *
     * <p>{@code COMPLETED} is not a claim that nothing was rejected — see
     * {@link ImportBatchStatus}. A run that refused half the file and wrote the
     * rest completed, and B-036's error report is how the user recovers the
     * other half.
     */
    @Transactional
    void finish(long batchId, ImportBatchStatus status,
                int created, int updated, int rejected) {
        batches.findById(batchId).ifPresent(batch -> {
            batch.setCreatedRows(created);
            batch.setUpdatedRows(updated);
            batch.setRejectedRows(rejected);
            batch.setStatus(status);
            batches.save(batch);
        });
    }

    /** Used by the 503 path — the batch was opened and never ran. */
    @Transactional
    void fail(long batchId) {
        batches.findById(batchId).ifPresent(batch -> {
            batch.setStatus(ImportBatchStatus.FAILED);
            batches.save(batch);
        });
    }

    @Transactional(readOnly = true)
    ImportDtos.Batch find(long batchId) {
        return batches.findById(batchId)
                .map(ImportDtos.Batch::of)
                .orElseThrow(() -> new ImportBatchNotFoundException(batchId));
    }
}
