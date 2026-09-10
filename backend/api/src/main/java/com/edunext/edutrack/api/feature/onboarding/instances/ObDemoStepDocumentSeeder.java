package com.edunext.edutrack.api.feature.onboarding.instances;

import com.edunext.edutrack.domain.onboarding.ObAttachment;
import com.edunext.edutrack.domain.onboarding.ObAttachmentKind;
import com.edunext.edutrack.domain.onboarding.ObAttachmentRepository;
import com.edunext.edutrack.domain.onboarding.ObAttachmentScanStatus;
import com.edunext.edutrack.domain.onboarding.ObAttachmentUploaderType;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStepDoc;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStepDocRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Attaches a placeholder against each required document on a newly
 * instantiated step, when {@link ObDemoDataProperties} says to.
 *
 * <h2>Why this is a class and not four lines in the caller</h2>
 *
 * <p>{@code ObJourneyInstantiationService} is the real thing, and the real
 * thing should not grow a demo branch through the middle of it. Everything
 * that is only true of a demo lives here, the caller makes one unconditional
 * call, and deleting this file plus that call is the whole of removing the
 * feature when the upload surface lands.
 *
 * <h2>A placeholder, and it says so in the row</h2>
 *
 * <p>The file name and storage key both carry {@code demo-seed}, so anybody
 * looking at {@code ob_attachments} — or at the undo statement in the demo
 * scripts — can tell these from a document a client actually sent. They point
 * at nothing in object storage: nothing in the current UI downloads a step
 * attachment, and inventing bytes to satisfy a download nobody makes would be
 * a second fiction on top of this one.
 *
 * <h2>Counted, not matched</h2>
 *
 * <p>One row per required checklist entry, because that is exactly what
 * {@code ObJourneyStepLifecycleService#evaluateCompletionGate} counts:
 * {@code ob_journey_step_docs} does not exist, so the gate compares the number
 * of {@code CLEAN} attachments on the step against the number of required rows
 * on its template. Seeding one row per entry keeps this correct if the gate
 * ever starts matching entry by entry.
 */
@Component
class ObDemoStepDocumentSeeder {

    private final ObDemoDataProperties properties;
    private final ObJourneyTemplateStepDocRepository templateStepDocs;
    private final ObAttachmentRepository attachments;
    private final Clock clock;

    ObDemoStepDocumentSeeder(ObDemoDataProperties properties,
                             ObJourneyTemplateStepDocRepository templateStepDocs,
                             ObAttachmentRepository attachments,
                             Clock clock) {
        this.properties = properties;
        this.templateStepDocs = templateStepDocs;
        this.attachments = attachments;
        this.clock = clock;
    }

    /**
     * Satisfies the document gate on one just-cloned step.
     *
     * <p>A no-op when the switch is off, which is the shipped path — the
     * caller does not branch, so there is one place that decides and it is
     * this one.
     *
     * @param templateStepId the step this was cloned from, which is where the
     *                       required-document checklist lives
     * @param stepId         the cloned step the attachments hang off
     * @param ownerUserId    attributed to the step's own owner, or null for an
     *                       unassigned step — {@code ck_ob_attachments_uploader}
     *                       requires a STAFF row to name a user, so an
     *                       unassigned step is skipped rather than attributed
     *                       to somebody who was never involved
     */
    void satisfyRequiredDocuments(Long templateStepId, long stepId, Long ownerUserId) {
        if (!properties.seedsStepDocuments() || templateStepId == null || ownerUserId == null) {
            return;
        }
        List<ObJourneyTemplateStepDoc> required = templateStepDocs
                .findByStepIdOrderBySequenceAsc(templateStepId).stream()
                .filter(ObJourneyTemplateStepDoc::isRequired)
                .toList();

        Instant now = clock.instant();
        for (ObJourneyTemplateStepDoc doc : required) {
            ObAttachment placeholder = new ObAttachment();
            placeholder.setStepId(stepId);
            placeholder.setKind(ObAttachmentKind.SUBMISSION);
            placeholder.setUploadedByType(ObAttachmentUploaderType.STAFF);
            placeholder.setUploadedByUser(ownerUserId);
            placeholder.setFileName("demo-seed-" + doc.getId() + ".pdf");
            placeholder.setContentType("application/pdf");
            // ck_ob_attachments_size wants a positive number and nothing reads
            // it, so this is a plausible one rather than a meaningful one.
            placeholder.setSizeBytes(24576L);
            placeholder.setStorageKey("ob-attachments/steps/" + stepId + "/demo-seed-" + doc.getId() + ".pdf");
            // CLEAN rather than PENDING: the gate counts only CLEAN rows, and
            // no scanner runs in a demo deployment to ever move it off PENDING.
            placeholder.setScanStatus(ObAttachmentScanStatus.CLEAN);
            placeholder.setScannedAt(now);
            attachments.save(placeholder);
        }
    }
}
