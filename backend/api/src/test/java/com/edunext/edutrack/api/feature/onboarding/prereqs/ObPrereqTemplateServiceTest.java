package com.edunext.edutrack.api.feature.onboarding.prereqs;

import com.edunext.edutrack.domain.onboarding.ObAttachment;
import com.edunext.edutrack.domain.onboarding.ObAttachmentKind;
import com.edunext.edutrack.domain.onboarding.ObAttachmentRepository;
import com.edunext.edutrack.domain.onboarding.ObPrereqTemplateTask;
import com.edunext.edutrack.domain.onboarding.ObPrereqTemplateTaskDoc;
import com.edunext.edutrack.domain.onboarding.ObPrereqTemplateTaskDocRepository;
import com.edunext.edutrack.domain.onboarding.ObPrereqTemplateTaskRepository;
import com.edunext.edutrack.domain.onboarding.ObPrereqTemplateVersion;
import com.edunext.edutrack.domain.onboarding.ObPrereqTemplateVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * B-124 · {@link ObPrereqTemplateService}. The four repositories are mocked
 * with Mockito but each is backed by a small in-memory map rather than
 * per-call stubs, on {@code ObJourneyTemplateServiceTest}'s own reasoning:
 * {@code beginRevision} saves a cloned task and then immediately queries its
 * documents by the id just assigned, which a fixed-return stub cannot model.
 *
 * <p><b>The tests this file is measured by are the three that encode
 * guarantees rather than behaviour:</b>
 *
 * <ul>
 *   <li>{@code editingARetiredVersionIsRefused} — guarding on
 *       {@code !isActive} rather than {@code publishedAt == null} would
 *       reopen a superseded version to editing, rewriting the checklist a
 *       client already agreed to.</li>
 *   <li>{@code publishingAnAllOptionalChecklistIsRefused} — an all-optional
 *       master clears its own gate at every boarding, so the gate would be
 *       present and do nothing.</li>
 *   <li>{@code aRevisionCarriesTheMandatoryFlagForward} — the clone-time
 *       trap C-102 found on step items: the column default happens to match
 *       most rows, so a lost flag looks like normal data until a gate
 *       re-locks.</li>
 * </ul>
 *
 * <p>The database holds the one-draft and one-active rules too, by unique
 * index over generated columns — {@code V20260908_1100} — and those were
 * verified against MySQL 8.4 directly. What is asserted here is that the
 * service refuses them with the right exception rather than leaving the
 * caller a raw constraint violation.
 */
class ObPrereqTemplateServiceTest {

    private static final long ADMIN = 7L;

    private final Map<Long, ObPrereqTemplateVersion> versionRows = new LinkedHashMap<>();
    private final Map<Long, ObPrereqTemplateTask> taskRows = new LinkedHashMap<>();
    private final Map<Long, ObPrereqTemplateTaskDoc> docRows = new LinkedHashMap<>();
    private final Map<Long, ObAttachment> attachmentRows = new LinkedHashMap<>();

    private final AtomicLong versionIds = new AtomicLong();
    private final AtomicLong taskIds = new AtomicLong();
    private final AtomicLong docIds = new AtomicLong();
    private final AtomicLong attachmentIds = new AtomicLong();

    private final ObPrereqTemplateVersionRepository versions = mock(ObPrereqTemplateVersionRepository.class);
    private final ObPrereqTemplateTaskRepository tasks = mock(ObPrereqTemplateTaskRepository.class);
    private final ObPrereqTemplateTaskDocRepository taskDocs = mock(ObPrereqTemplateTaskDocRepository.class);
    private final ObAttachmentRepository attachments = mock(ObAttachmentRepository.class);

    private final ObPrereqTemplateService service =
            new ObPrereqTemplateService(versions, tasks, taskDocs, attachments);

