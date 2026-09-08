package com.edunext.edutrack.api.feature.onboarding.prereqs;

import com.edunext.edutrack.domain.identity.UserRepository;
import com.edunext.edutrack.domain.onboarding.ObAttachment;
import com.edunext.edutrack.domain.onboarding.ObAttachmentRepository;
import com.edunext.edutrack.domain.onboarding.ObPrereqTemplateVersion;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * B-124 · builds the OB-14 response from the three tables plus the two it
 * borrows from — {@code users} for {@code publishedBy}'s display name and
 * {@code ob_attachments} for each reference document's file name and size.
 *
 * <p>A class of its own rather than private methods on the controller,
 * because two controllers compose the same shapes: the template routes
 * answer whole versions and the task routes answer single tasks, and both
 * have to nest documents identically. The alternative was the controller
 * holding three repositories, which is what
 * {@code ObJourneyTemplateController}'s own javadoc says it exists to
 * avoid.
 *
 * <p><b>Two queries for a whole version's documents, not one per task.</b>
 * The contract does not paginate a version's tasks — every caller needs all
 * of them — so the per-task fetch would be the N+1 this read is most likely
 * to become.
 */
@Component
class ObPrereqTemplateAssembler {

    private final ObPrereqTemplateService service;
    private final ObAttachmentRepository attachments;
    private final UserRepository users;

    ObPrereqTemplateAssembler(ObPrereqTemplateService service,
                              ObAttachmentRepository attachments,
                              UserRepository users) {
        this.service = service;
        this.attachments = attachments;
        this.users = users;
    }

    ObPrereqTemplateDtos.ObPrereqTemplate template(ObPrereqTemplateVersion version) {
        List<com.edunext.edutrack.domain.onboarding.ObPrereqTemplateTask> taskRows =
                service.tasksOf(version.getId());

        List<Long> taskIds = taskRows.stream()
                .map(com.edunext.edutrack.domain.onboarding.ObPrereqTemplateTask::getId)
                .toList();
        Map<Long, List<com.edunext.edutrack.domain.onboarding.ObPrereqTemplateTaskDoc>> docRows =
                service.docsByTask(taskIds);

        Map<Long, ObAttachment> filesById = filesFor(docRows.values().stream()
                .flatMap(List::stream)
                .map(com.edunext.edutrack.domain.onboarding.ObPrereqTemplateTaskDoc::getAttachmentId)
                .distinct()
                .toList());

        List<ObPrereqTemplateDtos.ObPrereqTemplateTask> tasks = new ArrayList<>();
        for (com.edunext.edutrack.domain.onboarding.ObPrereqTemplateTask task : taskRows) {
            tasks.add(ObPrereqTemplateDtos.ObPrereqTemplateTask.of(task,
                    docsOf(docRows.getOrDefault(task.getId(), List.of()), filesById)));
        }

        return ObPrereqTemplateDtos.ObPrereqTemplate.of(version, publishedBy(version), tasks);
    }

    ObPrereqTemplateDtos.ObPrereqTemplateTask task(
            com.edunext.edutrack.domain.onboarding.ObPrereqTemplateTask task) {

        List<com.edunext.edutrack.domain.onboarding.ObPrereqTemplateTaskDoc> docRows =
                service.docsOf(task.getId());
        Map<Long, ObAttachment> filesById = filesFor(docRows.stream()
                .map(com.edunext.edutrack.domain.onboarding.ObPrereqTemplateTaskDoc::getAttachmentId)
                .distinct()
                .toList());
        return ObPrereqTemplateDtos.ObPrereqTemplateTask.of(task, docsOf(docRows, filesById));
    }

    ObPrereqTemplateDtos.ObPrereqTemplateTaskDoc doc(
            com.edunext.edutrack.domain.onboarding.ObPrereqTemplateTaskDoc doc) {

        return one(doc, filesFor(List.of(doc.getAttachmentId())));
    }

    private List<ObPrereqTemplateDtos.ObPrereqTemplateTaskDoc> docsOf(
            List<com.edunext.edutrack.domain.onboarding.ObPrereqTemplateTaskDoc> docRows,
            Map<Long, ObAttachment> filesById) {

        List<ObPrereqTemplateDtos.ObPrereqTemplateTaskDoc> docs = new ArrayList<>();
        for (com.edunext.edutrack.domain.onboarding.ObPrereqTemplateTaskDoc doc : docRows) {
            docs.add(one(doc, filesById));
        }
        return docs;
    }

    private ObPrereqTemplateDtos.ObPrereqTemplateTaskDoc one(
            com.edunext.edutrack.domain.onboarding.ObPrereqTemplateTaskDoc doc,
            Map<Long, ObAttachment> filesById) {

        ObAttachment file = filesById.get(doc.getAttachmentId());
        return ObPrereqTemplateDtos.ObPrereqTemplateTaskDoc.of(doc,
                file == null ? null : file.getFileName(),
                file == null ? null : file.getSizeBytes());
    }

    private Map<Long, ObAttachment> filesFor(List<Long> attachmentIds) {
        Map<Long, ObAttachment> byId = new HashMap<>();
        if (attachmentIds.isEmpty()) {
            return byId;
        }
        for (ObAttachment attachment : attachments.findAllById(attachmentIds)) {
            byId.put(attachment.getId(), attachment);
        }
        return byId;
    }

    /**
     * Null when nothing has been published, which is the contract's own
     * {@code publishedBy: UserRef | null} — and also when the publishing
     * user has since been removed, where naming the id with no display name
     * would read as a bug on a screen that only ever shows the name.
     */
    private ObPrereqTemplateDtos.UserRef publishedBy(ObPrereqTemplateVersion version) {
        if (version.getPublishedBy() == null) {
            return null;
        }
        return users.findById(version.getPublishedBy())
                .map(user -> ObPrereqTemplateDtos.UserRef.of(user.getId(), user.getFullName()))
                .orElse(null);
    }
}
