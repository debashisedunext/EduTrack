package com.edunext.edutrack.api.feature.reports;

import java.util.Optional;

/**
 * A-065 · where a scheduled run's generated file lives — PLAN.md §2.2's object
 * store, the third consumer after attachments (C-025) and import error reports
 * (B-036).
 *
 * <h2>Two methods, and deliberately no third</h2>
 *
 * <p>{@link ImportReportStore}'s argument, and it is sharper here.
 * <b>Nothing in this interface can produce a public address.</b> There is no
 * presign, no ACL and no bucket URL, so a generated report is readable only
 * through {@link ReportScheduleController}'s download route — which requires a
 * session and re-checks that the caller owns the schedule.
 *
 * <p>The reason it is sharper: an import error report is minted for a screen
 * somebody is looking at, whereas this file's existence is announced <em>by
 * email</em>. A presigned URL would therefore be a credential sitting in a mail
 * archive, forwarded with the message, and valid long after whatever access the
 * recipient had was taken away. That is the one thing this feature must not
 * mint, and the way to guarantee it is to have no code that can.
 *
 * <p>This is also why the mail carries no attachment. A file attached to an
 * email is the same uncontrolled copy with the signature step skipped.
 */
interface ReportFileStore {

    /**
     * Store one run's file and answer the key it went under.
     *
     * <p>Returns the key rather than taking one, so the layout stays this
     * store's business — a caller that composed keys would be a second place
     * that has to agree about them.
     *
     * @throws RuntimeException if the object store refuses or cannot be
     *         reached. The caller decides what that means; in
     *         {@link ScheduledReportRunner} it means the run is recorded FAILED
     *         and no mail goes out, because a mail announcing a file that was
     *         never stored is worse than no mail
     */
    String put(long scheduleId, long runId, String fileName, String contentType, byte[] file);

    /** The stored bytes, or empty when the object is gone. */
    Optional<byte[]> read(String key);
}