    @BeforeEach
    void wireFakes() {
        lenient().when(versions.save(any())).thenAnswer(inv -> {
            ObPrereqTemplateVersion v = inv.getArgument(0);
            if (v.getId() == null) {
                v.setId(versionIds.incrementAndGet());
            }
            versionRows.put(v.getId(), v);
            return v;
        });
        lenient().when(versions.saveAndFlush(any())).thenAnswer(inv -> versions.save(inv.getArgument(0)));
        lenient().when(versions.findById(any())).thenAnswer(inv ->
                Optional.ofNullable(versionRows.get(inv.<Long>getArgument(0))));
        lenient().when(versions.findByIsActiveTrue()).thenAnswer(inv ->
                versionRows.values().stream().filter(ObPrereqTemplateVersion::isActive).findFirst());
        lenient().when(versions.findByPublishedAtIsNull()).thenAnswer(inv ->
                versionRows.values().stream().filter(ObPrereqTemplateVersion::isDraft).findFirst());
        lenient().when(versions.findByVersion(org.mockito.ArgumentMatchers.anyInt())).thenAnswer(inv ->
                versionRows.values().stream()
                        .filter(v -> v.getVersion() == inv.<Integer>getArgument(0))
                        .findFirst());
        lenient().when(versions.findTopByOrderByVersionDesc()).thenAnswer(inv ->
                versionRows.values().stream().max(Comparator.comparingInt(ObPrereqTemplateVersion::getVersion)));

        lenient().when(tasks.save(any())).thenAnswer(inv -> {
            ObPrereqTemplateTask t = inv.getArgument(0);
            if (t.getId() == null) {
                t.setId(taskIds.incrementAndGet());
            }
            taskRows.put(t.getId(), t);
            return t;
        });
        lenient().when(tasks.findById(any())).thenAnswer(inv ->
                Optional.ofNullable(taskRows.get(inv.<Long>getArgument(0))));
        lenient().doAnswer(inv -> taskRows.remove(((ObPrereqTemplateTask) inv.getArgument(0)).getId()))
                .when(tasks).delete(any());
        lenient().when(tasks.findByVersionIdOrderBySequenceAsc(any())).thenAnswer(inv ->
                tasksFor(inv.getArgument(0)));
        lenient().when(tasks.findTopByVersionIdOrderBySequenceDesc(any())).thenAnswer(inv ->
                tasksFor(inv.<Long>getArgument(0)).stream().reduce((a, b) -> b));
        lenient().when(tasks.countByVersionIdAndIsMandatoryTrue(any())).thenAnswer(inv ->
                tasksFor(inv.<Long>getArgument(0)).stream().filter(ObPrereqTemplateTask::isMandatory).count());
        lenient().when(tasks.countByVersionId(any())).thenAnswer(inv ->
                (long) tasksFor(inv.<Long>getArgument(0)).size());

        lenient().when(taskDocs.save(any())).thenAnswer(inv -> {
            ObPrereqTemplateTaskDoc d = inv.getArgument(0);
            if (d.getId() == null) {
                d.setId(docIds.incrementAndGet());
            }
            docRows.put(d.getId(), d);
            return d;
        });
        lenient().when(taskDocs.findById(any())).thenAnswer(inv ->
                Optional.ofNullable(docRows.get(inv.<Long>getArgument(0))));
        lenient().doAnswer(inv -> docRows.remove(((ObPrereqTemplateTaskDoc) inv.getArgument(0)).getId()))
                .when(taskDocs).delete(any());
        lenient().when(taskDocs.findByTemplateTaskIdOrderBySequenceAsc(any())).thenAnswer(inv ->
                docsFor(inv.getArgument(0)));
        lenient().when(taskDocs.findTopByTemplateTaskIdOrderBySequenceDesc(any())).thenAnswer(inv ->
                docsFor(inv.<Long>getArgument(0)).stream().reduce((a, b) -> b));
        lenient().when(taskDocs.findByTemplateTaskIdInOrderBySequenceAsc(any())).thenAnswer(inv -> {
            List<Long> ids = inv.getArgument(0);
            return docRows.values().stream()
                    .filter(d -> ids.contains(d.getTemplateTaskId()))
                    .sorted(Comparator.comparingInt(ObPrereqTemplateTaskDoc::getSequence))
                    .toList();
        });

        lenient().when(attachments.findById(any())).thenAnswer(inv ->
                Optional.ofNullable(attachmentRows.get(inv.<Long>getArgument(0))));
    }

    private List<ObPrereqTemplateTask> tasksFor(Long versionId) {
        return taskRows.values().stream()
                .filter(t -> t.getVersionId().equals(versionId))
                .sorted(Comparator.comparingInt(ObPrereqTemplateTask::getSequence))
                .toList();
    }

