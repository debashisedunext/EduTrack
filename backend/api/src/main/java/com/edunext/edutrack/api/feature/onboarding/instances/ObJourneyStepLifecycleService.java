package com.edunext.edutrack.api.feature.onboarding.instances;

import com.edunext.edutrack.domain.onboarding.ObGateStatus;
import com.edunext.edutrack.domain.onboarding.ObJourney;
import com.edunext.edutrack.domain.onboarding.ObJourneyRepository;
import com.edunext.edutrack.domain.onboarding.ObJourneyStep;
import com.edunext.edutrack.domain.onboarding.ObJourneyStepRepository;
import com.edunext.edutrack.domain.onboarding.ObJourneyStepStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * C-104 · Step lifecycle — plan's own line for this task: start, complete,
 * block-with-mandatory-reason, waiting-on-client, resume. Five actions on
 * {@link ObJourneyStepStatus}'s {@code PENDING → IN_PROGRESS ⇄
 * {BLOCKED, WAITING_ON_CLIENT} → DONE} shape; {@code SKIPPED} is C-107's own
 * transition and does not appear here.
 *
 * <h2>What this service deliberately does not do</h2>
 *
 * <p>Following {@code ObJourneyInstantiationService}'s own precedent of
 * naming its boundary rather than leaving it to be discovered:
 *
 * <ul>
 *   <li><b>No dependency-graph check on {@link #start}.</b> A step whose
 *       {@code dependsOnStepId} points at a step that has not finished can
 *       still be started manually today. Refusing that, "naming the
 *       blocker", is C-119's own line in the backlog — adding a partial
 *       version of it here would be exactly the kind of design C-119 would
 *       then have to unpick rather than build on.</li>
 *   <li><b>No completion gate on {@link #complete}.</b> Plan §5.8's
 *       "every sub-category answered, False needs a remark" and the
 *       sign-off requirement are C-106's own server-side gate. This method
 *       only enforces the status transition.</li>
 *   <li><b>No {@code due_at} maths.</b> The working-calendar computation
 *       from {@code tatDays}, and pause/resume as clock-event rows, are
 *       C-105's. {@code due_at} is left exactly as it was on every
 *       transition here.</li>
 * </ul>
 */
@Service
public class ObJourneyStepLifecycleService {

    private final ObJourneyStepRepository journeySteps;
    private final ObJourneyRepository journeys;

    public ObJourneyStepLifecycleService(ObJourneyStepRepository journeySteps, ObJourneyRepository journeys) {
        this.journeySteps = journeySteps;
        this.journeys = journeys;
    }

    /**
     * {@code PENDING → IN_PROGRESS}. Refused while the journey's own gate is
     * still {@code LOCKED} or the journey is held by another
     * ({@code held_by_journey_id}) — "clocks dead until the gate opens" is
     * literal, not merely about the initial instantiation.
     *
     * @throws JourneyStepNotFoundException     no such step
     * @throws NotStepOwnerException             caller is neither owner nor backup owner
     * @throws JourneyNotOpenException           the journey is locked or held
     * @throws InvalidStepTransitionException    step is not {@code PENDING}
     */
    @Transactional
    public ObJourneyStep start(long stepId, long callerId) {
        ObJourneyStep step = requireStep(stepId);
        requireOwnership(step, callerId);
        requireStatus(step, "start", ObJourneyStepStatus.PENDING);

        ObJourney journey = journeys.findById(step.getJourneyId())
                .orElseThrow(() -> new IllegalStateException(
                        "journey step " + stepId + " points at journey " + step.getJourneyId() + " which does not exist"));
        if (journey.getGateStatus() != ObGateStatus.OPEN || journey.getHeldByJourneyId() != null) {
            throw new JourneyNotOpenException(journey.getId(),
                    journey.getGateStatus() != ObGateStatus.OPEN, journey.getHeldByJourneyId());
        }

        step.setStatus(ObJourneyStepStatus.IN_PROGRESS);
        step.setStartedAt(Instant.now());
        return step;
    }

    /**
     * {@code IN_PROGRESS → DONE}. No completion-gate check — see the class
     * javadoc; that is C-106's own server-side gate, layered on top of this
     * transition rather than folded into it.
     *
     * @throws JourneyStepNotFoundException  no such step
     * @throws NotStepOwnerException          caller is neither owner nor backup owner
     * @throws InvalidStepTransitionException step is not {@code IN_PROGRESS}
     */
    @Transactional
    public ObJourneyStep complete(long stepId, long callerId) {
        ObJourneyStep step = requireStep(stepId);
        requireOwnership(step, callerId);
        requireStatus(step, "complete", ObJourneyStepStatus.IN_PROGRESS);

        step.setStatus(ObJourneyStepStatus.DONE);
        step.setFinishedAt(Instant.now());
        return step;
    }

    /**
     * {@code IN_PROGRESS → BLOCKED}. {@code reasonCode} is mandatory —
     * {@code ck_ob_journey_steps_blocked_reason} holds it at the database
     * too, but the DTO's {@code @NotBlank} is what turns a missing one into
     * a clean {@code 400} instead of a constraint-violation 500.
     *
     * @throws JourneyStepNotFoundException  no such step
     * @throws NotStepOwnerException          caller is neither owner nor backup owner
     * @throws InvalidStepTransitionException step is not {@code IN_PROGRESS}
     */
    @Transactional
    public ObJourneyStep block(long stepId, long callerId, String reasonCode, String note) {
        ObJourneyStep step = requireStep(stepId);
        requireOwnership(step, callerId);
        requireStatus(step, "block", ObJourneyStepStatus.IN_PROGRESS);

        step.setStatus(ObJourneyStepStatus.BLOCKED);
        step.setBlockedReasonCode(reasonCode);
        step.setBlockedNote(note);
        return step;
    }

    /**
     * {@code IN_PROGRESS → WAITING_ON_CLIENT}. Plan's own line: "internal
     * BLOCKED does not [pause the clock]; WAITING_ON_CLIENT pauses." This
     * method only flips the status column the scanner and TAT maths read —
     * the clock-event row that actually pauses is C-105's.
     *
     * @throws JourneyStepNotFoundException  no such step
     * @throws NotStepOwnerException          caller is neither owner nor backup owner
     * @throws InvalidStepTransitionException step is not {@code IN_PROGRESS}
     */
    @Transactional
    public ObJourneyStep waitOnClient(long stepId, long callerId) {
        ObJourneyStep step = requireStep(stepId);
        requireOwnership(step, callerId);
        requireStatus(step, "mark waiting-on-client", ObJourneyStepStatus.IN_PROGRESS);

        step.setStatus(ObJourneyStepStatus.WAITING_ON_CLIENT);
        return step;
    }

    /**
     * {@code BLOCKED → IN_PROGRESS} or {@code WAITING_ON_CLIENT → IN_PROGRESS}.
     * Clears {@code blockedReasonCode}/{@code blockedNote} — a resumed step
     * is no longer blocked, and a stale reason left on the row would read as
     * though it still were. {@code due_at} is left untouched; recomputing it
     * against the working calendar is C-105's own line in the backlog.
     *
     * @throws JourneyStepNotFoundException  no such step
     * @throws NotStepOwnerException          caller is neither owner nor backup owner
     * @throws InvalidStepTransitionException step is neither {@code BLOCKED} nor {@code WAITING_ON_CLIENT}
     */
    @Transactional
    public ObJourneyStep resume(long stepId, long callerId) {
        ObJourneyStep step = requireStep(stepId);
        requireOwnership(step, callerId);
        if (step.getStatus() != ObJourneyStepStatus.BLOCKED && step.getStatus() != ObJourneyStepStatus.WAITING_ON_CLIENT) {
            throw new InvalidStepTransitionException(stepId, "resume", step.getStatus());
        }

        step.setStatus(ObJourneyStepStatus.IN_PROGRESS);
        step.setBlockedReasonCode(null);
        step.setBlockedNote(null);
        return step;
    }

    private ObJourneyStep requireStep(long stepId) {
        return journeySteps.findById(stepId).orElseThrow(() -> new JourneyStepNotFoundException(stepId));
    }

    private void requireOwnership(ObJourneyStep step, long callerId) {
        if (!ObStepOwnership.mayAct(callerId, step)) {
            throw new NotStepOwnerException(step.getId(), step.getOwnerUserId(), step.getBackupOwnerUserId());
        }
    }

    private void requireStatus(ObJourneyStep step, String action, ObJourneyStepStatus required) {
        if (step.getStatus() != required) {
            throw new InvalidStepTransitionException(step.getId(), action, step.getStatus());
        }
    }
}
