package com.edunext.edutrack.api.feature.tickets.attachments;

import java.io.Serial;

/**
 * C-025 · 413. Blueprint §4B.4's limits — 10 MB per file, 50 MB and 20 files per
 * ticket.
 *
 * <p>The caps themselves are C-027's task, which makes them configurable in
 * system settings; they are enforced here because a security pipeline that
 * stores an unbounded upload before anyone asks how big it is has already lost.
 * {@link AttachmentProperties} holds them as properties in the meantime, so
 * C-027 is a settings source rather than a rewrite.
 *
 * <p>The message states the limit and what would be used, because the user's
 * next action depends on which: over the per-file cap they need a different
 * file, over the per-ticket cap they need to remove one. "Too large" answers
 * neither.
 */
class AttachmentLimitExceededException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private AttachmentLimitExceededException(String message) {
        super(message);
    }

    static AttachmentLimitExceededException fileTooLarge(long sizeBytes, long limitBytes) {
        return new AttachmentLimitExceededException(
                megabytes(sizeBytes) + " exceeds the " + megabytes(limitBytes) + " limit for one file.");
    }

    static AttachmentLimitExceededException ticketFull(long usedBytes, long sizeBytes, long limitBytes) {
        return new AttachmentLimitExceededException(
                "This ticket holds " + megabytes(usedBytes) + " of attachments and this file is "
                        + megabytes(sizeBytes) + ", which would take it past its "
                        + megabytes(limitBytes) + " total. Remove an attachment first.");
    }

    static AttachmentLimitExceededException tooManyFiles(int limit) {
        return new AttachmentLimitExceededException(
                "A ticket may hold " + limit + " attachments. Remove one before adding another.");
    }

    /**
     * Binary units with decimal labels, matching {@code formatFileSize} in
     * {@code components/ui/attachments.ts} and the blueprint's own examples in
     * §4B.5 ({@code qa-signoff-report.pdf (412 KB)}). The client's rejection
     * message and the server's must read the same, or a user who hits the cap on
     * one side and not the other has two different numbers for one rule.
     *
     * <p><b>C-027 · delegated rather than duplicated.</b> This class and
     * {@link AttachmentLimits.Bytes} carried the same fifteen lines and the same
     * claim about agreeing with the client, and they had drifted from it
     * identically — a 2 MB cap rendered {@code "2.0 MB"} where the client
     * renders {@code "2 MB"}. Two copies of a rule about staying in step is the
     * shape that guarantees they will not.
     */
    private static String megabytes(long bytes) {
        return AttachmentLimits.Bytes.human(bytes);
    }
}
