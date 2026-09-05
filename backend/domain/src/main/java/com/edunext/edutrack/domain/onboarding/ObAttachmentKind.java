package com.edunext.edutrack.domain.onboarding;

/**
 * {@code ob_attachments.kind} (A-102). {@code REFERENCE} is a document staff
 * attach for the client to read; {@code SUBMISSION} is what the client sends
 * back. {@code DELIVERABLE} and {@code EVIDENCE} cover the service/sign-off
 * owner arms of the same table.
 */
public enum ObAttachmentKind {
    REFERENCE,
    SUBMISSION,
    DELIVERABLE,
    EVIDENCE
}
