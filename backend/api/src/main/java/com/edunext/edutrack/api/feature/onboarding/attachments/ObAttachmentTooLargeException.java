package com.edunext.edutrack.api.feature.onboarding.attachments;

/**
 * B-107 · the file is over {@code edutrack.attachments.max-file-bytes}.
 *
 * <p><b>413, not 400.</b> The request is well-formed and the caller is
 * permitted; what is wrong is its size, which is what 413 means and what the
 * contract declares. {@code ChatAttachmentTooLargeException} makes the identical
 * call one module over.
 *
 * <p>The message carries both numbers, because "too large" without the cap
 * leaves somebody guessing whether to compress a screenshot or split a PDF.
 */
public class ObAttachmentTooLargeException extends RuntimeException {

    private final long sizeBytes;
    private final long maxBytes;

    public ObAttachmentTooLargeException(long sizeBytes, long maxBytes) {
        super("That file is %,d bytes; the limit is %,d.".formatted(sizeBytes, maxBytes));
        this.sizeBytes = sizeBytes;
        this.maxBytes = maxBytes;
    }

    public long sizeBytes() {
        return sizeBytes;
    }

    public long maxBytes() {
        return maxBytes;
    }
}
