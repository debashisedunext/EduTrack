package com.edunext.edutrack.api.feature.onboarding.prereqs;

import com.edunext.edutrack.domain.onboarding.ObAttachment;
import com.edunext.edutrack.domain.onboarding.ObAttachmentRepository;
import com.edunext.edutrack.domain.onboarding.ObPrereqTemplateTask;
import com.edunext.edutrack.domain.onboarding.ObPrereqTemplateTaskDoc;
import com.edunext.edutrack.domain.onboarding.ObPrereqTemplateTaskDocRepository;
import com.edunext.edutrack.domain.onboarding.ObPrereqTemplateTaskRepository;
import com.edunext.edutrack.domain.onboarding.ObPrereqTemplateVersion;
import com.edunext.edutrack.domain.onboarding.ObPrereqTemplateVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * B-124 · the org-wide prerequisites master (OB-14) and its versioning.
 *
 * <h2>The one rule everything else here serves</h2>
 *
 * <p><b>An admin edit never changes what an already-boarded client was
 * asked for.</b> Plan §1.1 #2 makes snapshotting apply to the prerequisites
 * master by name, and the mechanism is the one
 * {@code ObJourneyTemplateService} already uses: a version is edited only
 * while it is a draft, publishing supersedes rather than rewrites, and
 * B-125's per-client instance pins the version it was snapshotted from.
 *
 * <p>So every mutator here goes through {@link #requireDraft()}, and the
 * test is {@code publishedAt == null} — never {@code !isActive}, which
 * would reopen a <i>retired</i> version whose clients are still working
 * through it.
 *
 * <h2>How this differs from the journey template designer, and why</h2>
 *
 * <p>The two are deliberately not symmetrical, and the contract's
 * {@code updateObPrereqTemplateTask} description carries the reasoning at
 * length. In short: a journey template is per-product, so one Admin owns
 * one product's design and composing a draft by add/remove/reorder is
 * enough. This master is <b>org-wide and singular</b> — one draft for the
 * whole organisation, so two Admins editing at once is the normal case.
 * That buys three differences:
 *
 * <ol>
 *   <li>{@link #updateTask} exists, where journey steps have no PATCH.
 *       Delete-plus-re-add would lose the task's position and its
 *       reference documents, and on a flag that decides whether a gate can
 *       ever open it is a heavier operation than the edit it stands in
 *       for.</li>
 *   <li>"One draft at a time" is a database constraint here, not only a
 *       service rule — {@code uq_ob_prereq_template_versions_draft}.</li>
 *   <li>{@link #publish} refuses a draft with no mandatory task, where
 *       {@code ObJourneyTemplateService#publish} refuses one with no steps.
 *       Different emptiness, same argument: a checklist with nothing
 *       mandatory clears its own gate the moment it is instantiated, so
 *       every journey would open at boarding and the gate would look
 *       present while doing nothing.</li>
 * </ol>
 *
 * <h2>What this class does not do</h2>
 *
 * <p>No snapshotting: {@link #activeVersion()} is the read B-125's
 * instantiation calls, and the copying itself belongs to the task that owns
 * the instance tables. No gate evaluation — that is C-118's, and it reads
 * instances rather than this master. No upload: {@link #addTaskDoc} records
 * an {@code attachmentId} the module's shared attachment route has already
 * produced, so the type policy and the AV scan stay where every other
 * upload meets them.
 */
@Service
public class ObPrereqTemplateService {

    private final ObPrereqTemplateVersionRepository versions;
    private final ObPrereqTemplateTaskRepository tasks;
    private final ObPrereqTemplateTaskDocRepository taskDocs;
    private final ObAttachmentRepository attachments;

    public ObPrereqTemplateService(ObPrereqTemplateVersionRepository versions,
                                   ObPrereqTemplateTaskRepository tasks,
                                   ObPrereqTemplateTaskDocRepository taskDocs,
                                   ObAttachmentRepository attachments) {
        this.versions = versions;
        this.tasks = tasks;
        this.taskDocs = taskDocs;
        this.attachments = attachments;
    }

    // ── reads ────────────────────────────────────────────────────────────

    /**
     * {@code getObPrereqTemplate} with no {@code version} parameter — the
     * active version, which is what OB-14 opens on and what B-125
     * snapshots.
     *
     * <p><b>Answers empty rather than throwing when nothing is published
     * yet.</b> A fresh organisation has no active version and that is a
     * legitimate state, not an error: OB-14's own first visit is somebody
     * arriving to author one. The caller decides what to do with it —
     * the controller answers 404, B-125 refuses to board a client against
     * a master nobody has published.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<ObPrereqTemplateVersion> activeVersion() {
        return versions.findByIsActiveTrue();
    }

    /**
     * {@code getObPrereqTemplate?version=N} — an older version, read back
     * so a client boarded months ago can be shown the checklist they were
     * actually asked for. The same question {@code ObJourney.templateVersion}
     * raises on the journey side.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<ObPrereqTemplateVersion> versionNumbered(int version) {
        return versions.findByVersion(version);
    }

    /** The editable version, if one is open. At most one exists. */
    @Transactional(readOnly = true)
    public java.util.Optional<ObPrereqTemplateVersion> draft() {
        return versions.findByPublishedAtIsNull();
    }

    @Transactional(readOnly = true)
    public List<ObPrereqTemplateTask> tasksOf(long versionId) {
        return tasks.findByVersionIdOrderBySequenceAsc(versionId);
    }

    @Transactional(readOnly = true)
    public List<ObPrereqTemplateTaskDoc> docsOf(long templateTaskId) {
        return taskDocs.findByTemplateTaskIdOrderBySequenceAsc(templateTaskId);
    }

    /**
     * Every document on every one of these tasks, in one query.
     *
     * <p>The contract does not paginate a version's tasks — "the wizard
     * snapshots the whole thing, the OB-14 editor draws the whole thing"
     * — so the response always carries the full set with its documents
     * nested. Fetched per task, that is the N+1 the whole read is most
     * likely to become.
     */
    @Transactional(readOnly = true)
    public Map<Long, List<ObPrereqTemplateTaskDoc>> docsByTask(List<Long> templateTaskIds) {
        Map<Long, List<ObPrereqTemplateTaskDoc>> byTask = new HashMap<>();
        if (templateTaskIds.isEmpty()) {
            return byTask;
        }
        for (ObPrereqTemplateTaskDoc doc : taskDocs.findByTemplateTaskIdInOrderBySequenceAsc(templateTaskIds)) {
            byTask.computeIfAbsent(doc.getTemplateTaskId(), id -> new java.util.ArrayList<>()).add(doc);
        }
        return byTask;
    }

    // ── the version lifecycle ────────────────────────────────────────────

    /**
     * {@code beginObPrereqTemplateRevision} — clones the active version's
     * tasks and their reference documents into a new, editable draft one
     * version higher. The source version is read and never written, so
     * every client already boarded against it keeps rendering exactly the
     * checklist they agreed to.
     *
     * <p><b>Also the way the very first version is born.</b> With nothing
     * published yet there is nothing to clone, and this opens an empty
     * draft at version 1 rather than failing — the contract gives OB-14 no
     * separate "create" operation, because unlike a journey template there
     * is no per-product identity to create one <i>of</i>.
     *
     * @throws PrereqDraftAlreadyExistsException if a draft is already open.
     *         409, and the reason is not tidiness: two drafts would each be
     *         "the next version" and publishing either would silently
     *         discard the other's work. The database refuses it too.
     */
    @Transactional
    public ObPrereqTemplateVersion beginRevision(long editorUserId) {
        versions.findByPublishedAtIsNull().ifPresent(existing -> {
            throw new PrereqDraftAlreadyExistsException(existing.getVersion());
        });

        int nextVersion = versions.findTopByOrderByVersionDesc()
                .map(ObPrereqTemplateVersion::getVersion)
                .orElse(0) + 1;

        ObPrereqTemplateVersion draft = new ObPrereqTemplateVersion();
        draft.setVersion(nextVersion);
        draft.setActive(false);
        draft.setCreatedBy(editorUserId);
        ObPrereqTemplateVersion saved = versions.save(draft);

        versions.findByIsActiveTrue()
                .ifPresent(active -> cloneTasks(active.getId(), saved.getId()));
        return saved;
    }

    /**
     * One pass, where {@code ObJourneyTemplateService#cloneSteps} needs
     * two: prerequisites have no dependency edges to re-point once the
     * clones have ids, because they have no dependency model at all.
     */
    private void cloneTasks(long sourceVersionId, long targetVersionId) {
        for (ObPrereqTemplateTask source : tasks.findByVersionIdOrderBySequenceAsc(sourceVersionId)) {
            ObPrereqTemplateTask clone = new ObPrereqTemplateTask();
            clone.setVersionId(targetVersionId);
            clone.setSequence(source.getSequence());
            clone.setTitle(source.getTitle());
            clone.setDescription(source.getDescription());
            clone.setTatDays(source.getTatDays());
            // Carried forward explicitly rather than left to the column
            // default, on C-102's own reasoning about a cloned step item:
            // the default happens to match most tasks, which is exactly the
            // trap. A revision that silently re-made an optional task
            // mandatory would re-lock a gate, and nothing about a
            // clone-on-revise would surface that to whoever asked for it.
            clone.setMandatory(source.isMandatory());
            clone.setActive(source.isActive());
            ObPrereqTemplateTask savedClone = tasks.save(clone);

            for (ObPrereqTemplateTaskDoc doc : taskDocs.findByTemplateTaskIdOrderBySequenceAsc(source.getId())) {
                // The caption is cloned; the attachment is shared, not
                // copied. Two versions citing one specimen form is correct,
                // and re-uploading would duplicate the object in storage
                // for no reader's benefit.
                //
                // **So a cloned doc row names this version's task while its
                // attachment still names the source version's, and that is
                // the design rather than a leak.** The two columns answer
                // different questions: the attachment's owner records where
                // a file was first uploaded, the doc row records where it
                // is listed. `addTaskDoc` requires them to agree because a
                // fresh upload is always uploaded against the task it is
                // for; a clone is the one case where they legitimately
                // differ.
                //
                // It is also what keeps §4's cascade safe. The attachment
                // is owned by a task on a PUBLISHED version, and published
                // versions' tasks are never deleted — they are retired with
                // the version. So deleting this cloned task takes its
                // caption and leaves the file standing for the version that
                // still needs it.
                ObPrereqTemplateTaskDoc docClone = new ObPrereqTemplateTaskDoc();
                docClone.setTemplateTaskId(savedClone.getId());
                docClone.setAttachmentId(doc.getAttachmentId());
                docClone.setLabel(doc.getLabel());
                docClone.setSequence(doc.getSequence());
                taskDocs.save(docClone);
            }
        }
    }

    /**
     * {@code publishObPrereqTemplate} — the draft becomes the active
     * version, and the version it supersedes is retired in the same
     * transaction and in that order, so the unique index over one active
     * row never sees two at once.
     *
     * <p><b>Clients already boarded are untouched</b>, including those
     * whose gate is still locked. Migrating an in-flight client onto a
     * newer checklist is tempting — they have not finished the old one —
     * and it is the wrong call: a task they had already submitted would be
     * replaced by one they have never seen, and a mandatory addition would
     * re-lock a gate that had opened.
     *
     * @throws PrereqNoDraftException 409, nothing to publish.
     * @throws PrereqTemplateHasNoMandatoryTaskException 422 — see the class
     *         javadoc for why an all-optional checklist is a gate that is
     *         present and does nothing.
     */
    @Transactional
    public ObPrereqTemplateVersion publish(long publishedBy) {
        ObPrereqTemplateVersion draft = requireDraft();

        if (tasks.countByVersionIdAndIsMandatoryTrue(draft.getId()) == 0) {
            throw new PrereqTemplateHasNoMandatoryTaskException(draft.getVersion());
        }

        versions.findByIsActiveTrue().ifPresent(current -> {
            current.setActive(false);
            versions.saveAndFlush(current);
        });

        draft.setActive(true);
        draft.setPublishedBy(publishedBy);
        draft.setPublishedAt(Instant.now());
        return versions.saveAndFlush(draft);
    }

    // ── the draft's tasks ────────────────────────────────────────────────

    /** {@code addObPrereqTemplateTask} — appended at the end of the draft. */
    @Transactional
    public ObPrereqTemplateTask addTask(String title, String description, int tatDays,
                                        boolean mandatory, boolean active) {
        ObPrereqTemplateVersion draft = requireDraft();

        ObPrereqTemplateTask task = new ObPrereqTemplateTask();
        task.setVersionId(draft.getId());
        task.setSequence(nextTaskSequence(draft.getId()));
        task.setTitle(title);
        task.setDescription(description);
        task.setTatDays(tatDays);
        task.setMandatory(mandatory);
        task.setActive(active);
        return tasks.save(task);
    }

    /**
     * {@code updateObPrereqTemplateTask} — a real PATCH, where the journey
     * designer has none. The class javadoc has the reasoning; the guard is
     * the same one every mutator here applies, so a task belonging to a
     * published version is refused rather than rewritten under a boarded
     * client.
     *
     * <p>The request is a whole representation, so every field is written
     * on every save. That is safe here in a way it was not for B-103's
     * consent stamp: nothing on this row records <i>when</i> somebody
     * decided something, so rewriting an unchanged value loses nothing.
     */
    @Transactional
    public ObPrereqTemplateTask updateTask(long templateTaskId, String title, String description,
                                           int tatDays, boolean mandatory, boolean active) {
        ObPrereqTemplateTask task = requireEditableTask(templateTaskId);
        task.setTitle(title);
        task.setDescription(description);
        task.setTatDays(tatDays);
        task.setMandatory(mandatory);
        task.setActive(active);
        return tasks.save(task);
    }

    /**
     * {@code removeObPrereqTemplateTask}. Reference documents on the task
     * go with it, and so do their attachment rows — the migration's §4
     * cascade, which is why this needs no explicit cleanup and why the
     * cascade only ever fires on draft content.
     *
     * <p>No {@code dependentStepIds} conflict, unlike
     * {@code removeObJourneyTemplateStep}: nothing points at a prerequisite
     * task, because there is no dependency graph to re-point.
     *
     * <p>The remaining tasks are <b>not</b> re-sequenced. Positions stay
     * sparse until the next explicit reorder, which is what
     * {@code uq_ob_prereq_template_tasks_seq} tolerates and what keeps a
     * delete from silently renumbering rows an open editor is holding.
     */
    @Transactional
    public void removeTask(long templateTaskId) {
        tasks.delete(requireEditableTask(templateTaskId));
    }

    /**
     * {@code reorderObPrereqTemplateTasks} — the whole set, in the order
     * wanted. A partial list is refused rather than silently reordered
     * around tasks the caller could not see: positions only mean something
     * against the complete set.
     *
     * <p><b>Two passes through negative placeholders, not one</b>, and this
     * is not a style choice — {@code ObJourneyTemplateService#reorderSteps}
     * found it first. A single pass can ask MySQL to set some task's
     * {@code sequence} to a value another, not-yet-updated task still
     * holds (swapping positions 1 and 2 is the minimal case), and
     * {@code uq_ob_prereq_template_tasks_seq} refuses that collision
     * mid-transaction even though the two writes never conflict once both
     * have landed.
     */
    @Transactional
    public void reorderTasks(List<Long> orderedTaskIds) {
        ObPrereqTemplateVersion draft = requireDraft();

        List<ObPrereqTemplateTask> current = tasks.findByVersionIdOrderBySequenceAsc(draft.getId());
        Set<Long> currentIds = new LinkedHashSet<>();
        for (ObPrereqTemplateTask task : current) {
            currentIds.add(task.getId());
        }

        Set<Long> requestedIds = new LinkedHashSet<>(orderedTaskIds);
        if (requestedIds.size() != orderedTaskIds.size()) {
            throw new PrereqTaskReorderMismatchException("the same task id appears more than once");
        }
        if (!requestedIds.equals(currentIds)) {
            throw new PrereqTaskReorderMismatchException(
                    "the given ids are not exactly the draft's current task set");
        }

        Map<Long, ObPrereqTemplateTask> byId = new HashMap<>();
        for (ObPrereqTemplateTask task : current) {
            byId.put(task.getId(), task);
        }

        int placeholder = 1;
        for (Long taskId : orderedTaskIds) {
            byId.get(taskId).setSequence(-placeholder);
            tasks.save(byId.get(taskId));
            placeholder++;
        }
        tasks.flush();

        int sequence = 1;
        for (Long taskId : orderedTaskIds) {
            byId.get(taskId).setSequence(sequence);
            tasks.save(byId.get(taskId));
            sequence++;
        }
        tasks.flush();
    }

    // ── reference documents ──────────────────────────────────────────────

    /**
     * {@code addObPrereqTemplateTaskDoc} — records an attachment the
     * module's shared upload route has already produced, against a draft
     * task, under the admin's own caption.
     *
     * <p><b>This method is where the doc row and the attachment are held
     * to the same task.</b> The migration's §5 explains why the composite
     * foreign key that would have enforced it was not worth its cost; the
     * consequence is that the check lives here, so an attachment belonging
     * to another task — or to a client, a step or a sign-off — is refused
     * rather than listed under a task it does not belong to.
     *
     * @throws PrereqAttachmentNotOwnedByTaskException if the attachment is
     *         not owned by this task, or is not a {@code REFERENCE}.
     */
    @Transactional
    public ObPrereqTemplateTaskDoc addTaskDoc(long templateTaskId, String label, long attachmentId) {
        ObPrereqTemplateTask task = requireEditableTask(templateTaskId);

        ObAttachment attachment = attachments.findById(attachmentId)
                .orElseThrow(() -> new PrereqAttachmentNotOwnedByTaskException(
                        attachmentId, templateTaskId, "no such attachment"));
        if (!Long.valueOf(templateTaskId).equals(attachment.getPrereqTemplateTaskId())) {
            throw new PrereqAttachmentNotOwnedByTaskException(attachmentId, templateTaskId,
                    "the attachment names a different owner");
        }
        if (attachment.getKind() != com.edunext.edutrack.domain.onboarding.ObAttachmentKind.REFERENCE) {
            throw new PrereqAttachmentNotOwnedByTaskException(attachmentId, templateTaskId,
                    "a master document is a REFERENCE; " + attachment.getKind()
                            + " is what a client sends back");
        }

        ObPrereqTemplateTaskDoc doc = new ObPrereqTemplateTaskDoc();
        doc.setTemplateTaskId(task.getId());
        doc.setAttachmentId(attachmentId);
        doc.setLabel(label);
        doc.setSequence(nextDocSequence(templateTaskId));
        return taskDocs.save(doc);
    }

    /** {@code removeObPrereqTemplateTaskDoc}. */
    @Transactional
    public void removeTaskDoc(long docId) {
        ObPrereqTemplateTaskDoc doc = taskDocs.findById(docId)
                .orElseThrow(() -> new PrereqTemplateTaskDocNotFoundException(docId));
        requireEditableTask(doc.getTemplateTaskId());
        taskDocs.delete(doc);
    }

    // ── guards ───────────────────────────────────────────────────────────

    /**
     * @throws PrereqNoDraftException 409. Editing an active version in
     *         place would change what an in-flight client is being asked
     *         for, which is the whole thing versioning exists to prevent.
     */
    private ObPrereqTemplateVersion requireDraft() {
        return versions.findByPublishedAtIsNull().orElseThrow(PrereqNoDraftException::new);
    }

    /**
     * A task is editable exactly while its version has never been
     * published. {@code publishedAt == null}, never {@code !isActive} — see
     * {@link ObPrereqTemplateVersion}'s javadoc.
     */
    private ObPrereqTemplateTask requireEditableTask(long templateTaskId) {
        ObPrereqTemplateTask task = tasks.findById(templateTaskId)
                .orElseThrow(() -> new PrereqTemplateTaskNotFoundException(templateTaskId));
        ObPrereqTemplateVersion version = versions.findById(task.getVersionId())
                .orElseThrow(() -> new IllegalStateException(
                        "prerequisite task " + templateTaskId + " names version "
                                + task.getVersionId() + ", which does not exist"));
        if (!version.isDraft()) {
            throw new PrereqTaskNotEditableException(templateTaskId, version.getVersion());
        }
        return task;
    }

    private int nextTaskSequence(long versionId) {
        return tasks.findTopByVersionIdOrderBySequenceDesc(versionId)
                .map(t -> t.getSequence() + 1)
                .orElse(1);
    }

    private int nextDocSequence(long templateTaskId) {
        return taskDocs.findTopByTemplateTaskIdOrderBySequenceDesc(templateTaskId)
                .map(d -> d.getSequence() + 1)
                .orElse(1);
    }
}
