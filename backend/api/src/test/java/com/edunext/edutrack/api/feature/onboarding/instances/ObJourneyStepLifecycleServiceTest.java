package com.edunext.edutrack.api.feature.onboarding.instances;

import com.edunext.edutrack.domain.onboarding.ObGateStatus;
import com.edunext.edutrack.domain.onboarding.ObJourney;
import com.edunext.edutrack.domain.onboarding.ObJourneyRepository;
import com.edunext.edutrack.domain.onboarding.ObJourneyStep;
import com.edunext.edutrack.domain.onboarding.ObJourneyStepRepository;
import com.edunext.edutrack.domain.onboarding.ObJourneyStepStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
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

    private final ObJourneyStepLifecycleService service =
            new ObJourneyStepLifecycleService(journeySteps, journeys);

    @BeforeEach
    void wireFakes() {
        when(journeySteps.findById(any())).thenAnswer(inv ->
                Optional.ofNullable(stepRows.get(inv.<Long>getArgument(0))));
        when(journeys.findById(any())).thenAnswer(inv ->
                Optional.ofNullable(journeyRows.get(inv.<Long>getArgument(0))));

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
