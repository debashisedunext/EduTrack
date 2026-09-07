package com.edunext.edutrack.api.feature.onboarding.instances;

import com.edunext.edutrack.domain.journal.ObStepJournal;
import com.edunext.edutrack.domain.masters.WorkingCalendar;
import com.edunext.edutrack.domain.masters.WorkingCalendarRepository;
import com.edunext.edutrack.domain.masters.WorkingHoursService;
import com.edunext.edutrack.domain.onboarding.ObAttachmentRepository;
import com.edunext.edutrack.domain.onboarding.ObAttachmentScanStatus;
import com.edunext.edutrack.domain.onboarding.ObGateStatus;
import com.edunext.edutrack.domain.onboarding.ObJourney;
import com.edunext.edutrack.domain.onboarding.ObJourneyRepository;
import com.edunext.edutrack.domain.onboarding.ObJourneyStep;
import com.edunext.edutrack.domain.onboarding.ObJourneyStepItem;
import com.edunext.edutrack.domain.onboarding.ObJourneyStepItemRepository;
import com.edunext.edutrack.domain.onboarding.ObJourneyStepRepository;
import com.edunext.edutrack.domain.onboarding.ObJourneyStepStatus;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStepDoc;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStepDocRepository;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStepItem;
import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateStepItemRepository;
import com.edunext.edutrack.domain.onboarding.ObSignoffKind;
import com.edunext.edutrack.domain.onboarding.ObSignoffRepository;
import com.edunext.edutrack.domain.onboarding.ObSignoffStatus;
import com.edunext.edutrack.domain.onboarding.ObStepClockAttribution;
import com.edunext.edutrack.domain.onboarding.ObStepClockEvent;
import com.edunext.edutrack.domain.onboarding.ObStepClockEventRepository;
import com.edunext.edutrack.domain.onboarding.ObStepClockEventType;
import com.edunext.edutrack.domain.onboarding.ObStepHistory;
import com.edunext.edutrack.domain.onboarding.ObStepHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openapitools.jackson.nullable.JsonNullable;

import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C-104/C-106/C-107 · unit tests for the step-lifecycle actions — start,
 * complete (gated by C-106), block, waiting-on-client, resume, and skip
 * (C-107) — on {@code ObJourneyInstantiationServiceTest}'s own
 * fake-repository shape — no container, {@code mvnw -pl api -Dtest} runs
 * this in seconds.
 */
class ObJourneyStepLifecycleServiceTest {

    private static final long OWNER = 10L;
    private static final long BACKUP_OWNER = 11L;
    private static final long STRANGER = 99L;
    private static final long JOURNEY = 500L;
    private static final long STEP = 700L;

    private final Map<Long, ObJourneyStep> stepRows = new HashMap<>();
    private final Map<Long, ObJourney> journeyRows = new HashMap<>();
    private final List<ObStepHistory> historyRows = new ArrayList<>();

    private final ObJourneyStepRepository journeySteps = mock(ObJourneyStepRepository.class);
    private final ObJourneyRepository journeys = mock(ObJourneyRepository.class);
    private final ObJourneyStepItemRepository stepItems = mock(ObJourneyStepItemRepository.class);
    private final ObJourneyTemplateStepItemRepository templateStepItems = mock(ObJourneyTemplateStepItemRepository.class);
    private final ObJourneyTemplateStepDocRepository templateStepDocs = mock(ObJourneyTemplateStepDocRepository.class);
    private final ObAttachmentRepository attachments = mock(ObAttachmentRepository.class);
    private final ObSignoffRepository signoffs = mock(ObSignoffRepository.class);
    private final ObStepHistoryRepository stepHistory = mock(ObStepHistoryRepository.class);
    private final ObStepJournal stepJournal = new ObStepJournal(journeys, stepHistory);
    private final WorkingHoursService workingHours = mock(WorkingHoursService.class);
    private final WorkingCalendarRepository workingCalendars = mock(WorkingCalendarRepository.class);
    private final ObStepClockEventRepository clockEvents = mock(ObStepClockEventRepository.class);
    private final ObStepClockRecorder clockRecorder = mock(ObStepClockRecorder.class);

    private final ObJourneyStepLifecycleService service = new ObJourneyStepLifecycleService(
            journeySteps, journeys, stepItems, templateStepItems, templateStepDocs, attachments, signoffs,
            stepJournal, workingHours, workingCalendars, clockEvents, clockRecorder);

