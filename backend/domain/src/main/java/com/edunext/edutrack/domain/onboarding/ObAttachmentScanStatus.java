package com.edunext.edutrack.domain.onboarding;

/**
 * {@code ob_attachments.scan_status} (A-102). Nothing may be served to
 * anyone, and nothing may count toward the C-106 document-checklist gate,
 * while a row is anything other than {@link #CLEAN}.
 */
public enum ObAttachmentScanStatus {
    PENDING,
    CLEAN,
    INFECTED,
    FAILED
}
