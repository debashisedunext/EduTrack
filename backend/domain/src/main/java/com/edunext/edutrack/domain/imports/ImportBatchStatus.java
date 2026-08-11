package com.edunext.edutrack.domain.imports;

import java.util.Locale;
import java.util.Optional;

/**
 * B-030 · the lifecycle of one Excel import run.
 *
 * <p>These four names are {@code ImportBatchResponse.status} in
 * {@code contracts/openapi.yaml}, and since
 * {@code V20260810_2010__import_batch_status_vocabulary} they are also the only
 * values {@code import_batches.status} accepts. The column previously carried a
 * second, private set — {@code PENDING|VALIDATING|COMMITTING|DONE} — that no
 * caller could observe, so a row at its own default serialised as a value the
 * generated TypeScript union does not contain.
 *
 * <p><b>Nothing precedes {@link #QUEUED}.</b> The step-4 dry run writes nothing
 * (blueprint §4B.3), so there is no row to hold a "being validated" state — the
 * first INSERT happens at commit. A status for validation would name something
 * no batch can be.
 */
public enum ImportBatchStatus {

    /** Committed by the user, not yet picked up by the background job. */
    QUEUED,

    /** The job is upserting rows. {@code processed} climbs towards {@code total}. */
    RUNNING,

    /**
     * Every row reached a terminal outcome. <b>Not a synonym for "no rejections"
     * —</b> a run that rejected half the file and wrote the rest completed, and
     * its error report (B-036) is how the user recovers the other half.
     */
    COMPLETED,

    /** The job itself died. Distinct from COMPLETED-with-rejections above. */
    FAILED;

    /**
     * @return empty for a status this build does not know, so a row written by a
     *         newer deploy is ignored rather than fatal — the same tolerance
     *         {@code NotificationChannel.of(String)} applies, for the same
     *         reason.
     */
    public static Optional<ImportBatchStatus> of(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(code.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }
}