    @BeforeEach
    void wireFakes() {
        when(journeySteps.findById(any())).thenAnswer(inv ->
                Optional.ofNullable(stepRows.get(inv.<Long>getArgument(0))));
        when(journeys.findById(any())).thenAnswer(inv ->
                Optional.ofNullable(journeyRows.get(inv.<Long>getArgument(0))));
        when(journeys.findByIdForUpdate(any())).thenAnswer(inv ->
                Optional.ofNullable(journeyRows.get(inv.<Long>getArgument(0))));
        // C-106's completion gate defaults to "nothing outstanding" — an
        // empty Task List and no document checklist — so every C-104 test
        // written before this task keeps completing exactly as it did.
        when(stepItems.findByStepIdOrderBySequenceAsc(any())).thenReturn(List.of());
        when(templateStepItems.findAllById(any())).thenReturn(List.of());
        when(templateStepDocs.findByStepIdOrderBySequenceAsc(any())).thenReturn(List.of());
        when(stepHistory.findFirstByJourneyIdOrderByIdDesc(any())).thenAnswer(inv -> {
            Long journeyId = inv.getArgument(0);
            return historyRows.stream()
                    .filter(row -> row.getJourneyId().equals(journeyId))
                    .reduce((first, second) -> second);
        });
        when(stepHistory.insert(any())).thenAnswer(inv -> {
            ObStepHistory row = inv.getArgument(0);
            row.setId((long) (historyRows.size() + 1));
            historyRows.add(row);
            return row;
        });

        // C-105 · a plain 9-to-6 UTC calendar, Sat/Sun off — a 9-hour working
        // day, so tatDays=2 (pendingStep's default) is an 18-hour budget.
        // addWorkingHours/workingHoursBetween are stubbed as raw-duration
        // arithmetic rather than the real weekend-skipping walk: that walk is
        // WorkingHoursServiceTest's own coverage, and re-deriving it here
        // would test the fake, not this service's wiring into it.
        WorkingCalendar calendar = new WorkingCalendar();
        calendar.setTimezone("UTC");
        calendar.setWorkDayStart(LocalTime.of(9, 0));
        calendar.setWorkDayEnd(LocalTime.of(18, 0));
        calendar.setWeeklyOff(EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY));
        when(workingCalendars.getCalendar()).thenReturn(calendar);
        when(workingHours.addWorkingHours(any(), any())).thenAnswer(inv -> {
            Instant start = inv.getArgument(0);
            BigDecimal hours = inv.getArgument(1);
            return start.plusSeconds(hours.multiply(BigDecimal.valueOf(3600)).longValue());
        });
        when(workingHours.workingHoursBetween(any(), any())).thenAnswer(inv -> {
            Instant from = inv.getArgument(0);
            Instant to = inv.getArgument(1);
            if (!from.isBefore(to)) {
                return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            }
            return BigDecimal.valueOf(Duration.between(from, to).toSeconds())
                    .divide(BigDecimal.valueOf(3600), 2, RoundingMode.HALF_UP);
        });
        when(clockEvents.findFirstByStepIdAndEventTypeOrderByOccurredAtDescIdDesc(anyLong(), any()))
                .thenReturn(Optional.empty());

        ObJourney journey = new ObJourney();
        journey.setId(JOURNEY);
        journey.setObClientId(1L);
        journey.setProductId(1L);
        journey.setTemplateId(1L);
        journey.setGateStatus(ObGateStatus.OPEN);
        journeyRows.put(JOURNEY, journey);

