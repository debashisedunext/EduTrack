package com.edunext.edutrack.api.feature.onboarding.instances;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * C-104 · unit tests for the five step-lifecycle actions, on {@code
 * ObJourneyInstantiationServiceTest}'s own fake-repository shape — no
 * container, {@code mvnw -pl api -Dtest} runs this in seconds.
 */
class ObJourneyStepLifecycleServiceTest {

    private static final long OWNER = 10L;
    private static final long BACKUP_OWNER = 11L;
    private static final long STRANGER = 99L;
    private static final long JOURNEY = 500L;
    private static final long STEP = 700L;

    private final Map<Long, ObJourneyStep> stepRows = new HashMap<>();
    private final Map<Long, ObJourney> journeyRows = new HashMap<>();

    private final ObJourneyStepRepository journeySteps = mock(ObJourneyStepRepository.class);
    private final ObJourneyRepository journeys = mock(ObJourneyRepository.class);
    private final ObJourneyStepItemRepository stepItems = mock(ObJourneyStepItemRepository.class);
    private final ObJourneyTemplateStepItemRepository templateStepItems = mock(ObJourneyTemplateStepItemRepository.class);
    private final ObJourneyTemplateStepDocRepository templateStepDocs = mock(ObJourneyTemplateStepDocRepository.class);
    private final ObAttachmentRepository attachments = mock(ObAttachmentRepository.class);
    private final ObSignoffRepository signoffs = mock(ObSignoffRepository.class);

    private final ObJourneyStepLifecycleService service = new ObJourneyStepLifecycleService(
            journeySteps, journeys, stepItems, templateStepItems, templateStepDocs, attachments, signoffs);

    @BeforeEach
    void wireFakes() {
        when(journeySteps.findById(any())).thenAnswer(inv ->
                Optional.ofNullable(stepRows.get(inv.<Long>getArgument(0))));
        when(journeys.findById(any())).thenAnswer(inv ->
                Optional.ofNullable(journeyRows.get(inv.<Long>getArgument(0))));
        // C-106's completion gate defaults to "nothing outstanding" — an
        // empty Task List and no document checklist — so every C-104 test
        // written before this task keeps completing exactly as it did.
        when(stepItems.findByStepIdOrderBySequenceAsc(any())).thenReturn(List.of());
        when(templateStepItems.findAllById(any())).thenReturn(List.of());
        when(templateStepDocs.findByStepIdOrderBySequenceAsc(any())).thenReturn(List.of());

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

    @Test
    void resumeMovesAWaitingOnClientStepBackToInProgress() {
        stepRows.get(STEP).setStatus(ObJourneyStepStatus.WAITING_ON_CLIENT);

        ObJourneyStep resumed = service.resume(STEP, OWNER);

        assertThat(resumed.getStatus()).isEqualTo(ObJourneyStepStatus.IN_PROGRESS);
    }

    @Test
    void resumeLeavesDueAtUntouched() {
        ObJourneyStep step = stepRows.get(STEP);
        step.setStatus(ObJourneyStepStatus.BLOCKED);
        step.setBlockedReasonCode("client-unresponsive");
        java.time.Instant dueAt = java.time.Instant.parse("2026-10-01T00:00:00Z");
        step.setDueAt(dueAt);

        ObJourneyStep resumed = service.resume(STEP, OWNER);

        assertThat(resumed.getDueAt()).isEqualTo(dueAt);
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
}
