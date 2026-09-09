package com.edunext.edutrack.api.feature.portal.onboarding;

import com.edunext.edutrack.api.feature.onboarding.attachments.ObAttachmentOwner;
import com.edunext.edutrack.api.feature.onboarding.attachments.ObAttachmentPipeline;
import com.edunext.edutrack.domain.onboarding.ObAttachment;
import com.edunext.edutrack.domain.onboarding.ObAttachmentKind;
import com.edunext.edutrack.domain.onboarding.ObAttachmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * C-121 · CP-04's upload — a client filing evidence against their own
 * prerequisite task.
 *
 * <p>The pipeline call this class exists to make was already anticipated:
 * {@code ObAttachmentPipeline.Uploader.client(contactId)}'s own javadoc names
 * "CP-04" as its caller. What did not exist yet was the owner arm itself —
 * see the {@code ObAttachmentOwner.PREREQ_TASK} addition this task made, and
 * the domain column that had a migration but no Java write path until now.
 */
@Service
class PortalPrereqAttachmentService {

    private final ObAttachmentPipeline pipeline;
    private final ObAttachmentRepository attachments;

    PortalPrereqAttachmentService(ObAttachmentPipeline pipeline, ObAttachmentRepository attachments) {
        this.pipeline = pipeline;
        this.attachments = attachments;
    }

    @Transactional
    PortalOnboardingDtos.PortalSubmissionFile upload(long prereqTaskId, long uploaderContactId,
                                                     String fileName, byte[] content) {

        ObAttachment saved = pipeline.store(ObAttachmentOwner.PREREQ_TASK, prereqTaskId, ObAttachmentKind.SUBMISSION,
                ObAttachmentPipeline.Uploader.client(uploaderContactId), fileName, content);
        return view(saved);
    }

    @Transactional(readOnly = true)
    List<PortalOnboardingDtos.PortalSubmissionFile> submissionsOf(long prereqTaskId) {
        return attachments.findByPrereqTaskIdOrderByIdAsc(prereqTaskId).stream()
                .filter(row -> row.getDeletedAt() == null)
                .map(this::view)
                .toList();
    }

    private PortalOnboardingDtos.PortalSubmissionFile view(ObAttachment row) {
        return new PortalOnboardingDtos.PortalSubmissionFile(
                row.getId(), row.getFileName(), row.getSizeBytes(), row.getUploadedByType(),
                row.getCreatedAt(),
                pipeline.signedUrlFor(row).map(Object::toString).orElse(null));
    }
}
