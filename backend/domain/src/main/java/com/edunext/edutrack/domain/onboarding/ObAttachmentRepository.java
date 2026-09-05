package com.edunext.edutrack.domain.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ObAttachmentRepository extends JpaRepository<ObAttachment, Long> {

    /**
     * C-106's document-checklist gate: clean, non-tombstoned attachments on
     * one step. Deliberately not filtered by {@link ObAttachmentKind} —
     * the template checklist names a document by label, not by kind, and
     * nothing on this row links back to which checklist entry it satisfies
     * (see {@code ObJourneyTemplateStepDoc}'s own note); the gate can only
     * count attachments against required entries, not match them one to one.
     */
    long countByStepIdAndScanStatusAndDeletedAtIsNull(Long stepId, ObAttachmentScanStatus scanStatus);
}