    private List<ObPrereqTemplateTaskDoc> docsFor(Long taskId) {
        return docRows.values().stream()
                .filter(d -> d.getTemplateTaskId().equals(taskId))
                .sorted(Comparator.comparingInt(ObPrereqTemplateTaskDoc::getSequence))
                .toList();
    }

    /** A REFERENCE attachment already owned by the given task. */
    private long referenceAttachment(long templateTaskId) {
        ObAttachment attachment = new ObAttachment();
        attachment.setId(attachmentIds.incrementAndGet());
        attachment.setPrereqTemplateTaskId(templateTaskId);
        attachment.setKind(ObAttachmentKind.REFERENCE);
        attachmentRows.put(attachment.getId(), attachment);
        return attachment.getId();
    }

    private ObPrereqTemplateVersion publishedMasterWithOneMandatoryTask() {
        service.beginRevision(ADMIN);
        service.addTask("PAN card copy", "A scan of the company PAN", 3, true, true);
        return service.publish(ADMIN);
    }

    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("the version lifecycle")
    class VersionLifecycle {

        @Test
        @DisplayName("the first revision opens version 1 with nothing to clone")
        void theFirstRevisionOpensVersionOne() {
            ObPrereqTemplateVersion draft = service.beginRevision(ADMIN);

            assertThat(draft.getVersion()).isEqualTo(1);
            assertThat(draft.isDraft()).isTrue();
            assertThat(draft.isActive()).isFalse();
            assertThat(service.tasksOf(draft.getId())).isEmpty();
        }

        @Test
        @DisplayName("a second draft is refused while one is open")
        void aSecondDraftIsRefused() {
            service.beginRevision(ADMIN);

            assertThatThrownBy(() -> service.beginRevision(ADMIN))
                    .isInstanceOf(PrereqDraftAlreadyExistsException.class)
                    .hasMessageContaining("already an open draft");
        }

        @Test
        @DisplayName("publishing retires the version it supersedes, in the same call")
        void publishingRetiresThePreviousVersion() {
            ObPrereqTemplateVersion first = publishedMasterWithOneMandatoryTask();

            service.beginRevision(ADMIN);
            ObPrereqTemplateVersion second = service.publish(ADMIN);

            assertThat(second.getVersion()).isEqualTo(2);
            assertThat(second.isActive()).isTrue();
            assertThat(first.isActive()).isFalse();
            assertThat(first.getPublishedAt()).isNotNull();
            assertThat(service.activeVersion()).contains(second);
        }

        @Test
        @DisplayName("publishing records who did it")
        void publishingRecordsWhoDidIt() {
            ObPrereqTemplateVersion published = publishedMasterWithOneMandatoryTask();

            assertThat(published.getPublishedBy()).isEqualTo(ADMIN);
            assertThat(published.getPublishedAt()).isNotNull();
            assertThat(published.isDraft()).isFalse();
        }

        @Test
        @DisplayName("publishing with no draft open is refused")
        void publishingWithNoDraftIsRefused() {
            assertThatThrownBy(() -> service.publish(ADMIN))
                    .isInstanceOf(PrereqNoDraftException.class);
        }

        /**
         * The gate is a hard one (plan §5.3). A checklist with nothing
         * mandatory satisfies itself the moment it is instantiated, so
         * every journey would open at boarding while the gate still
         * appeared to be there.
         */
        @Test
        @DisplayName("publishing an all-optional checklist is refused")
        void publishingAnAllOptionalChecklistIsRefused() {
            service.beginRevision(ADMIN);
            service.addTask("Office photographs", null, 5, false, true);

            assertThatThrownBy(() -> service.publish(ADMIN))
                    .isInstanceOf(PrereqTemplateHasNoMandatoryTaskException.class)
                    .hasMessageContaining("no mandatory task");
        }

        @Test
        @DisplayName("an empty draft cannot be published either")
        void anEmptyDraftCannotBePublished() {
            service.beginRevision(ADMIN);

            assertThatThrownBy(() -> service.publish(ADMIN))
                    .isInstanceOf(PrereqTemplateHasNoMandatoryTaskException.class);
        }
    }

