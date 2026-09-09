package com.edunext.edutrack.api.feature.portal.onboarding;

import com.edunext.edutrack.api.feature.onboarding.attachments.ObAttachmentPipeline;
import com.edunext.edutrack.domain.onboarding.ObAttachmentRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * C-121 · CP-04's reference documents — admin-attached specimens on the
 * prerequisite <em>template</em> task, read through the client's task
 * instance exactly as {@code ObClientPrereqAssembler#referenceDocsOf} does
 * for staff.
 *
 * <p>The join is read again here rather than reused, for the same
 * package-visibility reason as {@link PortalJourneyReader}. What <em>is</em>
 * reused is {@link ObAttachmentPipeline} — public, and the one place A-102's
 * "nothing may be served while PENDING/INFECTED/tombstoned" rule lives.
 * Restating that rule here would be the second place {@code
 * ObAttachmentPipeline}'s own javadoc warns is where "PENDING is probably
 * fine" gets written.
 */
@Repository
class PortalReferenceDocReader {

    private final JdbcClient jdbc;
    private final ObAttachmentRepository attachments;
    private final ObAttachmentPipeline pipeline;

    PortalReferenceDocReader(JdbcClient jdbc, ObAttachmentRepository attachments, ObAttachmentPipeline pipeline) {
        this.jdbc = jdbc;
        this.attachments = attachments;
        this.pipeline = pipeline;
    }

    /** Empty for an ad-hoc task, which has no master row to read through. */
    List<PortalOnboardingDtos.PortalReferenceDoc> referenceDocsOf(Long templateTaskId) {
        if (templateTaskId == null) {
            return List.of();
        }
        List<DocRow> rows = jdbc.sql("""
                        SELECT d.id, d.template_task_id, d.label, d.attachment_id
                          FROM ob_prereq_template_task_docs d
                         WHERE d.template_task_id = :taskId
                         ORDER BY d.sequence ASC, d.id ASC
                        """)
                .param("taskId", templateTaskId)
                .query((rs, n) -> new DocRow(rs.getLong("id"), rs.getLong("template_task_id"),
                        rs.getString("label"), rs.getLong("attachment_id")))
                .list();

        List<PortalOnboardingDtos.PortalReferenceDoc> out = new ArrayList<>();
        for (DocRow row : rows) {
            attachments.findById(row.attachmentId()).ifPresent(attachment -> out.add(
                    new PortalOnboardingDtos.PortalReferenceDoc(
                            row.id(), row.templateTaskId(), row.label(), row.attachmentId(),
                            attachment.getFileName(), attachment.getSizeBytes(),
                            pipeline.signedUrlFor(attachment).map(Object::toString).orElse(null))));
        }
        return out;
    }

    private record DocRow(long id, long templateTaskId, String label, long attachmentId) {
    }
}
