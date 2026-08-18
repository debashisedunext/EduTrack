package com.edunext.edutrack.api.feature.imports;

import com.edunext.edutrack.domain.imports.ImportBatchStatus;

/**
 * B-037 · 422 when a reversal is asked for a run that is still going.
 *
 * <p>A {@code QUEUED} or {@code RUNNING} batch has a background job walking its
 * rows right now. Reversing it would be two threads deleting and inserting the
 * same clients — the job would go on creating rows the reversal has already
 * counted, and the batch would finish looking reversed while carrying clients
 * written after the reversal read its set.
 *
 * <p><b>Not a 409.</b> The distinction CONVENTIONS.md draws is that a 409 is a
 * conflict with the state of the resource that the caller could resolve by
 * re-reading it; this is a request that is well-formed and simply premature, and
 * the remedy is to wait for the run the caller is already watching. The other
 * refusals in this package that mean "your request cannot be carried out as
 * asked" — {@link NothingToCommitException}, {@link RejectedRowsPresentException}
 * — are 422s for the same reason.
 *
 * <p>Its own {@code type}, not shared with
 * {@link ImportBatchAlreadyReversedException}, because the two remedies are
 * opposite: this one resolves itself in a moment and the screen should offer to
 * wait, and that one never resolves and the screen should stop offering the
 * button. One type would put a "try again" on a batch that will refuse forever.
 */
class ImportBatchNotFinishedException extends RuntimeException {

    private final long batchId;
    private final ImportBatchStatus status;

    ImportBatchNotFinishedException(long batchId, ImportBatchStatus status) {
        super("Import #" + batchId + " is still " + status.name().toLowerCase()
                + ". A run can only be reversed once it has finished.");
        this.batchId = batchId;
        this.status = status;
    }

    long batchId() {
        return batchId;
    }

    ImportBatchStatus status() {
        return status;
    }
}