    @Nested
    @DisplayName("revision cloning")
    class Cloning {

        @Test
        @DisplayName("a revision clones the active version's tasks, source untouched")
        void aRevisionClonesTheActiveVersion() {
            ObPrereqTemplateVersion published = publishedMasterWithOneMandatoryTask();
            List<ObPrereqTemplateTask> before = service.tasksOf(published.getId());

            ObPrereqTemplateVersion draft = service.beginRevision(ADMIN);
            List<ObPrereqTemplateTask> cloned = service.tasksOf(draft.getId());

            assertThat(cloned).hasSize(1);
            assertThat(cloned.get(0).getTitle()).isEqualTo("PAN card copy");
            assertThat(cloned.get(0).getId()).isNotEqualTo(before.get(0).getId());
            // The source version is read, never written.
            assertThat(service.tasksOf(published.getId()))
                    .singleElement()
                    .extracting(ObPrereqTemplateTask::getId)
                    .isEqualTo(before.get(0).getId());
        }

        /**
         * C-102 found this on step items and the trap is identical: the
         * column default happens to match most rows, so a flag lost at
         * clone time looks like ordinary data — until a gate that had
         * opened re-locks, or one that should hold does not.
         */
        @Test
        @DisplayName("a revision carries the mandatory flag forward rather than defaulting it")
        void aRevisionCarriesTheMandatoryFlagForward() {
            service.beginRevision(ADMIN);
            service.addTask("PAN card copy", null, 3, true, true);
            service.addTask("Office photographs", null, 5, false, true);
            service.publish(ADMIN);

            ObPrereqTemplateVersion draft = service.beginRevision(ADMIN);

            assertThat(service.tasksOf(draft.getId()))
                    .extracting(ObPrereqTemplateTask::getTitle, ObPrereqTemplateTask::isMandatory)
                    .containsExactly(
                            org.assertj.core.api.Assertions.tuple("PAN card copy", true),
                            org.assertj.core.api.Assertions.tuple("Office photographs", false));
        }

        /**
         * The caption is cloned and the file is shared — re-uploading would
         * duplicate the object in storage for no reader's benefit. So a
         * cloned doc row names the new draft's task while the attachment
         * still names the version it was uploaded against, which is the one
         * place those two columns legitimately differ.
         */
        @Test
        @DisplayName("a revision clones captions and shares the underlying file")
        void aRevisionClonesCaptionsAndSharesTheFile() {
            service.beginRevision(ADMIN);
            ObPrereqTemplateTask task = service.addTask("PAN card copy", null, 3, true, true);
            long attachmentId = referenceAttachment(task.getId());
            service.addTaskDoc(task.getId(), "Specimen board resolution", attachmentId);
            service.publish(ADMIN);

            ObPrereqTemplateVersion draft = service.beginRevision(ADMIN);
            long clonedTaskId = service.tasksOf(draft.getId()).get(0).getId();
            List<ObPrereqTemplateTaskDoc> clonedDocs = service.docsOf(clonedTaskId);

            assertThat(clonedDocs).singleElement().satisfies(doc -> {
                assertThat(doc.getLabel()).isEqualTo("Specimen board resolution");
                assertThat(doc.getAttachmentId()).isEqualTo(attachmentId);
                assertThat(doc.getTemplateTaskId()).isEqualTo(clonedTaskId);
            });
            // The file is still owned by the task it was uploaded against.
            assertThat(attachmentRows.get(attachmentId).getPrereqTemplateTaskId())
                    .isEqualTo(task.getId());
        }
    }

    @Nested
    @DisplayName("the draft's tasks")
    class Tasks {

        @Test
        @DisplayName("tasks are appended in order")
        void tasksAreAppendedInOrder() {
            service.beginRevision(ADMIN);
            service.addTask("First", null, 1, true, true);
            service.addTask("Second", null, 2, false, true);

            assertThat(service.tasksOf(service.draft().orElseThrow().getId()))
                    .extracting(ObPrereqTemplateTask::getSequence, ObPrereqTemplateTask::getTitle)
                    .containsExactly(
                            org.assertj.core.api.Assertions.tuple(1, "First"),
                            org.assertj.core.api.Assertions.tuple(2, "Second"));
        }

