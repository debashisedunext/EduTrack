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
import com.edunext.edutrack.domain.onboarding.ObStepClockActorType;
import com.edunext.edutrack.domain.onboarding.ObStepClockAttribution;
import com.edunext.edutrack.domain.onboarding.ObStepClockEvent;
import com.edunext.edutrack.domain.onboarding.ObStepClockEventRepository;
import com.edunext.edutrack.domain.onboarding.ObStepClockEventType;
import com.edunext.edutrack.domain.onboarding.ObStepHistory;
import com.edunext.edutrack.domain.onboarding.ObStepHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.openapitools.jackson.nullable.JsonNullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
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
    private final ObJourneyDependencyRelease dependencyRelease = mock(ObJourneyDependencyRelease.class);

    private final ObJourneyStepLifecycleService service = new ObJourneyStepLifecycleService(
            journeySteps, journeys, stepItems, templateStepItems, templateStepDocs, attachments, signoffs,
            stepJournal, workingHours, workingCalendars, clockEvents, clockRecorder, dependencyRelease);

    @BeforeEach
    void wireFakes() {
        when(journeySteps.findById(any())).thenAnswer(inv ->
                Optional.ofNullable(stepRows.get(inv.<Long>getArgument(0))));
        // C-119 · activateEligibleSteps' own read, backed by the same map so
        // a step it flips to IN_PROGRESS is visible to the next call exactly
        // as it would be through a real repository within one transaction.
        when(journeySteps.findByJourneyIdOrderBySequenceAsc(any())).thenAnswer(inv -> {
            Long journeyId = inv.getArgument(0);
            return stepRows.values().stream()
                    .filter(s -> s.getJourneyId().equals(journeyId))
                    .sorted(Comparator.comparingInt(ObJourneyStep::getSequence))
                    .toList();
        });
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

    // ── start · dependency graph (C-119) ─────────────────────────────────

    @Test
    void startRefusesWhileItsDependencyHasNotFinished() {
        ObJourneyStep blocker = pendingStep();
        blocker.setId(701L);
        blocker.setSequence(0);
        blocker.setName("Earlier service");
        blocker.setStatus(ObJourneyStepStatus.IN_PROGRESS);
        stepRows.put(701L, blocker);
        stepRows.get(STEP).setDependsOnStepId(701L);

        assertThatThrownBy(() -> service.start(STEP, OWNER))
                .isInstanceOf(StepDependencyNotSatisfiedException.class)
                .hasMessageContaining("701")
                .hasMessageContaining("Earlier service");
    }

    @Test
    void startAllowsAStepWhoseDependencyIsDone() {
        ObJourneyStep blocker = pendingStep();
        blocker.setId(701L);
        blocker.setStatus(ObJourneyStepStatus.DONE);
        stepRows.put(701L, blocker);
        stepRows.get(STEP).setDependsOnStepId(701L);

        ObJourneyStep started = service.start(STEP, OWNER);

        assertThat(started.getStatus()).isEqualTo(ObJourneyStepStatus.IN_PROGRESS);
    }

    @Test
    void startAllowsAStepWhoseDependencyWasSkipped() {
        ObJourneyStep blocker = pendingStep();
        blocker.setId(701L);
        blocker.setStatus(ObJourneyStepStatus.SKIPPED);
        stepRows.put(701L, blocker);
        stepRows.get(STEP).setDependsOnStepId(701L);

        ObJourneyStep started = service.start(STEP, OWNER);

        assertThat(started.getStatus()).isEqualTo(ObJourneyStepStatus.IN_PROGRESS);
    }

    @Test
    void startTreatsADanglingDependencyAsAnInvariantViolation() {
        // The composite FK guarantees this cannot happen for real — see
        // requireDependencySatisfied's own javadoc.
        stepRows.get(STEP).setDependsOnStepId(999L);

        assertThatThrownBy(() -> service.start(STEP, OWNER))
                .isInstanceOf(IllegalStateException.class);
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

    // ── revertOnClientObjection (B-117) ──────────────────────────────────

    private static final long CONTACT = 42L;

    @Test
    void objectionRevertsAWaitingOnClientStepToInProgress() {
        ObJourneyStep step = stepRows.get(STEP);
        step.setStatus(ObJourneyStepStatus.WAITING_ON_CLIENT);
        step.setDueAt(Instant.parse("2026-10-01T00:00:00Z"));
        stubLastPause(Instant.parse("2026-09-28T00:00:00Z"));

        ObJourneyStepLifecycleService.ObjectionResult result =
                service.revertOnClientObjection(STEP, CONTACT, "The invoice total is wrong.");

        assertThat(result.stepReverted()).isTrue();
        assertThat(result.ownerUserId()).isEqualTo(OWNER);
        assertThat(step.getStatus()).isEqualTo(ObJourneyStepStatus.IN_PROGRESS);
    }

    /**
     * "Our clock resumes" — the backlog's own line. A wait pauses the clock
     * (C-105); an objection is one of the ways it ends, exactly like a staff
     * {@link #resumeMovesAWaitingOnClientStepBackToInProgress} resume, and
     * reuses the identical {@code due_at} recomputation.
     */
    @Test
    void objectionFromWaitingOnClientRecomputesDueAtAndRecordsAResumedClockEvent() {
        ObJourneyStep step = stepRows.get(STEP);
        step.setStatus(ObJourneyStepStatus.WAITING_ON_CLIENT);
        Instant oldDueAt = Instant.parse("2026-09-30T00:00:00Z");
        step.setDueAt(oldDueAt);
        Instant pausedAt = Instant.parse("2026-09-27T00:00:00Z");
        stubLastPause(pausedAt);

        service.revertOnClientObjection(STEP, CONTACT, "Please re-check the figures.");

        ArgumentCaptor<BigDecimal> hoursCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(workingHours).addWorkingHours(any(), hoursCaptor.capture());
        verify(workingHours).workingHoursBetween(eq(pausedAt), eq(oldDueAt));
        assertThat(hoursCaptor.getValue()).isEqualByComparingTo("72.00");

        ArgumentCaptor<ObStepClockEvent> eventCaptor = ArgumentCaptor.forClass(ObStepClockEvent.class);
        verify(clockRecorder).record(eventCaptor.capture());
        ObStepClockEvent event = eventCaptor.getValue();
        assertThat(event.getStepId()).isEqualTo(STEP);
        assertThat(event.getJourneyId()).isEqualTo(JOURNEY);
        assertThat(event.getEventType()).isEqualTo(ObStepClockEventType.RESUMED);
        assertThat(event.getAttributedTo()).isEqualTo(ObStepClockAttribution.INTERNAL);
        // SYSTEM/null, not the staff USER/OWNER pairing a manual resume
        // writes — the actor here is the client, and ObStepClockActorType
        // has no CLIENT member to name them with.
        assertThat(event.getActorType()).isEqualTo(ObStepClockActorType.SYSTEM);
        assertThat(event.getActorId()).isNull();
    }

    @Test
    void objectionFromInProgressRevertsWithNoClockEvent() {
        // A sign-off requested without ever pausing the step — nothing here
        // paused the clock, so nothing needs to give it back.
        stepRows.get(STEP).setStatus(ObJourneyStepStatus.IN_PROGRESS);

        ObJourneyStepLifecycleService.ObjectionResult result =
                service.revertOnClientObjection(STEP, CONTACT, "Not what we agreed.");

        assertThat(result.stepReverted()).isTrue();
        verify(clockRecorder, never()).record(any());
        verify(workingHours, never()).addWorkingHours(any(), any());
    }

    @Test
    void objectionOnADoneStepDoesNotUnwindCompletion() {
        // Un-completing a step that may already have activated siblings and
        // settled the journey is a cascade this task does not ask for —
        // completeOnClientAcceptance's own precedent for the same caller.
        ObJourneyStep step = stepRows.get(STEP);
        step.setStatus(ObJourneyStepStatus.DONE);
        step.setFinishedAt(Instant.parse("2026-09-20T00:00:00Z"));

        ObJourneyStepLifecycleService.ObjectionResult result =
                service.revertOnClientObjection(STEP, CONTACT, "Too late, but noted.");

        assertThat(result.stepReverted()).isFalse();
        assertThat(step.getStatus()).isEqualTo(ObJourneyStepStatus.DONE);
        assertThat(step.getFinishedAt()).isNotNull();
        verify(clockRecorder, never()).record(any());
    }

    @Test
    void objectionOnABlockedStepDoesNotForceItBackToInProgress() {
        ObJourneyStep step = stepRows.get(STEP);
        step.setStatus(ObJourneyStepStatus.BLOCKED);
        step.setBlockedReasonCode("client-unresponsive");

        ObJourneyStepLifecycleService.ObjectionResult result =
                service.revertOnClientObjection(STEP, CONTACT, "Objecting anyway.");

        assertThat(result.stepReverted()).isFalse();
        assertThat(step.getStatus()).isEqualTo(ObJourneyStepStatus.BLOCKED);
    }

    @Test
    void objectionIsJournalledEvenWhenTheStepDoesNotRevert() {
        stepRows.get(STEP).setStatus(ObJourneyStepStatus.DONE);

        service.revertOnClientObjection(STEP, CONTACT, "Recorded for the file.");

        assertThat(historyRows).hasSize(1);
        ObStepHistory entry = historyRows.get(0);
        assertThat(entry.getOldValue()).isEqualTo("DONE");
        assertThat(entry.getNewValue()).isEqualTo("DONE");
        assertThat(entry.getRemarks()).isEqualTo("Recorded for the file.");
    }

    @Test
    void objectionWritesAClientAttributedHistoryRowNamingTheContact() {
        stepRows.get(STEP).setStatus(ObJourneyStepStatus.IN_PROGRESS);

        service.revertOnClientObjection(STEP, CONTACT, "The invoice total is wrong.");

        assertThat(historyRows).hasSize(1);
        ObStepHistory entry = historyRows.get(0);
        assertThat(entry.getJourneyId()).isEqualTo(JOURNEY);
        assertThat(entry.getStepId()).isEqualTo(STEP);
        assertThat(entry.getObClientId()).isEqualTo(journeyRows.get(JOURNEY).getObClientId());
        assertThat(entry.getEventType()).isEqualTo("OBJECTED");
        assertThat(entry.getOldValue()).isEqualTo("IN_PROGRESS");
        assertThat(entry.getNewValue()).isEqualTo("IN_PROGRESS");
        assertThat(entry.getActorType()).isEqualTo("CLIENT");
        assertThat(entry.getActorContactId()).isEqualTo(CONTACT);
        assertThat(entry.getActorId()).isNull();
        assertThat(entry.getRemarks()).isEqualTo("The invoice total is wrong.");
        assertThat(entry.getRowHash()).isNotBlank();
    }

    @Test
    void objectionReturnsTheOwnerSoTheCallerCanNotifyThem() {
        stepRows.get(STEP).setStatus(ObJourneyStepStatus.IN_PROGRESS);
        stepRows.get(STEP).setOwnerUserId(OWNER);

        ObJourneyStepLifecycleService.ObjectionResult result =
                service.revertOnClientObjection(STEP, CONTACT, "Objection");

        assertThat(result.ownerUserId()).isEqualTo(OWNER);
    }

    @Test
    void objectionFailsCleanlyForAnUnknownStep() {
        assertThatThrownBy(() -> service.revertOnClientObjection(404L, CONTACT, "no such step"))
                .isInstanceOf(JourneyStepNotFoundException.class);
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

    // ── C-119 · activateEligibleSteps ────────────────────────────────────

    @Test
    void activateEligibleStepsMovesEveryDependencyFreePendingStepToInProgress() {
        ObJourneyStep parallelA = pendingStep();
        parallelA.setId(704L);
        parallelA.setSequence(2);
        stepRows.put(704L, parallelA);
        ObJourneyStep parallelB = pendingStep();
        parallelB.setId(705L);
        parallelB.setSequence(3);
        stepRows.put(705L, parallelB);

        service.activateEligibleSteps(JOURNEY);

        assertThat(stepRows.get(704L).getStatus()).isEqualTo(ObJourneyStepStatus.IN_PROGRESS);
        assertThat(stepRows.get(705L).getStatus()).isEqualTo(ObJourneyStepStatus.IN_PROGRESS);
        assertThat(stepRows.get(704L).getStartedAt()).isNotNull();
        assertThat(stepRows.get(704L).getDueAt()).isNotNull();
    }

    @Test
    void activateEligibleStepsLeavesAStepPendingWhileItsDependencyIsStillOpen() {
        ObJourneyStep blocker = pendingStep();
        blocker.setId(701L);
        blocker.setSequence(2);
        blocker.setStatus(ObJourneyStepStatus.IN_PROGRESS);
        stepRows.put(701L, blocker);
        ObJourneyStep dependent = pendingStep();
        dependent.setId(702L);
        dependent.setSequence(3);
        dependent.setDependsOnStepId(701L);
        stepRows.put(702L, dependent);

        service.activateEligibleSteps(JOURNEY);

        assertThat(stepRows.get(702L).getStatus()).isEqualTo(ObJourneyStepStatus.PENDING);
    }

    @Test
    void activateEligibleStepsActivatesAStepWhoseDependencyWasSkipped() {
        ObJourneyStep blocker = pendingStep();
        blocker.setId(701L);
        blocker.setSequence(2);
        blocker.setStatus(ObJourneyStepStatus.SKIPPED);
        stepRows.put(701L, blocker);
        ObJourneyStep dependent = pendingStep();
        dependent.setId(702L);
        dependent.setSequence(3);
        dependent.setDependsOnStepId(701L);
        stepRows.put(702L, dependent);

        service.activateEligibleSteps(JOURNEY);

        assertThat(stepRows.get(702L).getStatus()).isEqualTo(ObJourneyStepStatus.IN_PROGRESS);
    }

    @Test
    void activateEligibleStepsIsANoOpWhileTheJourneyIsLocked() {
        journeyRows.get(JOURNEY).setGateStatus(ObGateStatus.LOCKED);
        ObJourneyStep parallelA = pendingStep();
        parallelA.setId(704L);
        parallelA.setSequence(2);
        stepRows.put(704L, parallelA);

        service.activateEligibleSteps(JOURNEY);

        assertThat(stepRows.get(704L).getStatus()).isEqualTo(ObJourneyStepStatus.PENDING);
    }

    @Test
    void activateEligibleStepsIsANoOpWhileTheJourneyIsHeldByAnother() {
        journeyRows.get(JOURNEY).setHeldByJourneyId(999L);
        ObJourneyStep parallelA = pendingStep();
        parallelA.setId(704L);
        parallelA.setSequence(2);
        stepRows.put(704L, parallelA);

        service.activateEligibleSteps(JOURNEY);

        assertThat(stepRows.get(704L).getStatus()).isEqualTo(ObJourneyStepStatus.PENDING);
    }

    @Test
    void completeTriggersActivationOfANewlyEligibleSibling() {
        stepRows.get(STEP).setStatus(ObJourneyStepStatus.IN_PROGRESS);
        ObJourneyStep dependent = pendingStep();
        dependent.setId(702L);
        dependent.setSequence(2);
        dependent.setDependsOnStepId(STEP);
        stepRows.put(702L, dependent);

        service.complete(STEP, OWNER);

        assertThat(stepRows.get(702L).getStatus()).isEqualTo(ObJourneyStepStatus.IN_PROGRESS);
    }

    @Test
    void skipTriggersActivationOfANewlyEligibleSibling() {
        ObJourneyStep dependent = pendingStep();
        dependent.setId(702L);
        dependent.setSequence(2);
        dependent.setDependsOnStepId(STEP);
        stepRows.put(702L, dependent);

        service.skip(STEP, OWNER, MANAGER_ROLE, "no longer needed");

        assertThat(stepRows.get(702L).getStatus()).isEqualTo(ObJourneyStepStatus.IN_PROGRESS);
    }

    @Test
    void skipTriggersNoActivationWhileTheJourneyIsLocked() {
        // skip() itself does not require the journey be open (see
        // skipDoesNotRequireTheJourneyGateToBeOpen above) — activation must
        // still refuse while LOCKED, exactly as it would for any other
        // trigger, rather than trusting skip()'s own relaxed gate.
        journeyRows.get(JOURNEY).setGateStatus(ObGateStatus.LOCKED);
        ObJourneyStep dependent = pendingStep();
        dependent.setId(702L);
        dependent.setSequence(2);
        dependent.setDependsOnStepId(STEP);
        stepRows.put(702L, dependent);

        service.skip(STEP, OWNER, MANAGER_ROLE, "no longer needed");

        assertThat(stepRows.get(702L).getStatus()).isEqualTo(ObJourneyStepStatus.PENDING);
    }

    // ── C-111 · the read side, and the checkbox ───────────────────────────

    /**
     * The whole reason {@code mandatoryByTemplateItemId} was extracted: the
     * panel that <em>shows</em> the gate and the service that
     * <em>enforces</em> it must agree about which items hold completion. Two
     * copies of the template join would drift, and the drift would show as a
     * screen saying a step can be completed while the server refuses it.
     */
    @Test
    void checklistReportsMandatoryTheSameWayTheCompletionGateDoes() {
        when(stepItems.findByStepIdOrderBySequenceAsc(STEP)).thenReturn(List.of(
                stepItem(1L, 100L, null, "Signed agreement received"),
                stepItem(2L, 101L, null, "Nice to have")));
        when(templateStepItems.findAllById(any())).thenReturn(List.of(
                templateItem(100L, true), templateItem(101L, false)));

        ObJourneyStepLifecycleService.ObStepChecklist checklist = service.checklistFor(STEP);

        assertThat(checklist.items())
                .extracting(entry -> entry.row().getLabel(),
                        ObJourneyStepLifecycleService.ChecklistItem::mandatory)
                .containsExactly(tuple("Signed agreement received", true), tuple("Nice to have", false));
    }

    /**
     * An ad-hoc item — one no template row governs — defaults to mandatory,
     * matching {@code requireCompletionGate}'s own {@code getOrDefault(...,
     * true)}. Defaulting the other way would show an item as optional that
     * the gate then refuses completion over.
     */
    @Test
    void checklistDefaultsAnAdHocItemToMandatory() {
        when(stepItems.findByStepIdOrderBySequenceAsc(STEP)).thenReturn(List.of(
                stepItem(1L, null, null, "Added for this client alone")));

        ObJourneyStepLifecycleService.ObStepChecklist checklist = service.checklistFor(STEP);

        assertThat(checklist.items()).singleElement()
                .extracting(ObJourneyStepLifecycleService.ChecklistItem::mandatory)
                .isEqualTo(true);
    }

    /**
     * Required entries are satisfied by counting clean attachments, exactly
     * as the gate counts them — nothing links one attachment to one entry.
     * Two required rows and one attachment means the first is satisfied and
     * the second is not.
     */
    @Test
    void checklistSatisfiesRequiredDocumentsByCountingAttachments() {
        stepRows.get(STEP).setTemplateStepId(900L);
        ObJourneyTemplateStepDoc first = templateDoc(true);
        ObJourneyTemplateStepDoc second = templateDoc(true);
        second.setId(2L);
        when(templateStepDocs.findByStepIdOrderBySequenceAsc(900L)).thenReturn(List.of(first, second));
        when(attachments.countByStepIdAndScanStatusAndDeletedAtIsNull(STEP, ObAttachmentScanStatus.CLEAN))
                .thenReturn(1L);

        ObJourneyStepLifecycleService.ObStepChecklist checklist = service.checklistFor(STEP);

        assertThat(checklist.docs())
                .extracting(ObJourneyStepLifecycleService.ChecklistDoc::satisfied)
                .containsExactly(true, false);
    }

    /** An optional entry never holds the gate, so it is never outstanding. */
    @Test
    void checklistNeverReportsAnOptionalDocumentAsOutstanding() {
        stepRows.get(STEP).setTemplateStepId(900L);
        when(templateStepDocs.findByStepIdOrderBySequenceAsc(900L)).thenReturn(List.of(templateDoc(false)));
        when(attachments.countByStepIdAndScanStatusAndDeletedAtIsNull(STEP, ObAttachmentScanStatus.CLEAN))
                .thenReturn(0L);

        ObJourneyStepLifecycleService.ObStepChecklist checklist = service.checklistFor(STEP);

        assertThat(checklist.docs()).singleElement()
                .extracting(ObJourneyStepLifecycleService.ChecklistDoc::satisfied)
                .isEqualTo(true);
    }

    /**
     * Reading a step is not acting on it — plan §9's "read-only for anybody
     * else's step" is a statement that everybody may read one. The method
     * takes no caller at all, which is the point.
     */
    @Test
    void checklistIsReadableWithoutBeingTheStepOwner() {
        when(stepItems.findByStepIdOrderBySequenceAsc(STEP)).thenReturn(List.of(
                stepItem(1L, 100L, null, "Signed agreement received")));
        when(templateStepItems.findAllById(any())).thenReturn(List.of(templateItem(100L, true)));

        assertThat(service.checklistFor(STEP).items()).hasSize(1);
    }

    @Test
    void answeringAnItemRecordsWhoAnsweredItAndWhen() {
        stepRows.get(STEP).setStatus(ObJourneyStepStatus.IN_PROGRESS);
        when(stepItems.findById(1L)).thenReturn(Optional.of(
                stepItem(1L, 100L, null, "Signed agreement received")));

        ObJourneyStepItem answered = service.answerItem(1L, OWNER, true);

        assertThat(answered.getAnswer()).isTrue();
        assertThat(answered.getAnsweredBy()).isEqualTo(OWNER);
        assertThat(answered.getAnsweredAt()).isNotNull();
    }

    /**
     * Unticking returns the item to unanswered and takes the remark with it:
     * a remark explains an answer, and keeping one after clearing the other
     * leaves a reason for a decision no longer recorded.
     */
    @Test
    void untickingAnItemReturnsItToUnansweredAndClearsTheRemark() {
        stepRows.get(STEP).setStatus(ObJourneyStepStatus.IN_PROGRESS);
        ObJourneyStepItem item = stepItem(1L, 100L, Boolean.TRUE, "Signed agreement received");
        item.setRemark("was fine");
        item.setAnsweredBy(OWNER);
        when(stepItems.findById(1L)).thenReturn(Optional.of(item));

        ObJourneyStepItem answered = service.answerItem(1L, OWNER, false);

        assertThat(answered.getAnswer()).isNull();
        assertThat(answered.getAnsweredBy()).isNull();
        assertThat(answered.getAnsweredAt()).isNull();
        assertThat(answered.getRemark()).isNull();
    }

    /**
     * The item is only ever as writable as the step it belongs to —
     * {@code ObStepOwnership} applied to the parent, not to the row.
     */
    @Test
    void aStrangerCannotAnswerAnItem() {
        stepRows.get(STEP).setStatus(ObJourneyStepStatus.IN_PROGRESS);
        when(stepItems.findById(1L)).thenReturn(Optional.of(
                stepItem(1L, 100L, null, "Signed agreement received")));

        assertThatThrownBy(() -> service.answerItem(1L, STRANGER, true))
                .isInstanceOf(NotStepOwnerException.class);
    }

    /** The backup owner has the same standing here as everywhere else. */
    @Test
    void theBackupOwnerCanAnswerAnItem() {
        stepRows.get(STEP).setStatus(ObJourneyStepStatus.IN_PROGRESS);
        when(stepItems.findById(1L)).thenReturn(Optional.of(
                stepItem(1L, 100L, null, "Signed agreement received")));

        assertThat(service.answerItem(1L, BACKUP_OWNER, true).getAnswer()).isTrue();
    }

    /**
     * A closed step's checklist is the record of how it closed. Editing it
     * afterwards would change what the completion gate was satisfied by,
     * retroactively.
     */
    @Test
    void aClosedStepsChecklistCannotBeEdited() {
        stepRows.get(STEP).setStatus(ObJourneyStepStatus.DONE);
        when(stepItems.findById(1L)).thenReturn(Optional.of(
                stepItem(1L, 100L, Boolean.TRUE, "Signed agreement received")));

        assertThatThrownBy(() -> service.answerItem(1L, OWNER, false))
                .isInstanceOf(StepAlreadyTerminalException.class);
    }

    @Test
    void answeringAnItemThatDoesNotExistIsNotFound() {
        when(stepItems.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.answerItem(404L, OWNER, true))
                .isInstanceOf(JourneyStepItemNotFoundException.class);
    }

    /**
     * The agreement `isDone` depends on, pinned. The gate is satisfied by
     * <em>any</em> answer, True or False — so `isDone` on the wire means
     * "answered", and a panel treating `isDone: false` as outstanding would
     * refuse what the server allows.
     */
    @Test
    void anItemAnsweredFalseSatisfiesTheCompletionGate() {
        stepRows.get(STEP).setStatus(ObJourneyStepStatus.IN_PROGRESS);
        when(stepItems.findByStepIdOrderBySequenceAsc(STEP)).thenReturn(List.of(
                stepItem(1L, 100L, Boolean.FALSE, "Signed agreement received")));
        when(templateStepItems.findAllById(any())).thenReturn(List.of(templateItem(100L, true)));

        service.complete(STEP, OWNER);

        assertThat(stepRows.get(STEP).getStatus()).isEqualTo(ObJourneyStepStatus.DONE);
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

    // ── completeOnClientAcceptance (B-115) ────────────────────────────────

    @Test
    void clientAcceptanceCompletesAStepWhoseGateIsSatisfied() {
        ObJourneyStep step = stepRows.get(STEP);
        step.setStatus(ObJourneyStepStatus.IN_PROGRESS);

        List<String> failures = service.completeOnClientAcceptance(STEP);

        assertThat(failures).isEmpty();
        assertThat(step.getStatus()).isEqualTo(ObJourneyStepStatus.DONE);
        assertThat(step.getFinishedAt()).isNotNull();
    }

    @Test
    void clientAcceptanceNeedsNoOwner() {
        // The whole point of the seam. complete() refuses a caller who is
        // neither owner nor backup; the client accepting is not a user at all
        // and could never satisfy that check.
        ObJourneyStep step = stepRows.get(STEP);
        step.setStatus(ObJourneyStepStatus.IN_PROGRESS);
        step.setOwnerUserId(OWNER);
        step.setBackupOwnerUserId(BACKUP_OWNER);

        assertThatThrownBy(() -> service.complete(STEP, STRANGER))
                .isInstanceOf(NotStepOwnerException.class);
        assertThat(service.completeOnClientAcceptance(STEP)).isEmpty();
        assertThat(step.getStatus()).isEqualTo(ObJourneyStepStatus.DONE);
    }

    @Test
    void clientAcceptanceReportsUnansweredMandatoryItemsAsACodeRatherThanThrowing() {
        ObJourneyStep step = stepRows.get(STEP);
        step.setStatus(ObJourneyStepStatus.IN_PROGRESS);
        ObJourneyStepItem unanswered = new ObJourneyStepItem();
        unanswered.setId(1L);
        unanswered.setStepId(STEP);
        unanswered.setSequence(1);
        unanswered.setLabel("Signed MSA received");
        when(stepItems.findByStepIdOrderBySequenceAsc(STEP)).thenReturn(List.of(unanswered));

        List<String> failures = service.completeOnClientAcceptance(STEP);

        assertThat(failures).containsExactly("ob-step-items-unanswered");
        assertThat(step.getStatus()).isEqualTo(ObJourneyStepStatus.IN_PROGRESS);
    }

    @Test
    void clientAcceptanceNeverLeaksOurChecklistWordingToTheClient() {
        ObJourneyStep step = stepRows.get(STEP);
        step.setStatus(ObJourneyStepStatus.IN_PROGRESS);
        ObJourneyStepItem unanswered = new ObJourneyStepItem();
        unanswered.setId(1L);
        unanswered.setStepId(STEP);
        unanswered.setSequence(1);
        unanswered.setLabel("Chase Priya about the missing PAN");
        when(stepItems.findByStepIdOrderBySequenceAsc(STEP)).thenReturn(List.of(unanswered));

        List<String> failures = service.completeOnClientAcceptance(STEP);

        // The contract's reasoning: one field, two audiences, and only the
        // internal one gets the detail. This response is unauthenticated.
        assertThat(failures).noneMatch(code -> code.contains("Priya"));
        assertThat(failures).allMatch(code -> code.startsWith("ob-step-"));
    }

    @Test
    void clientAcceptanceReportsAMissingRequiredDocument() {
        ObJourneyStep step = stepRows.get(STEP);
        step.setStatus(ObJourneyStepStatus.IN_PROGRESS);
        step.setTemplateStepId(4242L);
        ObJourneyTemplateStepDoc required = new ObJourneyTemplateStepDoc();
        required.setId(1L);
        required.setStepId(4242L);
        required.setSequence(1);
        required.setLabel("Purchase order");
        required.setRequired(true);
        when(templateStepDocs.findByStepIdOrderBySequenceAsc(4242L)).thenReturn(List.of(required));
        when(attachments.countByStepIdAndScanStatusAndDeletedAtIsNull(STEP, ObAttachmentScanStatus.CLEAN))
                .thenReturn(0L);

        List<String> failures = service.completeOnClientAcceptance(STEP);

        assertThat(failures).containsExactly("ob-step-docs-missing");
        assertThat(step.getStatus()).isEqualTo(ObJourneyStepStatus.IN_PROGRESS);
    }

    @Test
    void clientAcceptanceReportsEveryOutstandingReasonTogether() {
        ObJourneyStep step = stepRows.get(STEP);
        step.setStatus(ObJourneyStepStatus.IN_PROGRESS);
        step.setTemplateStepId(4242L);
        ObJourneyStepItem unanswered = new ObJourneyStepItem();
        unanswered.setId(1L);
        unanswered.setStepId(STEP);
        unanswered.setSequence(1);
        unanswered.setLabel("Signed MSA received");
        when(stepItems.findByStepIdOrderBySequenceAsc(STEP)).thenReturn(List.of(unanswered));
        ObJourneyTemplateStepDoc required = new ObJourneyTemplateStepDoc();
        required.setId(1L);
        required.setStepId(4242L);
        required.setSequence(1);
        required.setLabel("Purchase order");
        required.setRequired(true);
        when(templateStepDocs.findByStepIdOrderBySequenceAsc(4242L)).thenReturn(List.of(required));
        when(attachments.countByStepIdAndScanStatusAndDeletedAtIsNull(STEP, ObAttachmentScanStatus.CLEAN))
                .thenReturn(0L);

        List<String> failures = service.completeOnClientAcceptance(STEP);

        // C-106's own rule, kept on this path: a caller fixing one thing should
        // not have to resubmit to discover the next.
        assertThat(failures).containsExactly("ob-step-items-unanswered", "ob-step-docs-missing");
    }

    @Test
    void clientAcceptanceOnAnAlreadyDoneStepIsNotAFailure() {
        ObJourneyStep step = stepRows.get(STEP);
        step.setStatus(ObJourneyStepStatus.DONE);

        // Staff completed it between the link being sent and the client
        // clicking. There is nothing to do and nothing was wrong.
        assertThat(service.completeOnClientAcceptance(STEP)).isEmpty();
    }

    @Test
    void clientAcceptanceOnABlockedStepReportsOurSideRatherThanThrowing() {
        ObJourneyStep step = stepRows.get(STEP);
        step.setStatus(ObJourneyStepStatus.BLOCKED);

        List<String> failures = service.completeOnClientAcceptance(STEP);

        assertThat(failures).containsExactly("ob-step-not-in-progress");
        assertThat(step.getStatus()).isEqualTo(ObJourneyStepStatus.BLOCKED);
    }

    @Test
    void clientAcceptanceActivatesSiblingsExactlyAsAnOwnerCompletionDoes() {
        ObJourneyStep first = stepRows.get(STEP);
        first.setStatus(ObJourneyStepStatus.IN_PROGRESS);

        ObJourneyStep next = new ObJourneyStep();
        next.setId(STEP + 1);
        next.setJourneyId(JOURNEY);
        next.setSequence(2);
        next.setName("Configure tenant");
        next.setTatDays(2);
        next.setOwnerUserId(OWNER);
        next.setStatus(ObJourneyStepStatus.PENDING);
        next.setDependsOnStepId(STEP);
        stepRows.put(next.getId(), next);

        service.completeOnClientAcceptance(STEP);

        // A journey that stalled because the last completion came through the
        // public surface would be the hardest kind of bug to see.
        assertThat(next.getStatus()).isEqualTo(ObJourneyStepStatus.IN_PROGRESS);
    }

    @Test
    void clientAcceptanceFailsCleanlyForAnUnknownStep() {
        assertThatThrownBy(() -> service.completeOnClientAcceptance(404L))
                .isInstanceOf(JourneyStepNotFoundException.class);
    }

    @Test
    void clientAcceptanceSettlesTheJourneyExactlyAsAnOwnerCompletionDoes() {
        // completeOnClientAcceptance mirrors complete()'s own ending — C-123's
        // settleJourney has to run on both paths, or a journey finished
        // through the public sign-off flow never completes and never
        // releases whatever it was holding.
        stepRows.get(STEP).setStatus(ObJourneyStepStatus.IN_PROGRESS);

        service.completeOnClientAcceptance(STEP);

        assertThat(journeyRows.get(JOURNEY).getCompletedAt()).isNotNull();
        verify(dependencyRelease).release(JOURNEY);
    }

    // ── settleJourney — C-123, plan §5 item 6 ────────────────────────────

    @Test
    void completingTheOnlyStepSettlesTheJourneyAndReleasesWhatWasHeldByIt() {
        stepRows.get(STEP).setStatus(ObJourneyStepStatus.IN_PROGRESS);
        // The journey release() reports — activateEligibleSteps re-reads it
        // for real inside settleJourney, so the fixture needs a real row,
        // OPEN and with nothing else holding it, exactly as the production
        // release just left it.
        ObJourney released = new ObJourney();
        released.setId(600L);
        released.setGateStatus(ObGateStatus.OPEN);
        journeyRows.put(600L, released);
        when(dependencyRelease.release(JOURNEY)).thenReturn(List.of(600L));

        service.complete(STEP, OWNER);

        assertThat(journeyRows.get(JOURNEY).getCompletedAt()).isNotNull();
        verify(dependencyRelease).release(JOURNEY);
        verify(dependencyRelease).notifyUnblocked(600L, JOURNEY);
    }

    @Test
    void completingOneOfTwoStepsDoesNotSettleTheJourneyYet() {
        stepRows.get(STEP).setStatus(ObJourneyStepStatus.IN_PROGRESS);
        ObJourneyStep sibling = pendingStep();
        sibling.setId(STEP + 1);
        sibling.setSequence(2);
        stepRows.put(sibling.getId(), sibling);

        service.complete(STEP, OWNER);

        assertThat(journeyRows.get(JOURNEY).getCompletedAt()).isNull();
        verify(dependencyRelease, never()).release(anyLong());
    }

    @Test
    void aSkippedStepSettlesTheJourneyExactlyAsADoneOneDoes() {
        stepRows.get(STEP).setStatus(ObJourneyStepStatus.IN_PROGRESS);

        service.skip(STEP, STRANGER, MANAGER_ROLE, "not required for this client");

        assertThat(journeyRows.get(JOURNEY).getCompletedAt()).isNotNull();
        verify(dependencyRelease).release(JOURNEY);
    }

    @Test
    void settlingAnAlreadyCompletedJourneyIsANoOp() {
        journeyRows.get(JOURNEY).setCompletedAt(Instant.parse("2026-01-01T00:00:00Z"));
        stepRows.get(STEP).setStatus(ObJourneyStepStatus.IN_PROGRESS);

        service.complete(STEP, OWNER);

        // The original timestamp is untouched, not merely non-null —
        // re-settling must not restamp a journey that already landed.
        assertThat(journeyRows.get(JOURNEY).getCompletedAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        verify(dependencyRelease, never()).release(anyLong());
    }
}
