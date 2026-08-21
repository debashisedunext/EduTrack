package com.edunext.edutrack.api.feature.chat;

/**
 * D-053 · the file is bigger than {@code edutrack.attachments.max-file-bytes}.
 *
 * <p>413, not 400: the request is well-formed and the body is the problem,
 * which is what {@code Payload Too Large} means and what
 * {@code AttachmentLimitExceededException} already answers for the ticket
 * route. Sharing that class instead would mean widening an exception whose
 * message talks about a ticket's total budget and its file count — limits a
 * chat thread does not have, since a thread has no defensible ceiling the way
 * a ticket's evidence bundle does.
 *
 * <p>The message names both numbers. A user who has just watched a 12 MB
 * upload fail needs to know what the ceiling is, and the ceiling is not a
 * secret.
 */
class ChatAttachmentTooLargeException extends RuntimeException {

    ChatAttachmentTooLargeException(long sizeBytes, long maxBytes) {
        super("That file is " + megabytes(sizeBytes) + " MB and the limit is "
                + megabytes(maxBytes) + " MB.");
    }

    private static String megabytes(long bytes) {
        return String.format("%.1f", bytes / 1024.0 / 1024.0);
    }
}
