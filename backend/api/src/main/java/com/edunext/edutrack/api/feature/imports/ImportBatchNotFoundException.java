package com.edunext.edutrack.api.feature.imports;

/**
 * B-035 · 404 for a batch id that names no run.
 *
 * <p>Not row-scoped, and there is nothing here for {@code ScopeResolver} to
 * answer about — an import batch has no assignee, project or client on it. The
 * 404 is the ordinary "no such resource", the same {@code type} the unknown
 * schema and the missing preset use, because to a caller they are one condition.
 *
 * <p>The realistic cause is a batch old enough to have been swept, or a wizard
 * left open across a redeploy, so the id goes on the body: a screen polling it
 * every two seconds needs to know which of its polls to stop.
 */
class ImportBatchNotFoundException extends RuntimeException {

    private final long batchId;

    ImportBatchNotFoundException(long batchId) {
        super("No import batch " + batchId + " exists.");
        this.batchId = batchId;
    }

    long batchId() {
        return batchId;
    }
}
