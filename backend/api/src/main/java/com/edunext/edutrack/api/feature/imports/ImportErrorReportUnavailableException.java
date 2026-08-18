package com.edunext.edutrack.api.feature.imports;

/**
 * B-036 · 404 for a run that has no error report to download.
 *
 * <p>A type of its own rather than {@link ImportBatchNotFoundException}, and the
 * split is by remedy the way every refusal in this package is. "No such import"
 * means the id is wrong and the screen should stop polling it; "this import has
 * no report" means the id is right and the answer is on the batch the caller can
 * already read — it is still running, nothing was rejected, or the report could
 * not be stored.
 *
 * <p>Both are 404 and both carry the product's ordinary not-found {@code type},
 * because to a caller they are two shades of the same absence and neither offers
 * a button. What separates them is the {@code detail} and the {@code status}
 * property, which is why the batch's status travels on the body: the honest
 * sentence for a run at {@code RUNNING} is "not yet" and for one at
 * {@code COMPLETED} is "there is none".
 *
 * <p>Reaching this at all means a client ignored a null {@code errorReportUrl} —
 * the step-5 screen only offers the download when the batch says there is one.
 * It is still worth answering properly: the URL is a link somebody can bookmark,
 * and a bookmark that 500s is a worse answer than one that says the report has
 * gone.
 */
class ImportErrorReportUnavailableException extends RuntimeException {

    private final long batchId;
    private final String status;

    ImportErrorReportUnavailableException(long batchId, String status) {
        super("Import batch " + batchId + " has no error report to download (status " + status + ").");
        this.batchId = batchId;
        this.status = status;
    }

    long batchId() {
        return batchId;
    }

    String status() {
        return status;
    }
}
