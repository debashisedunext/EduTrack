package com.edunext.edutrack.domain.onboarding;

/**
 * {@code ob_attachments.uploaded_by_type} (A-102) — which side of the
 * portal an attachment came from. Exactly one of {@code uploadedByUser} /
 * {@code uploadedByContact} is set to match.
 */
public enum ObAttachmentUploaderType {
    STAFF,
    CLIENT
}
