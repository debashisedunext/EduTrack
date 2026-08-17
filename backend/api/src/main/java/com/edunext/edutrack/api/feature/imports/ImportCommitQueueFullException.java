package com.edunext.edutrack.api.feature.imports;

/**
 * B-035 · 503 when every commit slot and the queue behind them are taken.
 *
 * <p>The sibling of {@link ImportStagingFullException}, and given a type of its
 * own for the reason B-032 gave that one: the condition is temporary, expected
 * and nothing the caller did wrong, so it is a 503 with {@code Retry-After}
 * rather than a 500 and a stack trace. Catching a bare
 * {@code RejectedExecutionException} in the advice would be one line less and
 * would turn every genuine executor bug into a cheerful "try again shortly".
 *
 * <p><b>Why not run it on the request thread instead.</b>
 * {@code AttachmentScanConfig} saturates to {@link
 * java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy} and is right to: a
 * scan is seconds, and dropping it would leave an attachment PENDING for ever.
 * A commit is not seconds. Caller-runs here holds an HTTP connection open for up
 * to five thousand upserts, and the client on the other end is a browser that
 * has been told to poll for progress — it would time out waiting for the
 * response that tells it what to poll.
 *
 * <p>The batch row is already open by the time submission is attempted, and it
 * is marked {@code FAILED} rather than deleted. A refused run that left no trace
 * is indistinguishable from a run nobody started, and the whole point of
 * {@code import_batches} is that every attempt is identified.
 */
class ImportCommitQueueFullException extends RuntimeException {

    private final int ceiling;
    private final Long batchId;

    ImportCommitQueueFullException(int ceiling, Long batchId) {
        super("Too many imports are being committed at once (" + ceiling
                + " queued or running). Nothing of this file has been written. "
                + "Try again in a few seconds — the queue clears as the running imports finish.");
        this.ceiling = ceiling;
        this.batchId = batchId;
    }

    int ceiling() {
        return ceiling;
    }

    /** The batch that was opened and immediately failed, so the caller can find it in the history. */
    Long batchId() {
        return batchId;
    }
}