        @Test
        @DisplayName("adding a task with no draft open is refused")
        void addingATaskWithNoDraftIsRefused() {
            assertThatThrownBy(() -> service.addTask("Orphan", null, 1, true, true))
                    .isInstanceOf(PrereqNoDraftException.class);
        }

        @Test
        @DisplayName("a draft task can be edited in place")
        void aDraftTaskCanBeEdited() {
            service.beginRevision(ADMIN);
            ObPrereqTemplateTask task = service.addTask("PAN card", null, 3, true, true);

            ObPrereqTemplateTask edited = service.updateTask(task.getId(),
                    "PAN card copy", "Self-attested", 5, true, true);

            assertThat(edited.getTitle()).isEqualTo("PAN card copy");
            assertThat(edited.getDescription()).isEqualTo("Self-attested");
            assertThat(edited.getTatDays()).isEqualTo(5);
        }

        /**
         * <b>The test this class is measured by.</b> A guard written as
         * {@code !isActive} passes every other test in this file and fails
         * only this one — and the bug it would ship is a retired version
         * being edited under whichever clients are still working through
         * it.
         */
        @Test
        @DisplayName("editing a retired version is refused, not just the active one")
        void editingARetiredVersionIsRefused() {
            ObPrereqTemplateVersion retired = publishedMasterWithOneMandatoryTask();
            long taskOnRetired = service.tasksOf(retired.getId()).get(0).getId();

            // Supersede it, so the old version is published AND inactive —
            // the state a `!isActive` guard would wrongly call editable.
            service.beginRevision(ADMIN);
            service.publish(ADMIN);
            assertThat(retired.isActive()).isFalse();
            assertThat(retired.isDraft()).isFalse();

            assertThatThrownBy(() -> service.updateTask(taskOnRetired, "Rewritten", null, 1, true, true))
                    .isInstanceOf(PrereqTaskNotEditableException.class)
                    .hasMessageContaining("has been published");
        }

        @Test
        @DisplayName("a task on the active version cannot be edited or removed")
        void aTaskOnTheActiveVersionIsFrozen() {
            ObPrereqTemplateVersion active = publishedMasterWithOneMandatoryTask();
            long taskId = service.tasksOf(active.getId()).get(0).getId();

            assertThatThrownBy(() -> service.updateTask(taskId, "Rewritten", null, 1, true, true))
                    .isInstanceOf(PrereqTaskNotEditableException.class);
            assertThatThrownBy(() -> service.removeTask(taskId))
                    .isInstanceOf(PrereqTaskNotEditableException.class);
        }

        @Test
        @DisplayName("removing a draft task takes it out of the set")
        void removingADraftTask() {
            service.beginRevision(ADMIN);
            ObPrereqTemplateTask first = service.addTask("First", null, 1, true, true);
            service.addTask("Second", null, 2, false, true);

            service.removeTask(first.getId());

            assertThat(service.tasksOf(service.draft().orElseThrow().getId()))
                    .extracting(ObPrereqTemplateTask::getTitle)
                    .containsExactly("Second");
        }

