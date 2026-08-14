package com.edunext.edutrack.api.feature.tickets.attachments;

/**
 * C-025 · the anti-virus scan that stands between an upload and anybody being
 * able to open it — blueprint §4B.4, "anti-virus scan before the file becomes
 * visible".
 *
 * <h2>Three outcomes, and the third is not a failure to report</h2>
 *
 * <p>{@link Verdict#CLEAN} and {@link Verdict#INFECTED} are answers.
 * {@link Verdict#UNKNOWN} is the absence of one — the scanner was unreachable,
 * timed out, or is not configured — and it is a first-class outcome rather than
 * an exception precisely so that a caller has to decide what to do about it. An
 * implementation that threw would invite a {@code catch} that logs and carries
 * on, which is how an outage becomes a silent policy change.
 *
 * <p>{@code UNKNOWN} leaves the attachment PENDING. It is not "clean until
 * proven otherwise": the row's default is PENDING for this reason and the
 * migration that created it says so. The one deployment where UNKNOWN may be
 * treated as clean is local development, and that is an explicit,
 * profile-guarded opt-in ({@code edutrack.attachments.scan.fail-open}) that
 * {@link AttachmentScanConfig} refuses to start with anywhere else.
 */
interface AttachmentScanner {

    enum Verdict {

        /** The scanner examined the bytes and found nothing. */
        CLEAN,

        /** The scanner found something. The object is deleted and never served. */
        INFECTED,

        /**
         * No verdict was obtained. The file stays PENDING and stays unreadable.
         * This is the outcome of every outage, timeout and misconfiguration, and
         * it must never be widened into CLEAN by an implementation.
         */
        UNKNOWN
    }

    /**
     * @param fileName carried for the scan log only. It is not passed to the
     *                 scanner as a path and does not affect the verdict — the
     *                 bytes decide, exactly as they do in {@link AttachmentSniffer}
     * @param content  the stored, EXIF-stripped bytes
     */
    Verdict scan(String fileName, byte[] content);
}