        stepRows.put(STEP, pendingStep());
    }

    private ObJourneyStep pendingStep() {
        ObJourneyStep step = new ObJourneyStep();
        step.setId(STEP);
        step.setJourneyId(JOURNEY);
        step.setSequence(1);
        step.setName("Collect signed agreement");
        step.setTatDays(2);
        step.setOwnerUserId(OWNER);
        step.setBackupOwnerUserId(BACKUP_OWNER);
        step.setStatus(ObJourneyStepStatus.PENDING);
        return step;
    }

    // ── start ─────────────────────────────────────────────────────────────

    @Test
    void startMovesAPendingStepToInProgressAndStampsStartedAt() {
        ObJourneyStep started = service.start(STEP, OWNER);

        assertThat(started.getStatus()).isEqualTo(ObJourneyStepStatus.IN_PROGRESS);
        assertThat(started.getStartedAt()).isNotNull();
    }

    @Test
    void startIsAllowedForTheBackupOwnerToo() {
        ObJourneyStep started = service.start(STEP, BACKUP_OWNER);

        assertThat(started.getStatus()).isEqualTo(ObJourneyStepStatus.IN_PROGRESS);
    }

    @Test
    void startRefusesACallerWhoIsNeitherOwnerNorBackupOwner() {
        assertThatThrownBy(() -> service.start(STEP, STRANGER))
                .isInstanceOf(NotStepOwnerException.class);
    }

    @Test
    void startRefusesAStepThatIsNotPending() {
        stepRows.get(STEP).setStatus(ObJourneyStepStatus.IN_PROGRESS);

        assertThatThrownBy(() -> service.start(STEP, OWNER))
                .isInstanceOf(InvalidStepTransitionException.class);
    }

    @Test
    void startRefusesWhileTheJourneyGateIsStillLocked() {
        journeyRows.get(JOURNEY).setGateStatus(ObGateStatus.LOCKED);

        assertThatThrownBy(() -> service.start(STEP, OWNER))
                .isInstanceOf(JourneyNotOpenException.class);
    }

    @Test
    void startRefusesWhileTheJourneyIsHeldByAnother() {
        journeyRows.get(JOURNEY).setHeldByJourneyId(600L);

        assertThatThrownBy(() -> service.start(STEP, OWNER))
                .isInstanceOf(JourneyNotOpenException.class);
    }

    @Test
    void startFailsCleanlyForAnUnknownStep() {
        assertThatThrownBy(() -> service.start(404L, OWNER))
                .isInstanceOf(JourneyStepNotFoundException.class);
    }

    // ── complete ──────────────────────────────────────────────────────────

    @Test
    void completeMovesAnInProgressStepToDoneAndStampsFinishedAt() {
        stepRows.get(STEP).setStatus(ObJourneyStepStatus.IN_PROGRESS);

        ObJourneyStep completed = service.complete(STEP, OWNER);

        assertThat(completed.getStatus()).isEqualTo(ObJourneyStepStatus.DONE);
        assertThat(completed.getFinishedAt()).isNotNull();
    }

    @Test
    void completeRefusesAStepThatIsNotInProgress() {
        assertThatThrownBy(() -> service.complete(STEP, OWNER))
                .isInstanceOf(InvalidStepTransitionException.class);
    }

    @Test
    void completeRefusesACallerWhoIsNeitherOwnerNorBackupOwner() {
        stepRows.get(STEP).setStatus(ObJourneyStepStatus.IN_PROGRESS);

        assertThatThrownBy(() -> service.complete(STEP, STRANGER))
                .isInstanceOf(NotStepOwnerException.class);
    }

    // ── complete · C-106's completion gate ───────────────────────────────────

    @Test
    void completeRefusesAnUnansweredMandatoryItem() {
        ObJourneyStep step = stepRows.get(STEP);
        step.setStatus(ObJourneyStepStatus.IN_PROGRESS);
        // No templateItemId — an admin's ad-hoc item. Defaults to mandatory.
        ObJourneyStepItem item = stepItem(1L, null, null, "Signed agreement received");
        when(stepItems.findByStepIdOrderBySequenceAsc(STEP)).thenReturn(List.of(item));

        assertThatThrownBy(() -> service.complete(STEP, OWNER))
                .isInstanceOf(CompletionGateException.class)
                .satisfies(e -> {
                    CompletionGateException gate = (CompletionGateException) e;
                    assertThat(gate.unansweredMandatoryItems()).containsExactly("Signed agreement received");
                    assertThat(gate.missingRequiredDocs()).isZero();
                    assertThat(gate.signoffMissing()).isFalse();
                });
    }

    @Test
    void completeAllowsAnUnansweredNonMandatoryItem() {
        ObJourneyStep step = stepRows.get(STEP);
        step.setStatus(ObJourneyStepStatus.IN_PROGRESS);
        ObJourneyStepItem item = stepItem(1L, 900L, null, "Optional note");
        when(stepItems.findByStepIdOrderBySequenceAsc(STEP)).thenReturn(List.of(item));
        when(templateStepItems.findAllById(List.of(900L))).thenReturn(List.of(templateItem(900L, false)));

        ObJourneyStep completed = service.complete(STEP, OWNER);

        assertThat(completed.getStatus()).isEqualTo(ObJourneyStepStatus.DONE);
    }

    @Test
    void completeRefusesAnUnansweredMandatoryTemplateBackedItem() {
        ObJourneyStep step = stepRows.get(STEP);
        step.setStatus(ObJourneyStepStatus.IN_PROGRESS);
        ObJourneyStepItem item = stepItem(1L, 900L, null, "Signed PO attached?");
        when(stepItems.findByStepIdOrderBySequenceAsc(STEP)).thenReturn(List.of(item));
        when(templateStepItems.findAllById(List.of(900L))).thenReturn(List.of(templateItem(900L, true)));

        assertThatThrownBy(() -> service.complete(STEP, OWNER))
                .isInstanceOf(CompletionGateException.class);
    }

    @Test
    void completeAllowsAnAnsweredMandatoryItem() {
        ObJourneyStep step = stepRows.get(STEP);
        step.setStatus(ObJourneyStepStatus.IN_PROGRESS);
        ObJourneyStepItem item = stepItem(1L, null, true, "Signed agreement received");
        when(stepItems.findByStepIdOrderBySequenceAsc(STEP)).thenReturn(List.of(item));

        ObJourneyStep completed = service.complete(STEP, OWNER);

        assertThat(completed.getStatus()).isEqualTo(ObJourneyStepStatus.DONE);
    }

    @Test
    void completeRefusesWhenARequiredDocumentIsNotAttached() {
        ObJourneyStep step = stepRows.get(STEP);
        step.setStatus(ObJourneyStepStatus.IN_PROGRESS);
        step.setTemplateStepId(400L);
        when(templateStepDocs.findByStepIdOrderBySequenceAsc(400L))
                .thenReturn(List.of(templateDoc(true), templateDoc(false)));
        when(attachments.countByStepIdAndScanStatusAndDeletedAtIsNull(STEP, ObAttachmentScanStatus.CLEAN))
                .thenReturn(0L);

        assertThatThrownBy(() -> service.complete(STEP, OWNER))
                .isInstanceOf(CompletionGateException.class)
                .satisfies(e -> assertThat(((CompletionGateException) e).missingRequiredDocs()).isEqualTo(1));
    }

    @Test
    void completeAllowsWhenEveryRequiredDocumentIsAttachedAndClean() {
        ObJourneyStep step = stepRows.get(STEP);
        step.setStatus(ObJourneyStepStatus.IN_PROGRESS);
        step.setTemplateStepId(400L);
        when(templateStepDocs.findByStepIdOrderBySequenceAsc(400L)).thenReturn(List.of(templateDoc(true)));
        when(attachments.countByStepIdAndScanStatusAndDeletedAtIsNull(STEP, ObAttachmentScanStatus.CLEAN))
                .thenReturn(1L);

        ObJourneyStep completed = service.complete(STEP, OWNER);

        assertThat(completed.getStatus()).isEqualTo(ObJourneyStepStatus.DONE);
    }

    @Test
    void completeIgnoresDocumentChecklistWhenTheStepHasNoTemplateStepId() {
        ObJourneyStep step = stepRows.get(STEP);
        step.setStatus(ObJourneyStepStatus.IN_PROGRESS);

        ObJourneyStep completed = service.complete(STEP, OWNER);

        assertThat(completed.getStatus()).isEqualTo(ObJourneyStepStatus.DONE);
    }

    @Test
    void completeRefusesWhenSignoffIsRequiredButNotYetSigned() {
        ObJourneyStep step = stepRows.get(STEP);
        step.setStatus(ObJourneyStepStatus.IN_PROGRESS);
        step.setRequiresSignoff(true);

        assertThatThrownBy(() -> service.complete(STEP, OWNER))
                .isInstanceOf(CompletionGateException.class)
                .satisfies(e -> assertThat(((CompletionGateException) e).signoffMissing()).isTrue());
    }

    @Test
    void completeAllowsWhenTheRequiredSignoffIsSigned() {
        ObJourneyStep step = stepRows.get(STEP);
        step.setStatus(ObJourneyStepStatus.IN_PROGRESS);
        step.setRequiresSignoff(true);
        when(signoffs.existsByStepIdAndKindAndStatus(STEP, ObSignoffKind.STEP, ObSignoffStatus.SIGNED))
                .thenReturn(true);

        ObJourneyStep completed = service.complete(STEP, OWNER);

        assertThat(completed.getStatus()).isEqualTo(ObJourneyStepStatus.DONE);
    }

    @Test
    void completeIgnoresSignoffWhenTheStepDoesNotRequireOne() {
        ObJourneyStep step = stepRows.get(STEP);
        step.setStatus(ObJourneyStepStatus.IN_PROGRESS);
        step.setRequiresSignoff(false);

        ObJourneyStep completed = service.complete(STEP, OWNER);

        assertThat(completed.getStatus()).isEqualTo(ObJourneyStepStatus.DONE);
    }

    @Test
    void completeReportsAllThreeGateFailuresTogether() {
        ObJourneyStep step = stepRows.get(STEP);
        step.setStatus(ObJourneyStepStatus.IN_PROGRESS);
        step.setTemplateStepId(400L);
        step.setRequiresSignoff(true);
        when(stepItems.findByStepIdOrderBySequenceAsc(STEP))
                .thenReturn(List.of(stepItem(1L, null, null, "Signed agreement received")));
        when(templateStepDocs.findByStepIdOrderBySequenceAsc(400L)).thenReturn(List.of(templateDoc(true)));
        when(attachments.countByStepIdAndScanStatusAndDeletedAtIsNull(STEP, ObAttachmentScanStatus.CLEAN))
                .thenReturn(0L);

        assertThatThrownBy(() -> service.complete(STEP, OWNER))
                .isInstanceOf(CompletionGateException.class)
                .satisfies(e -> {
                    CompletionGateException gate = (CompletionGateException) e;
                    assertThat(gate.unansweredMandatoryItems()).containsExactly("Signed agreement received");
                    assertThat(gate.missingRequiredDocs()).isEqualTo(1);
                    assertThat(gate.signoffMissing()).isTrue();
                });
    }

    private static ObJourneyStepItem stepItem(long id, Long templateItemId, Boolean answer, String label) {
        ObJourneyStepItem item = new ObJourneyStepItem();
        item.setId(id);
        item.setStepId(STEP);
        item.setTemplateItemId(templateItemId);
        item.setLabel(label);
        item.setAnswer(answer);
        return item;
    }

    private static ObJourneyTemplateStepItem templateItem(long id, boolean mandatory) {
        ObJourneyTemplateStepItem item = new ObJourneyTemplateStepItem();
        item.setId(id);
        item.setMandatory(mandatory);
        return item;
    }

    private static ObJourneyTemplateStepDoc templateDoc(boolean required) {
        ObJourneyTemplateStepDoc doc = new ObJourneyTemplateStepDoc();
        doc.setId(1L);
        doc.setRequired(required);
        return doc;
    }

    // ── block ─────────────────────────────────────────────────────────────

    @Test
    void blockMovesAnInProgressStepToBlockedWithItsReason() {
        stepRows.get(STEP).setStatus(ObJourneyStepStatus.IN_PROGRESS);

        ObJourneyStep blocked = service.block(STEP, OWNER, "client-unresponsive", "Awaiting signed PO");

        assertThat(blocked.getStatus()).isEqualTo(ObJourneyStepStatus.BLOCKED);
        assertThat(blocked.getBlockedReasonCode()).isEqualTo("client-unresponsive");
        assertThat(blocked.getBlockedNote()).isEqualTo("Awaiting signed PO");
    }

    @Test
    void blockRefusesAStepThatIsNotInProgress() {
        assertThatThrownBy(() -> service.block(STEP, OWNER, "client-unresponsive", null))
                .isInstanceOf(InvalidStepTransitionException.class);
    }

    // ── waiting-on-client ────────────────────────────────────────────────

    @Test
    void waitOnClientMovesAnInProgressStepToWaitingOnClient() {
        stepRows.get(STEP).setStatus(ObJourneyStepStatus.IN_PROGRESS);

        ObJourneyStep waiting = service.waitOnClient(STEP, OWNER);

        assertThat(waiting.getStatus()).isEqualTo(ObJourneyStepStatus.WAITING_ON_CLIENT);
    }

    @Test
    void waitOnClientRefusesAStepThatIsNotInProgress() {
        assertThatThrownBy(() -> service.waitOnClient(STEP, OWNER))
                .isInstanceOf(InvalidStepTransitionException.class);
    }

    /** C-105 · the pause is a row, not just a status flip. */
    @Test
    void waitOnClientRecordsAPausedClockEventAttributedToTheClient() {
        stepRows.get(STEP).setStatus(ObJourneyStepStatus.IN_PROGRESS);

        service.waitOnClient(STEP, OWNER);

        ArgumentCaptor<ObStepClockEvent> captor = ArgumentCaptor.forClass(ObStepClockEvent.class);
        verify(clockRecorder).record(captor.capture());
        ObStepClockEvent event = captor.getValue();
        assertThat(event.getStepId()).isEqualTo(STEP);
        assertThat(event.getJourneyId()).isEqualTo(JOURNEY);
        assertThat(event.getEventType()).isEqualTo(ObStepClockEventType.PAUSED);
        assertThat(event.getPauseReason()).isEqualTo("WAITING_ON_CLIENT");
        assertThat(event.getAttributedTo()).isEqualTo(ObStepClockAttribution.CLIENT);
        assertThat(event.getActorId()).isEqualTo(OWNER);
        assertThat(event.getOccurredAt()).isNotNull();
    }

    // ── start · due_at (C-105) ───────────────────────────────────────────

    /**
     * {@code pendingStep()}'s {@code tatDays=2} against the 9-hour test
     * calendar is an 18-hour budget; the stubbed {@code addWorkingHours}
     * adds it as a raw duration onto whatever {@code startedAt} came out.
     */
    @Test
    void startComputesDueAtFromTheWorkingCalendarTatBudget() {
        ObJourneyStep started = service.start(STEP, OWNER);

        assertThat(started.getDueAt()).isEqualTo(started.getStartedAt().plusSeconds(18 * 3600L));
    }

    // ── resume ────────────────────────────────────────────────────────────

    @Test
    void resumeMovesABlockedStepBackToInProgressAndClearsTheReason() {
        ObJourneyStep step = stepRows.get(STEP);
        step.setStatus(ObJourneyStepStatus.BLOCKED);
        step.setBlockedReasonCode("client-unresponsive");
        step.setBlockedNote("Awaiting signed PO");

        ObJourneyStep resumed = service.resume(STEP, OWNER);

        assertThat(resumed.getStatus()).isEqualTo(ObJourneyStepStatus.IN_PROGRESS);
        assertThat(resumed.getBlockedReasonCode()).isNull();
        assertThat(resumed.getBlockedNote()).isNull();
    }

    /** A resume from BLOCKED never paused the clock, so it writes no event. */
    @Test
    void resumeFromBlockedRecordsNoClockEvent() {
        ObJourneyStep step = stepRows.get(STEP);
        step.setStatus(ObJourneyStepStatus.BLOCKED);
        step.setBlockedReasonCode("client-unresponsive");

        service.resume(STEP, OWNER);

        verify(clockRecorder, never()).record(any());
    }

    @Test
    void resumeMovesAWaitingOnClientStepBackToInProgress() {
        ObJourneyStep step = stepRows.get(STEP);
        step.setStatus(ObJourneyStepStatus.WAITING_ON_CLIENT);
        step.setDueAt(Instant.parse("2026-10-01T00:00:00Z"));
        stubLastPause(Instant.parse("2026-09-28T00:00:00Z"));

        ObJourneyStep resumed = service.resume(STEP, OWNER);

        assertThat(resumed.getStatus()).isEqualTo(ObJourneyStepStatus.IN_PROGRESS);
    }

    @Test
    void resumeLeavesDueAtUntouched() {
        ObJourneyStep step = stepRows.get(STEP);
        step.setStatus(ObJourneyStepStatus.BLOCKED);
        step.setBlockedReasonCode("client-unresponsive");
        Instant dueAt = Instant.parse("2026-10-01T00:00:00Z");
        step.setDueAt(dueAt);

        ObJourneyStep resumed = service.resume(STEP, OWNER);

        assertThat(resumed.getDueAt()).isEqualTo(dueAt);
    }

    /**
     * C-105 · the working hours still owed as of the pause — computed
     * between the last {@code PAUSED} row's {@code occurredAt} and the old
     * {@code due_at} — are what {@code addWorkingHours} is asked to place
     * from {@code resumedAt}. The stubbed calendar has no weekends inside
     * this narrow window, so the raw-duration and working-hours figures
     * agree: 3 days = 72 hours owed.
     */
    @Test
    void resumeFromWaitingOnClientRecomputesDueAtFromHoursOwedAtThePause() {
        ObJourneyStep step = stepRows.get(STEP);
        step.setStatus(ObJourneyStepStatus.WAITING_ON_CLIENT);
        Instant oldDueAt = Instant.parse("2026-09-30T00:00:00Z");
        step.setDueAt(oldDueAt);
        Instant pausedAt = Instant.parse("2026-09-27T00:00:00Z");
        stubLastPause(pausedAt);

        service.resume(STEP, OWNER);

        ArgumentCaptor<BigDecimal> hoursCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<Instant> fromCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(workingHours).addWorkingHours(any(), hoursCaptor.capture());
        verify(workingHours).workingHoursBetween(fromCaptor.capture(), eq(oldDueAt));
        assertThat(fromCaptor.getValue()).isEqualTo(pausedAt);
        assertThat(hoursCaptor.getValue()).isEqualByComparingTo("72.00");
    }

    /** A step already breached when it paused owes nothing and resumes exactly at resumedAt. */
    @Test
    void resumeFromWaitingOnClientOwesZeroHoursWhenAlreadyBreachedAtThePause() {
        ObJourneyStep step = stepRows.get(STEP);
        step.setStatus(ObJourneyStepStatus.WAITING_ON_CLIENT);
        Instant oldDueAt = Instant.parse("2026-09-20T00:00:00Z");
        step.setDueAt(oldDueAt);
        // Paused after its own due date — already breached.
        stubLastPause(Instant.parse("2026-09-25T00:00:00Z"));

        ObJourneyStep resumed = service.resume(STEP, OWNER);

        ArgumentCaptor<BigDecimal> hoursCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<Instant> resumedAtCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(workingHours).addWorkingHours(resumedAtCaptor.capture(), hoursCaptor.capture());
        assertThat(hoursCaptor.getValue()).isEqualByComparingTo("0.00");
        assertThat(resumed.getDueAt()).isEqualTo(resumedAtCaptor.getValue());
    }

    /** C-105 · the resume is a row too, attributed back to internal — the wait is over. */
    @Test
    void resumeFromWaitingOnClientRecordsAResumedClockEvent() {
        ObJourneyStep step = stepRows.get(STEP);
        step.setStatus(ObJourneyStepStatus.WAITING_ON_CLIENT);
        step.setDueAt(Instant.parse("2026-10-01T00:00:00Z"));
        stubLastPause(Instant.parse("2026-09-28T00:00:00Z"));

        service.resume(STEP, OWNER);

        ArgumentCaptor<ObStepClockEvent> captor = ArgumentCaptor.forClass(ObStepClockEvent.class);
        verify(clockRecorder).record(captor.capture());
        ObStepClockEvent event = captor.getValue();
        assertThat(event.getStepId()).isEqualTo(STEP);
        assertThat(event.getJourneyId()).isEqualTo(JOURNEY);
        assertThat(event.getEventType()).isEqualTo(ObStepClockEventType.RESUMED);
        assertThat(event.getPauseReason()).isNull();
        assertThat(event.getActorId()).isEqualTo(OWNER);
    }

    @Test
    void resumeFromWaitingOnClientWithNoPausedEventOnRecordFailsLoudly() {
        stepRows.get(STEP).setStatus(ObJourneyStepStatus.WAITING_ON_CLIENT);
        stepRows.get(STEP).setDueAt(Instant.parse("2026-10-01T00:00:00Z"));
        // No stubLastPause(...) — the default wireFakes() stub returns empty.

        assertThatThrownBy(() -> service.resume(STEP, OWNER))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void resumeRefusesAStepThatIsNeitherBlockedNorWaitingOnClient() {
        assertThatThrownBy(() -> service.resume(STEP, OWNER))
                .isInstanceOf(InvalidStepTransitionException.class);
    }

    @Test
    void resumeRefusesACallerWhoIsNeitherOwnerNorBackupOwner() {
        stepRows.get(STEP).setStatus(ObJourneyStepStatus.BLOCKED);
        stepRows.get(STEP).setBlockedReasonCode("client-unresponsive");

        assertThatThrownBy(() -> service.resume(STEP, STRANGER))
                .isInstanceOf(NotStepOwnerException.class);
    }

    /** Stubs the most recent {@code PAUSED} row {@code resume} needs, at the given instant. */
    private void stubLastPause(Instant occurredAt) {
        ObStepClockEvent paused = new ObStepClockEvent();
        paused.setStepId(STEP);
        paused.setJourneyId(JOURNEY);
        paused.setEventType(ObStepClockEventType.PAUSED);
        paused.setPauseReason("WAITING_ON_CLIENT");
        paused.setAttributedTo(ObStepClockAttribution.CLIENT);
        paused.setOccurredAt(occurredAt);
        when(clockEvents.findFirstByStepIdAndEventTypeOrderByOccurredAtDescIdDesc(STEP, ObStepClockEventType.PAUSED))
                .thenReturn(Optional.of(paused));
    }

    // ── skip (C-107) ─────────────────────────────────────────────────────

    private static final String MANAGER_ROLE = "OB_MANAGER";
    private static final String ADMIN_ROLE = "OB_ADMIN";
    private static final String WRONG_ROLE = "OB_SALES";

    @Test
    void skipMovesAnyNonTerminalStatusToSkippedWithReasonAndActor() {
        ObJourneyStep skipped = service.skip(STEP, STRANGER, MANAGER_ROLE, "client does not need this service");

        assertThat(skipped.getStatus()).isEqualTo(ObJourneyStepStatus.SKIPPED);
        assertThat(skipped.getSkipReason()).isEqualTo("client does not need this service");
        assertThat(skipped.getSkippedBy()).isEqualTo(STRANGER);
    }

    @Test
    void skipIsAllowedForAnAdminToo() {
        ObJourneyStep skipped = service.skip(STEP, STRANGER, ADMIN_ROLE, "duplicate service");

        assertThat(skipped.getStatus()).isEqualTo(ObJourneyStepStatus.SKIPPED);
    }

    @Test
    void skipIsNotRowScopedByOwnership() {
        // STRANGER owns nothing on this step, and that is exactly the point of
        // an override — see the service's own class javadoc on skip().
        ObJourneyStep skipped = service.skip(STEP, STRANGER, MANAGER_ROLE, "override, not owned");

        assertThat(skipped.getStatus()).isEqualTo(ObJourneyStepStatus.SKIPPED);
    }

    @Test
    void skipWorksFromAnyNonTerminalStatusIncludingBlocked() {
        stepRows.get(STEP).setStatus(ObJourneyStepStatus.BLOCKED);
        stepRows.get(STEP).setBlockedReasonCode("client-unresponsive");

        ObJourneyStep skipped = service.skip(STEP, OWNER, MANAGER_ROLE, "no longer needed");

        assertThat(skipped.getStatus()).isEqualTo(ObJourneyStepStatus.SKIPPED);
    }

    @Test
    void skipDoesNotRequireTheJourneyGateToBeOpen() {
        journeyRows.get(JOURNEY).setGateStatus(ObGateStatus.LOCKED);

        ObJourneyStep skipped = service.skip(STEP, OWNER, MANAGER_ROLE, "override while locked");

        assertThat(skipped.getStatus()).isEqualTo(ObJourneyStepStatus.SKIPPED);
    }

    @Test
    void skipRefusesACallerHoldingTheWrongOnboardingRole() {
        assertThatThrownBy(() -> service.skip(STEP, OWNER, WRONG_ROLE, "not a manager"))
                .isInstanceOf(NotAnOnboardingModeratorException.class);
    }

    @Test
    void skipAnswersNotFoundForACallerWithNoOnboardingStandingAtAll() {
        assertThatThrownBy(() -> service.skip(STEP, OWNER, null, "no onboarding role"))
                .isInstanceOf(JourneyStepNotFoundException.class);
    }

    @Test
    void skipRefusesAnAlreadyDoneStep() {
        stepRows.get(STEP).setStatus(ObJourneyStepStatus.DONE);

        assertThatThrownBy(() -> service.skip(STEP, OWNER, MANAGER_ROLE, "too late"))
                .isInstanceOf(StepAlreadyTerminalException.class);
    }

    @Test
    void skipRefusesAnAlreadySkippedStep() {
        stepRows.get(STEP).setStatus(ObJourneyStepStatus.SKIPPED);

        assertThatThrownBy(() -> service.skip(STEP, OWNER, MANAGER_ROLE, "already gone"))
                .isInstanceOf(StepAlreadyTerminalException.class);
    }

    @Test
    void skipFailsCleanlyForAnUnknownStep() {
        assertThatThrownBy(() -> service.skip(404L, OWNER, MANAGER_ROLE, "no such step"))
                .isInstanceOf(JourneyStepNotFoundException.class);
    }

    @Test
    void skipAppendsAGenesisHistoryRowOnTheFirstSkipOfAJourney() {
        service.skip(STEP, OWNER, MANAGER_ROLE, "first ever event on this journey");

        assertThat(historyRows).hasSize(1);
        ObStepHistory entry = historyRows.get(0);
        assertThat(entry.getJourneyId()).isEqualTo(JOURNEY);
        assertThat(entry.getStepId()).isEqualTo(STEP);
        assertThat(entry.getObClientId()).isEqualTo(journeyRows.get(JOURNEY).getObClientId());
        assertThat(entry.getEventType()).isEqualTo("SKIPPED");
        assertThat(entry.getOldValue()).isEqualTo("PENDING");
        assertThat(entry.getNewValue()).isEqualTo("SKIPPED");
        assertThat(entry.getActorId()).isEqualTo(OWNER);
        assertThat(entry.getActorType()).isEqualTo("USER");
        assertThat(entry.getRemarks()).isEqualTo("first ever event on this journey");
        assertThat(entry.getPrevHash()).isNull();
        assertThat(entry.getRowHash()).isNotBlank();
    }

    @Test
    void skipChainsOntoAPriorHistoryRowOfTheSameJourney() {
        ObStepHistory earlier = new ObStepHistory();
        earlier.setId(1L);
        earlier.setJourneyId(JOURNEY);
        earlier.setEventType("STEP_ACTIVATED");
        earlier.setActorType("SYSTEM");
        earlier.setRowHash("a".repeat(64));
        historyRows.add(earlier);

        service.skip(STEP, OWNER, MANAGER_ROLE, "second event on this journey");

        assertThat(historyRows).hasSize(2);
        ObStepHistory entry = historyRows.get(1);
        assertThat(entry.getPrevHash()).isEqualTo("a".repeat(64));
        assertThat(entry.getRowHash()).isNotEqualTo(entry.getPrevHash());
    }

    // ── update (C-108) ───────────────────────────────────────────────────

    private static final long NEW_OWNER = 20L;
    private static final long NEW_BACKUP = 21L;

    /** {@code STRANGER} as the caller throughout — see {@link #updateIsNotRowScopedByOwnership}. */
    private ObJourneyStep update(JsonNullable<Long> ownerUserId, JsonNullable<Long> backupOwnerUserId,
            Integer tatDays, JsonNullable<java.time.Instant> dueAt) {
        return service.update(STEP, STRANGER, MANAGER_ROLE, ownerUserId, backupOwnerUserId, tatDays, dueAt);
    }

    @Test
    void updateReassignsOwnerAndBackupOwner() {
        ObJourneyStep updated = update(JsonNullable.of(NEW_OWNER), JsonNullable.of(NEW_BACKUP), null,
                JsonNullable.undefined());

        assertThat(updated.getOwnerUserId()).isEqualTo(NEW_OWNER);
        assertThat(updated.getBackupOwnerUserId()).isEqualTo(NEW_BACKUP);
    }

    @Test
    void updateReplansTatDaysAndDueAt() {
        java.time.Instant newDueAt = java.time.Instant.parse("2026-11-01T00:00:00Z");

        ObJourneyStep updated = update(JsonNullable.undefined(), JsonNullable.undefined(), 5,
                JsonNullable.of(newDueAt));

        assertThat(updated.getTatDays()).isEqualTo(5);
        assertThat(updated.getDueAt()).isEqualTo(newDueAt);
    }

    @Test
    void updateLeavesAbsentFieldsUnchanged() {
        ObJourneyStep step = stepRows.get(STEP);
        long originalOwner = step.getOwnerUserId();
        long originalBackup = step.getBackupOwnerUserId();
        int originalTatDays = step.getTatDays();

        ObJourneyStep updated = update(JsonNullable.undefined(), JsonNullable.undefined(), null,
                JsonNullable.undefined());

        assertThat(updated.getOwnerUserId()).isEqualTo(originalOwner);
        assertThat(updated.getBackupOwnerUserId()).isEqualTo(originalBackup);
        assertThat(updated.getTatDays()).isEqualTo(originalTatDays);
    }

    @Test
    void updateClearsBackupOwnerOnAnExplicitNull() {
        ObJourneyStep updated = update(JsonNullable.undefined(), JsonNullable.of(null), null,
                JsonNullable.undefined());

        assertThat(updated.getBackupOwnerUserId()).isNull();
    }

    @Test
    void updateIsNotRowScopedByOwnership() {
        // STRANGER owns nothing on this step — a manager reassigning it is
        // exactly the override requireModerator (not requireOwnership) exists
        // to allow. See the service's own class javadoc on update().
        ObJourneyStep updated = update(JsonNullable.of(NEW_OWNER), JsonNullable.undefined(), null,
                JsonNullable.undefined());

        assertThat(updated.getOwnerUserId()).isEqualTo(NEW_OWNER);
    }

    @Test
    void updateRefusesACallerHoldingTheWrongOnboardingRole() {
        assertThatThrownBy(() -> service.update(STEP, STRANGER, WRONG_ROLE,
                JsonNullable.of(NEW_OWNER), JsonNullable.undefined(), null, JsonNullable.undefined()))
                .isInstanceOf(NotAnOnboardingModeratorException.class);
    }

    @Test
    void updateAnswersNotFoundForACallerWithNoOnboardingStandingAtAll() {
        assertThatThrownBy(() -> service.update(STEP, STRANGER, null,
                JsonNullable.of(NEW_OWNER), JsonNullable.undefined(), null, JsonNullable.undefined()))
                .isInstanceOf(JourneyStepNotFoundException.class);
    }

    @Test
    void updateRefusesAnAlreadyDoneStep() {
        stepRows.get(STEP).setStatus(ObJourneyStepStatus.DONE);

        assertThatThrownBy(() -> update(JsonNullable.of(NEW_OWNER), JsonNullable.undefined(), null,
                JsonNullable.undefined()))
                .isInstanceOf(StepAlreadyTerminalException.class);
    }

    @Test
    void updateRefusesAnAlreadySkippedStep() {
        stepRows.get(STEP).setStatus(ObJourneyStepStatus.SKIPPED);

        assertThatThrownBy(() -> update(JsonNullable.of(NEW_OWNER), JsonNullable.undefined(), null,
                JsonNullable.undefined()))
                .isInstanceOf(StepAlreadyTerminalException.class);
    }

    @Test
    void updateWritesOneFieldChangedHistoryRowPerChangedField() {
        update(JsonNullable.of(NEW_OWNER), JsonNullable.of(NEW_BACKUP), 5, JsonNullable.undefined());

        assertThat(historyRows).hasSize(3);
        assertThat(historyRows).allSatisfy(entry -> assertThat(entry.getEventType()).isEqualTo("FIELD_CHANGED"));
        assertThat(historyRows.stream().map(ObStepHistory::getFieldName))
                .containsExactlyInAnyOrder("owner_user_id", "backup_owner_user_id", "tat_days");
    }

    @Test
    void updateWritesNoHistoryRowWhenNothingActuallyChanges() {
        ObJourneyStep step = stepRows.get(STEP);
        update(JsonNullable.of(step.getOwnerUserId()), JsonNullable.of(step.getBackupOwnerUserId()),
                step.getTatDays(), JsonNullable.undefined());

        assertThat(historyRows).isEmpty();
    }

    @Test
    void updateFailsCleanlyForAnUnknownStep() {
        assertThatThrownBy(() -> service.update(404L, STRANGER, MANAGER_ROLE,
                JsonNullable.of(NEW_OWNER), JsonNullable.undefined(), null, JsonNullable.undefined()))
                .isInstanceOf(JourneyStepNotFoundException.class);
    }
}
