package com.edunext.edutrack.api.feature.onboarding.prereqs;

import com.edunext.edutrack.domain.journal.ObPrereqJournal;
import com.edunext.edutrack.domain.onboarding.ObClientPrereqTask;
import com.edunext.edutrack.domain.onboarding.ObClientPrereqTaskRepository;
import com.edunext.edutrack.domain.onboarding.ObClientPrereqs;
import com.edunext.edutrack.domain.onboarding.ObClientPrereqsRepository;
import com.edunext.edutrack.domain.onboarding.ObGateStatus;
import com.edunext.edutrack.domain.onboarding.ObPrereqActorType;
import com.edunext.edutrack.domain.onboarding.ObPrereqHistory;
import com.edunext.edutrack.domain.onboarding.ObPrereqSubmittedVia;
import com.edunext.edutrack.domain.onboarding.ObPrereqTaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * B-125 · {@link ObPrereqTaskService} — the four transitions, the chain and
 * the gate's only valve.
 *
 * <p>Repositories are mocked but backed by in-memory maps, on
 * {@code ObPrereqTemplateServiceTest}'s reasoning: a transition writes a task
 * and then re-reads the client's whole set to evaluate the gate, which a
 * fixed-return stub cannot model.
 *
 * <p><b>The tests this file is measured by</b> are the ones that encode
 * guarantees rather than behaviour:
 *
 * <ul>
 *   <li>{@code aMandatoryTaskCannotBeSkipped} — plan §5.3's whole point. If
 *       this passes when it should not, the gate is a convention.</li>
 *   <li>{@code verifyingStraightFromPendingIsRefused} — a task that skipped
 *       {@code SUBMITTED} has no {@code submittedAt}, and §5.4's
 *       client-attributed clock has nothing to measure.</li>
 *   <li>{@code aReturnDoesNotResetTheClock} — a return that restarted the
 *       clock would let an incomplete submission buy an extension.</li>
 *   <li>{@code theGateOpensOnceAndOnlyOnce} — {@code gateOpened} is not
 *       {@code gateStatus == OPEN}; a screen that confused them would
 *       refresh forever.</li>
 * </ul>
 */
class ObPrereqTaskServiceTest {

    private static final long CLIENT = 42L;
    private static final long STAFF = 7L;
    private static final long CONTACT = 900L;

    private final Map<Long, ObClientPrereqTask> taskRows = new LinkedHashMap<>();
    private final Map<Long, ObClientPrereqs> headerRows = new LinkedHashMap<>();
    private final List<Object[]> commentRows = new ArrayList<>();
    private final List<ObPrereqHistory> historyRows = new ArrayList<>();

    private final AtomicLong taskIds = new AtomicLong();
    private final AtomicLong headerIds = new AtomicLong();
    private final AtomicLong commentIds = new AtomicLong();
    private final AtomicLong historyIds = new AtomicLong();

    private final ObClientPrereqTaskRepository tasks = mock(ObClientPrereqTaskRepository.class);
    private final ObClientPrereqsRepository headers = mock(ObClientPrereqsRepository.class);
    private final ObPrereqThreadRepository thread = mock(ObPrereqThreadRepository.class);
    private final ObPrereqJournal journal = mock(ObPrereqJournal.class);
    private final ObClientPrereqService clientPrereqs = mock(ObClientPrereqService.class);

    /**
     * A stand-in for {@link ObPrereqGateReadOnly}, which needs a JDBC reader.
     * It applies the same two decisions the shipped one does — evaluate §5.3's
     * condition, and stamp the header once — so the transitions under test see
     * the real contract rather than a no-op.
     */
    private final ObPrereqGate gate = (obClientId, all) -> {
        boolean satisfied = ObPrereqGate.isSatisfiedBy(all);
        ObClientPrereqs header = headerRows.values().stream()
                .filter(h -> h.getObClientId().equals(obClientId)).findFirst().orElse(null);
        boolean was = header != null && header.getStatus() == ObClientPrereqs.Status.CLEARED;
        boolean openedNow = satisfied && !was;
        if (openedNow && header != null) {
            header.setStatus(ObClientPrereqs.Status.CLEARED);
            header.setClearedAt(Instant.now());
        }
        return new ObPrereqGate.Outcome(
                satisfied ? ObGateStatus.OPEN : ObGateStatus.LOCKED, openedNow, List.of());
    };

