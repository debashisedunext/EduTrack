package com.edunext.edutrack.api.feature.onboarding.prereqs;

/**
 * B-124 · the attachment being listed does not belong to the task it is
 * being listed under, or is not a reference document.
 *
 * <p>The migration's §5 records why no foreign key holds this: the doc
 * row's task and the attachment's owner answer different questions, and a
 * composite key would refuse every document carried into a new draft by a
 * revision. That leaves the check here, on the one path where the two must
 * agree — a fresh upload is always uploaded against the task it is for, so
 * an attachment naming a different owner is a caller error rather than a
 * clone.
 *
 * <p>400: the request is malformed against state the caller can see, not a
 * conflict with a row's lifecycle.
 */
class PrereqAttachmentNotOwnedByTaskException extends RuntimeException {

    PrereqAttachmentNotOwnedByTaskException(long attachmentId, long templateTaskId, String reason) {
        super("attachment " + attachmentId + " cannot be listed on prerequisite template task "
                + templateTaskId + ": " + reason);
    }
}