        @Test
        @DisplayName("an unknown task id is not found")
        void anUnknownTaskIsNotFound() {
            assertThatThrownBy(() -> service.removeTask(9999L))
                    .isInstanceOf(PrereqTemplateTaskNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("reordering")
    class Reordering {

        @Test
        @DisplayName("the draft's tasks take the requested order as 1..N")
        void reorderAppliesTheRequestedOrder() {
            service.beginRevision(ADMIN);
            ObPrereqTemplateTask a = service.addTask("A", null, 1, true, true);
            ObPrereqTemplateTask b = service.addTask("B", null, 1, false, true);
            ObPrereqTemplateTask c = service.addTask("C", null, 1, false, true);

            service.reorderTasks(List.of(c.getId(), a.getId(), b.getId()));

            assertThat(service.tasksOf(service.draft().orElseThrow().getId()))
                    .extracting(ObPrereqTemplateTask::getTitle)
                    .containsExactly("C", "A", "B");
        }

        /**
         * A swap is the minimal case that would collide on
         * {@code uq_ob_prereq_template_tasks_seq} under a single-pass
         * write — which is why the service goes through negative
         * placeholders. Asserted here as the outcome; the constraint
         * itself was verified against MySQL.
         */
        @Test
        @DisplayName("swapping two adjacent tasks never leaves two at one position")
        void swappingTwoAdjacentTasks() {
            service.beginRevision(ADMIN);
            ObPrereqTemplateTask a = service.addTask("A", null, 1, true, true);
            ObPrereqTemplateTask b = service.addTask("B", null, 1, false, true);

            service.reorderTasks(List.of(b.getId(), a.getId()));

            assertThat(service.tasksOf(service.draft().orElseThrow().getId()))
                    .extracting(ObPrereqTemplateTask::getSequence)
                    .containsExactly(1, 2);
            assertThat(a.getSequence()).isEqualTo(2);
            assertThat(b.getSequence()).isEqualTo(1);
        }

        @Test
        @DisplayName("a partial list is refused rather than partly applied")
        void aPartialListIsRefused() {
            service.beginRevision(ADMIN);
            ObPrereqTemplateTask a = service.addTask("A", null, 1, true, true);
            service.addTask("B", null, 1, false, true);

            assertThatThrownBy(() -> service.reorderTasks(List.of(a.getId())))
                    .isInstanceOf(PrereqTaskReorderMismatchException.class)
                    .hasMessageContaining("not exactly the draft's current task set");
        }

        @Test
        @DisplayName("a repeated id is refused")
        void aRepeatedIdIsRefused() {
            service.beginRevision(ADMIN);
            ObPrereqTemplateTask a = service.addTask("A", null, 1, true, true);
            service.addTask("B", null, 1, false, true);

            assertThatThrownBy(() -> service.reorderTasks(List.of(a.getId(), a.getId())))
                    .isInstanceOf(PrereqTaskReorderMismatchException.class)
                    .hasMessageContaining("more than once");
        }

        @Test
        @DisplayName("an id from another version is refused")
        void anIdFromAnotherVersionIsRefused() {
            ObPrereqTemplateVersion published = publishedMasterWithOneMandatoryTask();
            long foreignTaskId = service.tasksOf(published.getId()).get(0).getId();

            service.beginRevision(ADMIN);
            List<Long> draftTaskIds = service.tasksOf(service.draft().orElseThrow().getId())
                    .stream().map(ObPrereqTemplateTask::getId).toList();

            assertThatThrownBy(() -> service.reorderTasks(
                    java.util.stream.Stream.concat(draftTaskIds.stream(), java.util.stream.Stream.of(foreignTaskId))
                            .toList()))
                    .isInstanceOf(PrereqTaskReorderMismatchException.class);
        }
    }

    @Nested
    @DisplayName("reference documents")
    class ReferenceDocuments {

        @Test
        @DisplayName("a reference document is listed under its own task")
        void aReferenceDocumentIsListed() {
            service.beginRevision(ADMIN);
            ObPrereqTemplateTask task = service.addTask("PAN card copy", null, 3, true, true);
            long attachmentId = referenceAttachment(task.getId());

            ObPrereqTemplateTaskDoc doc =
                    service.addTaskDoc(task.getId(), "Specimen form", attachmentId);

            assertThat(doc.getLabel()).isEqualTo("Specimen form");
            assertThat(doc.getSequence()).isEqualTo(1);
            assertThat(service.docsOf(task.getId())).containsExactly(doc);
        }

        /**
         * The check the migration deliberately left to the service — no
         * foreign key holds it, because a composite key would refuse every
         * document a revision clones.
         */
        @Test
        @DisplayName("an attachment owned by another task is refused")
        void anAttachmentOwnedByAnotherTaskIsRefused() {
            service.beginRevision(ADMIN);
            ObPrereqTemplateTask mine = service.addTask("Mine", null, 1, true, true);
            ObPrereqTemplateTask theirs = service.addTask("Theirs", null, 1, false, true);
            long theirAttachment = referenceAttachment(theirs.getId());

            assertThatThrownBy(() -> service.addTaskDoc(mine.getId(), "Borrowed", theirAttachment))
                    .isInstanceOf(PrereqAttachmentNotOwnedByTaskException.class)
                    .hasMessageContaining("different owner");
        }

        /**
         * REFERENCE is what staff attach for the client to read; SUBMISSION
         * is what comes back the other way and belongs on the instance.
         * Listing one here would put a client's own upload on the org-wide
         * master, visible to every other client boarded from it.
         */
        @Test
        @DisplayName("a SUBMISSION cannot be listed as a master reference document")
        void aSubmissionCannotBeAMasterReference() {
            service.beginRevision(ADMIN);
            ObPrereqTemplateTask task = service.addTask("PAN card copy", null, 3, true, true);

            ObAttachment submission = new ObAttachment();
            submission.setId(attachmentIds.incrementAndGet());
            submission.setPrereqTemplateTaskId(task.getId());
            submission.setKind(ObAttachmentKind.SUBMISSION);
            attachmentRows.put(submission.getId(), submission);

            assertThatThrownBy(() -> service.addTaskDoc(task.getId(), "Wrong kind", submission.getId()))
                    .isInstanceOf(PrereqAttachmentNotOwnedByTaskException.class)
                    .hasMessageContaining("REFERENCE");
        }

        @Test
        @DisplayName("attaching to a published task is refused")
        void attachingToAPublishedTaskIsRefused() {
            ObPrereqTemplateVersion active = publishedMasterWithOneMandatoryTask();
            long taskId = service.tasksOf(active.getId()).get(0).getId();
            long attachmentId = referenceAttachment(taskId);

            assertThatThrownBy(() -> service.addTaskDoc(taskId, "Too late", attachmentId))
                    .isInstanceOf(PrereqTaskNotEditableException.class);
        }

        @Test
        @DisplayName("a reference document can be detached from a draft task")
        void aReferenceDocumentCanBeDetached() {
            service.beginRevision(ADMIN);
            ObPrereqTemplateTask task = service.addTask("PAN card copy", null, 3, true, true);
            ObPrereqTemplateTaskDoc doc = service.addTaskDoc(task.getId(), "Specimen",
                    referenceAttachment(task.getId()));

            service.removeTaskDoc(doc.getId());

            assertThat(service.docsOf(task.getId())).isEmpty();
        }

        @Test
        @DisplayName("an unknown document id is not found")
        void anUnknownDocumentIsNotFound() {
            assertThatThrownBy(() -> service.removeTaskDoc(9999L))
                    .isInstanceOf(PrereqTemplateTaskDocNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("reads")
    class Reads {

        @Test
        @DisplayName("an older version reads back as it was published")
        void anOlderVersionReadsBack() {
            ObPrereqTemplateVersion first = publishedMasterWithOneMandatoryTask();
            service.beginRevision(ADMIN);
            service.addTask("Added later", null, 2, true, true);
            service.publish(ADMIN);

            assertThat(service.versionNumbered(1)).contains(first);
            assertThat(service.tasksOf(first.getId()))
                    .extracting(ObPrereqTemplateTask::getTitle)
                    .containsExactly("PAN card copy");
        }

        @Test
        @DisplayName("nothing is active before the first publish")
        void nothingIsActiveBeforeTheFirstPublish() {
            service.beginRevision(ADMIN);

            assertThat(service.activeVersion()).isEmpty();
            assertThat(service.draft()).isPresent();
        }

        @Test
        @DisplayName("documents come back grouped by task in one read")
        void documentsComeBackGroupedByTask() {
            service.beginRevision(ADMIN);
            ObPrereqTemplateTask a = service.addTask("A", null, 1, true, true);
            ObPrereqTemplateTask b = service.addTask("B", null, 1, false, true);
            service.addTaskDoc(a.getId(), "For A", referenceAttachment(a.getId()));
            service.addTaskDoc(b.getId(), "For B", referenceAttachment(b.getId()));

            Map<Long, List<ObPrereqTemplateTaskDoc>> byTask =
                    service.docsByTask(List.of(a.getId(), b.getId()));

            assertThat(byTask.get(a.getId())).extracting(ObPrereqTemplateTaskDoc::getLabel)
                    .containsExactly("For A");
            assertThat(byTask.get(b.getId())).extracting(ObPrereqTemplateTaskDoc::getLabel)
                    .containsExactly("For B");
        }

        @Test
        @DisplayName("an empty id list asks the repository nothing")
        void anEmptyIdListAsksNothing() {
            assertThat(service.docsByTask(List.of())).isEmpty();
        }
    }
}