    private final ObPrereqTaskService service =
            new ObPrereqTaskService(tasks, headers, thread, journal, gate, clientPrereqs);

    @BeforeEach
    void wireFakes() {
        lenient().when(tasks.save(any())).thenAnswer(inv -> {
            ObClientPrereqTask t = inv.getArgument(0);
            if (t.getId() == null) {
                t.setId(taskIds.incrementAndGet());
            }
            taskRows.put(t.getId(), t);
            return t;
        });
        lenient().when(tasks.findById(any())).thenAnswer(inv ->
                Optional.ofNullable(taskRows.get(inv.<Long>getArgument(0))));
        lenient().when(tasks.findByObClientIdOrderBySequenceAsc(any())).thenAnswer(inv ->
                taskRows.values().stream()
                        .filter(t -> t.getObClientId().equals(inv.<Long>getArgument(0)))
                        .sorted(Comparator.comparingInt(ObClientPrereqTask::getSequence))
                        .toList());
        lenient().when(tasks.findByHeaderIdOrderBySequenceAsc(any())).thenAnswer(inv ->
                taskRows.values().stream()
                        .filter(t -> t.getHeaderId().equals(inv.<Long>getArgument(0)))
                        .sorted(Comparator.comparingInt(ObClientPrereqTask::getSequence))
                        .toList());
        lenient().when(tasks.findTopByHeaderIdOrderBySequenceDesc(any())).thenAnswer(inv ->
                taskRows.values().stream()
                        .filter(t -> t.getHeaderId().equals(inv.<Long>getArgument(0)))
                        .max(Comparator.comparingInt(ObClientPrereqTask::getSequence)));

        lenient().when(headers.findByObClientId(any())).thenAnswer(inv ->
                headerRows.values().stream()
                        .filter(h -> h.getObClientId().equals(inv.<Long>getArgument(0)))
                        .findFirst());
        lenient().when(headers.save(any())).thenAnswer(inv -> {
            ObClientPrereqs h = inv.getArgument(0);
            if (h.getId() == null) {
                h.setId(headerIds.incrementAndGet());
            }
            headerRows.put(h.getId(), h);
            return h;
        });

        lenient().when(thread.insertComment(anyLong(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenAnswer(inv -> {
                    long id = commentIds.incrementAndGet();
                    commentRows.add(new Object[]{
                            inv.getArgument(1), inv.getArgument(4), inv.getArgument(5)});
                    return id;
                });

        lenient().when(journal.append(any())).thenAnswer(inv -> {
            ObPrereqHistory h = inv.getArgument(0);
            h.setId(historyIds.incrementAndGet());
            historyRows.add(h);
            return h;
        });

        // The service asks for a working-calendar due date; the calendar
        // itself is B-024's and is tested there. What matters here is that
        // the service routes through it rather than adding days naively.
        lenient().when(clientPrereqs.dueAt(any(), anyLong() == 0 ? 0 : org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(inv -> inv.<Instant>getArgument(0)
                        .plus(inv.<Integer>getArgument(1), ChronoUnit.DAYS));
    }

    // ── fixtures ─────────────────────────────────────────────────────────

    private ObClientPrereqs givenChecklist() {
        ObClientPrereqs header = new ObClientPrereqs();
        header.setObClientId(CLIENT);
        header.setTemplateVersionId(1L);
        header.setTemplateVersion(1);
        header.setStatus(ObClientPrereqs.Status.IN_PROGRESS);
        return headers.save(header);
    }

    private ObClientPrereqTask givenTask(ObClientPrereqs header, String title,
                                         boolean mandatory, int sequence) {
        ObClientPrereqTask task = new ObClientPrereqTask();
        task.setHeaderId(header.getId());
        task.setObClientId(CLIENT);
        task.setTemplateTaskId(100L + sequence);
        task.setSequence(sequence);
        task.setTitle(title);
        task.setTatDays(3);
        task.setMandatory(mandatory);
        task.setAdHoc(false);
        task.setDueAt(Instant.now().plus(3, ChronoUnit.DAYS));
        return tasks.save(task);
    }

    private ObClientPrereqTask submitted(ObClientPrereqs header, String title, boolean mandatory, int seq) {
        ObClientPrereqTask task = givenTask(header, title, mandatory, seq);
        service.submit(task.getId(), STAFF, null, null);
        return task;
    }

    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("submit")
    class Submit {

        @Test
        @DisplayName("a staff submission records STAFF and the user, not a contact")
        void staffSubmissionRecordsTheUser() {
            ObClientPrereqTask task = givenTask(givenChecklist(), "PAN copy", true, 1);

            service.submit(task.getId(), STAFF, null, "arrived by email");

            assertThat(task.getStatus()).isEqualTo(ObPrereqTaskStatus.SUBMITTED);
            assertThat(task.getSubmittedVia()).isEqualTo(ObPrereqSubmittedVia.STAFF);
            assertThat(task.getSubmittedByUser()).isEqualTo(STAFF);
            assertThat(task.getSubmittedByContact()).isNull();
            assertThat(task.getSubmittedAt()).isNotNull();
        }

        /**
         * The portal half of plan §4's {@code submitted_via}. Without a staff
         * path an implementor would log in as the client; without a portal
         * path the column would answer a question nobody asked.
         */
        @Test
        @DisplayName("a portal submission records CLIENT and the contact, not a user")
        void portalSubmissionRecordsTheContact() {
            ObClientPrereqTask task = givenTask(givenChecklist(), "PAN copy", true, 1);

            service.submit(task.getId(), null, CONTACT, null);

            assertThat(task.getSubmittedVia()).isEqualTo(ObPrereqSubmittedVia.PORTAL);
            assertThat(task.getSubmittedByContact()).isEqualTo(CONTACT);
            assertThat(task.getSubmittedByUser()).isNull();
            assertThat(historyRows).last().satisfies(h -> {
                assertThat(h.getActorType()).isEqualTo(ObPrereqActorType.CLIENT);
                assertThat(h.getActorContactId()).isEqualTo(CONTACT);
                assertThat(h.getActorUserId()).isNull();
            });
        }

        @Test
        @DisplayName("submitting twice is refused, naming the status it is in")
        void submittingTwiceIsRefused() {
            ObClientPrereqTask task = submitted(givenChecklist(), "PAN copy", true, 1);

            assertThatThrownBy(() -> service.submit(task.getId(), STAFF, null, null))
                    .isInstanceOf(PrereqTransitionException.class)
                    .hasMessageContaining("SUBMITTED");
        }

        @Test
        @DisplayName("re-submitting a returned task is the normal loop and is allowed")
        void resubmittingAReturnedTaskIsAllowed() {
            ObClientPrereqs header = givenChecklist();
            ObClientPrereqTask task = submitted(header, "PAN copy", true, 1);
            service.returnToClient(task.getId(), STAFF, "the scan is unreadable");

            service.submit(task.getId(), STAFF, null, null);

            assertThat(task.getStatus()).isEqualTo(ObPrereqTaskStatus.SUBMITTED);
        }
    }

    @Nested
    @DisplayName("verify")
    class Verify {

        /**
         * <b>A measured test.</b> {@code submittedAt} is what plan §5.4
         * attributes client-side waiting time with; a task verified straight
         * from {@code PENDING} has none, so the time it took would be
         * attributed to nobody.
         */
        @Test
        @DisplayName("verifying straight from PENDING is refused")
        void verifyingStraightFromPendingIsRefused() {
            ObClientPrereqTask task = givenTask(givenChecklist(), "PAN copy", true, 1);

            assertThatThrownBy(() -> service.verify(task.getId(), STAFF, null))
                    .isInstanceOf(PrereqTransitionException.class)
                    .hasMessageContaining("PENDING");
        }

        @Test
        @DisplayName("verifying records who and when")
        void verifyingRecordsWhoAndWhen() {
            ObClientPrereqTask task = submitted(givenChecklist(), "PAN copy", true, 1);

            service.verify(task.getId(), STAFF, "looks right");

            assertThat(task.getStatus()).isEqualTo(ObPrereqTaskStatus.VERIFIED);
            assertThat(task.getVerifiedBy()).isEqualTo(STAFF);
            assertThat(task.getVerifiedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("return")
    class Return {

        @Test
        @DisplayName("a return sends the task back to PENDING and clears the submission stamps")
        void aReturnClearsTheSubmissionStamps() {
            ObClientPrereqTask task = submitted(givenChecklist(), "PAN copy", true, 1);

            service.returnToClient(task.getId(), STAFF, "the scan is unreadable");

            assertThat(task.getStatus()).isEqualTo(ObPrereqTaskStatus.PENDING);
            assertThat(task.getSubmittedAt()).isNull();
            assertThat(task.getSubmittedVia()).isNull();
            assertThat(task.getSubmittedByUser()).isNull();
        }

        /**
         * <b>A measured test.</b> Plan §5.4 attributes prerequisite time to
         * the client. A return that reset {@code dueAt} would let an
         * incomplete submission buy an extension, once per return.
         */
        @Test
        @DisplayName("a return does not reset the clock")
        void aReturnDoesNotResetTheClock() {
            ObClientPrereqTask task = submitted(givenChecklist(), "PAN copy", true, 1);
            Instant dueBefore = task.getDueAt();

            service.returnToClient(task.getId(), STAFF, "unreadable");

            assertThat(task.getDueAt()).isEqualTo(dueBefore);
        }

        /**
         * The comment lands in the thread <i>and</i> in the chain: the thread
         * is where the client reads it, the chain is what makes it defensible
         * later.
         */
        @Test
        @DisplayName("the mandatory comment is written to the thread as a system comment and into the chain")
        void theReturnCommentIsWrittenToBoth() {
            ObClientPrereqTask task = submitted(givenChecklist(), "PAN copy", true, 1);

            service.returnToClient(task.getId(), STAFF, "the scan is unreadable");

            assertThat(commentRows).singleElement().satisfies(c -> {
                assertThat(c[0]).isEqualTo(ObPrereqActorType.STAFF);
                assertThat(c[1]).isEqualTo("the scan is unreadable");
                assertThat(c[2]).isEqualTo(true);
            });
            assertThat(historyRows).last()
                    .extracting(ObPrereqHistory::getReason)
                    .isEqualTo("the scan is unreadable");
        }

        @Test
        @DisplayName("returning a task that is not SUBMITTED is refused")
        void returningAPendingTaskIsRefused() {
            ObClientPrereqTask task = givenTask(givenChecklist(), "PAN copy", true, 1);

            assertThatThrownBy(() -> service.returnToClient(task.getId(), STAFF, "why"))
                    .isInstanceOf(PrereqTransitionException.class);
        }
    }

    @Nested
    @DisplayName("skip — the gate's only valve")
    class Skip {

        /**
         * <b>The test this class exists for.</b> Plan §5.3 leaves exactly one
         * valve and it is non-mandatory tasks only: a skippable mandatory
         * task is not a mandatory task, and the gate becomes a convention.
         * The database says the same at
         * {@code ck_ob_client_prereq_tasks_mandatory_not_skipped}.
         */
        @Test
        @DisplayName("a mandatory task cannot be skipped")
        void aMandatoryTaskCannotBeSkipped() {
            ObClientPrereqTask task = givenTask(givenChecklist(), "PAN copy", true, 1);

            assertThatThrownBy(() -> service.skip(task.getId(), STAFF, "client cannot supply"))
                    .isInstanceOf(MandatoryTaskNotSkippableException.class)
                    .hasMessageContaining("only valve");

            assertThat(task.getStatus()).isEqualTo(ObPrereqTaskStatus.PENDING);
        }

        @Test
        @DisplayName("a non-mandatory task is skipped with its reason recorded")
        void aNonMandatoryTaskIsSkipped() {
            ObClientPrereqTask task = givenTask(givenChecklist(), "Office photos", false, 1);

            service.skip(task.getId(), STAFF, "client has no office yet");

            assertThat(task.getStatus()).isEqualTo(ObPrereqTaskStatus.SKIPPED);
            assertThat(task.getSkipReason()).isEqualTo("client has no office yet");
            assertThat(task.getSkippedBy()).isEqualTo(STAFF);
            assertThat(task.getSkippedAt()).isNotNull();
        }

        @Test
        @DisplayName("the skip reason reaches the chain, where a waiver dispute reads it")
        void theSkipReasonReachesTheChain() {
            ObClientPrereqTask task = givenTask(givenChecklist(), "Office photos", false, 1);

            service.skip(task.getId(), STAFF, "client has no office yet");

            assertThat(historyRows).last().satisfies(h -> {
                assertThat(h.getToStatus()).isEqualTo(ObPrereqTaskStatus.SKIPPED);
                assertThat(h.getReason()).isEqualTo("client has no office yet");
            });
        }

        @Test
        @DisplayName("a settled task cannot be skipped again")
        void aSettledTaskCannotBeSkipped() {
            ObClientPrereqTask task = givenTask(givenChecklist(), "Office photos", false, 1);
            service.skip(task.getId(), STAFF, "first");

            assertThatThrownBy(() -> service.skip(task.getId(), STAFF, "second"))
                    .isInstanceOf(PrereqTransitionException.class);
        }
    }

    @Nested
    @DisplayName("the gate")
    class Gate {

        @Test
        @DisplayName("the gate stays locked while a mandatory task is outstanding")
        void theGateStaysLockedWhileMandatoryOutstanding() {
            ObClientPrereqs header = givenChecklist();
            ObClientPrereqTask first = submitted(header, "PAN copy", true, 1);
            givenTask(header, "GST certificate", true, 2);

            var settled = service.verify(first.getId(), STAFF, null);

            assertThat(settled.gate().gateStatus()).isEqualTo(ObGateStatus.LOCKED);
            assertThat(settled.gate().gateOpened()).isFalse();
            assertThat(settled.progress().mandatoryVerified()).isEqualTo(1);
            assertThat(settled.progress().mandatoryTotal()).isEqualTo(2);
        }

        /**
         * Plan §5.3 requires every non-mandatory task to be VERIFIED
         * <b>or</b> SKIPPED too — so an outstanding optional task holds the
         * gate exactly as a mandatory one does. {@code optionalOutstanding}
         * is on the response for this reason: a screen showing 1/1 mandatory
         * beside a locked gate would otherwise look broken.
         */
        @Test
        @DisplayName("an outstanding optional task holds the gate, and the count says so")
        void anOutstandingOptionalTaskHoldsTheGate() {
            ObClientPrereqs header = givenChecklist();
            ObClientPrereqTask mandatory = submitted(header, "PAN copy", true, 1);
            givenTask(header, "Office photos", false, 2);

            var settled = service.verify(mandatory.getId(), STAFF, null);

            assertThat(settled.gate().gateStatus()).isEqualTo(ObGateStatus.LOCKED);
            assertThat(settled.progress().mandatoryVerified()).isEqualTo(1);
            assertThat(settled.progress().mandatoryTotal()).isEqualTo(1);
            assertThat(settled.progress().optionalOutstanding()).isEqualTo(1);
        }

        @Test
        @DisplayName("skipping the last optional task opens the gate")
        void skippingTheLastOptionalOpensTheGate() {
            ObClientPrereqs header = givenChecklist();
            ObClientPrereqTask mandatory = submitted(header, "PAN copy", true, 1);
            ObClientPrereqTask optional = givenTask(header, "Office photos", false, 2);
            service.verify(mandatory.getId(), STAFF, null);

            var settled = service.skip(optional.getId(), STAFF, "no office yet");

            assertThat(settled.gate().gateStatus()).isEqualTo(ObGateStatus.OPEN);
            assertThat(settled.gate().gateOpened()).isTrue();
        }

        /**
         * <b>A measured test.</b> {@code gateOpened} means "this transition
         * opened it", not "it is open" — the contract is explicit, because a
         * screen that refreshed the ribbon whenever the gate reads open would
         * refresh forever.
         */
        @Test
        @DisplayName("the gate opens once and only once")
        void theGateOpensOnceAndOnlyOnce() {
            ObClientPrereqs header = givenChecklist();
            ObClientPrereqTask first = submitted(header, "PAN copy", true, 1);
            ObClientPrereqTask second = submitted(header, "GST certificate", true, 2);

            var opening = service.verify(first.getId(), STAFF, null);
            assertThat(opening.gate().gateOpened()).isFalse();

            var opened = service.verify(second.getId(), STAFF, null);
            assertThat(opened.gate().gateOpened()).isTrue();
            assertThat(opened.gate().gateStatus()).isEqualTo(ObGateStatus.OPEN);

            // A third settling transition on the same client reports the gate
            // open and `gateOpened` false — it was not this one that did it.
            ObClientPrereqTask third = givenTask(header, "Office photos", false, 3);
            var after = service.skip(third.getId(), STAFF, "not needed");
            assertThat(after.gate().gateStatus()).isEqualTo(ObGateStatus.OPEN);
            assertThat(after.gate().gateOpened()).isFalse();
        }

        @Test
        @DisplayName("clearing the checklist stamps the header once")
        void clearingStampsTheHeaderOnce() {
            ObClientPrereqs header = givenChecklist();
            ObClientPrereqTask only = submitted(header, "PAN copy", true, 1);

            service.verify(only.getId(), STAFF, null);
            Instant clearedAt = header.getClearedAt();

            assertThat(header.getStatus()).isEqualTo(ObClientPrereqs.Status.CLEARED);
            assertThat(clearedAt).isNotNull();

            // A later transition must not re-date it: `cleared_at` is what
            // §5.4's attribution is measured against.
            ObClientPrereqTask later = givenTask(header, "Office photos", false, 2);
            service.skip(later.getId(), STAFF, "not needed");
            assertThat(header.getClearedAt()).isEqualTo(clearedAt);
        }
    }

    @Nested
    @DisplayName("the chain")
    class Chain {

        @Test
        @DisplayName("every transition appends exactly one history entry, from and to recorded")
        void everyTransitionAppendsOneEntry() {
            ObClientPrereqs header = givenChecklist();
            ObClientPrereqTask task = givenTask(header, "PAN copy", true, 1);

            service.submit(task.getId(), STAFF, null, null);
            service.returnToClient(task.getId(), STAFF, "unreadable");
            service.submit(task.getId(), STAFF, null, null);
            service.verify(task.getId(), STAFF, null);

            assertThat(historyRows)
                    .extracting(ObPrereqHistory::getFromStatus, ObPrereqHistory::getToStatus)
                    .containsExactly(
                            org.assertj.core.api.Assertions.tuple(
                                    ObPrereqTaskStatus.PENDING, ObPrereqTaskStatus.SUBMITTED),
                            org.assertj.core.api.Assertions.tuple(
                                    ObPrereqTaskStatus.SUBMITTED, ObPrereqTaskStatus.PENDING),
                            org.assertj.core.api.Assertions.tuple(
                                    ObPrereqTaskStatus.PENDING, ObPrereqTaskStatus.SUBMITTED),
                            org.assertj.core.api.Assertions.tuple(
                                    ObPrereqTaskStatus.SUBMITTED, ObPrereqTaskStatus.VERIFIED));
        }

        @Test
        @DisplayName("the chain is keyed by client, which is what the journal locks")
        void theChainIsKeyedByClient() {
            ObClientPrereqTask task = submitted(givenChecklist(), "PAN copy", true, 1);
            service.verify(task.getId(), STAFF, null);

            assertThat(historyRows).allSatisfy(h ->
                    assertThat(h.getObClientId()).isEqualTo(CLIENT));
        }

        @Test
        @DisplayName("an ad-hoc task's first entry has no from-status")
        void anAdHocTasksFirstEntryHasNoFromStatus() {
            givenChecklist();

            service.addAdHoc(CLIENT, "Board resolution", null, 5, false, STAFF);

            assertThat(historyRows).singleElement().satisfies(h -> {
                assertThat(h.getFromStatus()).isNull();
                assertThat(h.getToStatus()).isEqualTo(ObPrereqTaskStatus.PENDING);
            });
        }
    }

    @Nested
    @DisplayName("ad-hoc tasks and edits")
    class AdHocAndEdits {

        @Test
        @DisplayName("an ad-hoc task is marked as one and carries no template id")
        void anAdHocTaskIsMarked() {
            givenChecklist();

            ObClientPrereqTask created =
                    service.addAdHoc(CLIENT, "Board resolution", "signed", 5, true, STAFF);

            assertThat(created.isAdHoc()).isTrue();
            assertThat(created.getTemplateTaskId()).isNull();
            assertThat(created.isMandatory()).isTrue();
            assertThat(created.getSequence()).isEqualTo(1);
        }

        @Test
        @DisplayName("an ad-hoc task is appended after the snapshotted ones")
        void anAdHocTaskIsAppended() {
            ObClientPrereqs header = givenChecklist();
            givenTask(header, "PAN copy", true, 1);
            givenTask(header, "GST certificate", true, 2);

            ObClientPrereqTask created =
                    service.addAdHoc(CLIENT, "Board resolution", null, 5, false, STAFF);

            assertThat(created.getSequence()).isEqualTo(3);
        }

        @Test
        @DisplayName("adding to a client with no checklist is not found")
        void addingToAClientWithNoChecklist() {
            assertThatThrownBy(() ->
                    service.addAdHoc(CLIENT, "Board resolution", null, 5, false, STAFF))
                    .isInstanceOf(ClientPrereqsNotFoundException.class);
        }

        @Test
        @DisplayName("a settled task cannot be reworded")
        void aSettledTaskCannotBeReworded() {
            ObClientPrereqTask task = submitted(givenChecklist(), "PAN copy", true, 1);
            service.verify(task.getId(), STAFF, null);

            assertThatThrownBy(() -> service.update(task.getId(), "Rewritten", null, null))
                    .isInstanceOf(PrereqTaskSettledException.class)
                    .hasMessageContaining("VERIFIED");
        }

        @Test
        @DisplayName("a null field means leave it")
        void aNullFieldMeansLeaveIt() {
            ObClientPrereqTask task = givenTask(givenChecklist(), "PAN copy", true, 1);

            service.update(task.getId(), null, "now with a description", null);

            assertThat(task.getTitle()).isEqualTo("PAN copy");
            assertThat(task.getDescription()).isEqualTo("now with a description");
            assertThat(task.getTatDays()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("overdue is derived, never stored")
    class Overdue {

        @Test
        @DisplayName("an outstanding task past its due date is overdue")
        void anOutstandingTaskPastDueIsOverdue() {
            ObClientPrereqTask task = givenTask(givenChecklist(), "PAN copy", true, 1);
            task.setDueAt(Instant.now().minus(1, ChronoUnit.DAYS));

            assertThat(task.isOverdue(Instant.now())).isTrue();
        }

        /**
         * A settled task is never overdue, however late it was. The question
         * the screen asks is "what is the client still holding up", and a
         * verified task is holding nothing up.
         */
        @Test
        @DisplayName("a settled task is never overdue, however late it was")
        void aSettledTaskIsNeverOverdue() {
            ObClientPrereqTask task = submitted(givenChecklist(), "PAN copy", true, 1);
            task.setDueAt(Instant.now().minus(30, ChronoUnit.DAYS));
            service.verify(task.getId(), STAFF, null);

            assertThat(task.isOverdue(Instant.now())).isFalse();
        }
    }
}
