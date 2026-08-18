package com.edunext.edutrack.api.feature.imports;

import java.time.Instant;

/**
 * B-037 · 422 when a batch has already been reversed.
 *
 * <p><b>A reversal is not repeatable, and this is what makes that structural
 * rather than a matter of the screen disabling a button.</b> Running it twice
 * would be harmless in its effects — the second pass finds no rows carrying the
 * batch id, because the first deleted them — but it would overwrite
 * {@code reversed_at}, {@code reversed_by} and both counters with a second
 * reversal's zeroes. The record would then say the batch was reversed just now,
 * by whoever pressed the button last, deleting nothing: a false entry in the one
 * table that exists to make bad imports traceable.
 *
 * <p>{@code reversedAt} goes on the problem body so the screen can say
 * <em>when</em> without re-reading the batch. A user who reaches this refusal has
 * almost always got a stale list open in another tab, and "reversed at 14:02" is
 * the sentence that explains it.
 *
 * <p>A separate {@code type} from {@link ImportBatchNotFinishedException} —
 * see that class for why the two must not share one.
 */
class ImportBatchAlreadyReversedException extends RuntimeException {

    private final long batchId;
    private final Instant reversedAt;

    ImportBatchAlreadyReversedException(long batchId, Instant reversedAt) {
        super("Import #" + batchId + " has already been reversed. A run can only be reversed once.");
        this.batchId = batchId;
        this.reversedAt = reversedAt;
    }

    long batchId() {
        return batchId;
    }

    Instant reversedAt() {
        return reversedAt;
    }
}
