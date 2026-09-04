package com.edunext.edutrack.api.feature.onboarding.instances;

import com.edunext.edutrack.domain.onboarding.ObAttachmentRepository;
import com.edunext.edutrack.domain.onboarding.ObAttachmentScanStatus;
import com.edunext.edutrack.domain.onboarding.ObGateStatus;
import com.edunext.edutrack.domain.onboarding.ObJourney;
import com.edunext.edutrack.api.security.scope.UnscopedAccess;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

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
 *   <li><b>No {@code due_at} maths.</b> The working-calendar computation
 *       from {@code tatDays}, and pause/resume as clock-event rows, are
 *       C-105's. {@code due_at} is left exactly as it was on every
 *       transition here.</li>
 * </ul>
 *
 * <h2>C-106 · the completion gate</h2>
 *
 * <p>{@link #complete} now enforces plan §5.8's "every [mandatory]
 * sub-category answered", the architect's addition 7 ("a step can't
 * complete with required documents missing"), and, where
 * {@link ObJourneyStep#isRequiresSignoff()} says so, an accepted client
 * sign-off (§8) — see {@link #requireCompletionGate} for exactly how each
 * is evaluated and {@link CompletionGateException} for how all three are
 * reported together. <b>This is the one choke point.</b> A-120's public
 * sign-off surface, whenever it lands, must call this same method to land
 * a step on {@code DONE} rather than flipping the status column directly —
 * the design's own acceptance path does the latter and enforces none of
 * this, which is exactly the gap this task exists to close.
 */
@Service
@UnscopedAccess("""
        A-112's guard, C-104's transitions: this class reads ObJourneyRepository         once, and not as a caller-scoped read. requireOwnership has already         refused anyone who is neither the step's owner nor its backup, and         OnboardingScopeResolver grants OB_STEP_OWNER exactly the journeys         containing their steps — owner or backup, deliberately — so a caller         who reaches the findById below is provably in scope for that journey         already. The read is of the step's own parent, for gate_status and         held_by_journey_id, and it can disclose nothing the caller did not         just prove they may act on.

        Routing it through ScopedJourneys would also be worse than redundant         here: this method takes a callerId, not an Authentication, so it would         need a second principal shape threaded through five transitions to         re-answer a question requireOwnership has already answered — and a         scope miss would surface as the IllegalStateException below, a 500,         where the whole point of the guard is a 404.""")
public class ObJourneyStepLifecycleService {

    private final ObJourneyStepRepository journeySteps;
    private final ObJourneyRepository journeys;
    private final ObJourneyStepItemRepository stepItems;
    private final ObJourneyTemplateStepItemRepository templateStepItems;
    private final ObJourneyTemplateStepDocRepository templateStepDocs;
    private final ObAttachmentRepository attachments;
    private final ObSignoffRepository signoffs;

    public ObJourneyStepLifecycleService(ObJourneyStepRepository journeySteps, ObJourneyRepository journeys,
            ObJourneyStepItemRepository stepItems, ObJourneyTemplateStepItemRepository templateStepItems,
            ObJourneyTemplateStepDocRepository templateStepDocs, ObAttachmentRepository attachments,
            ObSignoffRepository signoffs) {
        this.journeySteps = journeySteps;
        this.journeys = journeys;
        this.stepItems = stepItems;
        this.templateStepItems = templateStepItems;
        this.templateStepDocs = templateStepDocs;
        this.attachments = attachments;
        this.signoffs = signoffs;
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
     * {@code IN_PROGRESS → DONE}. C-106's completion gate runs after the
     * transition check and before anything is written — see
     * {@link #requireCompletionGate} and the class javadoc.
     *
     * @throws JourneyStepNotFoundException  no such step
     * @throws NotStepOwnerException          caller is neither owner nor backup owner
     * @throws InvalidStepTransitionException step is not {@code IN_PROGRESS}
     * @throws CompletionGateException        a mandatory item is unanswered, a
     *                                         required document is not attached, or
     *                                         the step's required sign-off is missing
     */
    @Transactional
    public ObJourneyStep complete(long stepId, long callerId) {
        ObJourneyStep step = requireStep(stepId);
        requireOwnership(step, callerId);
        requireStatus(step, "complete", ObJourneyStepStatus.IN_PROGRESS);
        requireCompletionGate(step);

        step.setStatus(ObJourneyStepStatus.DONE);
        step.setFinishedAt(Instant.now());
        return step;
    }

    /**
     * C-106 · the three independent completion checks, evaluated together
     * so a refusal names everything outstanding rather than one thing at a
     * time.
     *
     * <p><b>Mandatory items.</b> An item with no {@code templateItemId}
     * is an admin's ad-hoc addition that no template row governs — see
     * {@code ObJourneyStepItem}'s own javadoc. It defaults to mandatory,
     * on the same reasoning {@code ObJourneyTemplateStepItem.mandatory}
     * gives for its own default: every item predates the choice of
     * whether to gate on it, so the value that preserves existing
     * behaviour is the safe one. The False-needs-a-remark half of §5.8 is
     * not re-checked here — {@code ck_ob_journey_step_items_remark} makes
     * it impossible to store a False with no remark in the first place.
     *
     * <p><b>Required documents.</b> The checklist is per template step
     * ({@code ObJourneyTemplateStepDoc}), but nothing links one attachment
     * to one checklist entry — see {@code ObAttachmentRepository}'s own
     * note. So this counts rather than matches: the number of {@code CLEAN},
     * non-tombstoned attachments on the step must reach the number of
     * required checklist rows on its template step. A step with no
     * {@code templateStepId} (none recorded, or an ad-hoc step with no
     * template lineage at all) has no checklist to gate on.
     *
     * <p><b>Sign-off.</b> Only checked when
     * {@link ObJourneyStep#isRequiresSignoff()} is set on this step's own
     * snapshot — never the live template, which C-104's own precedent
     * already established for every other field this row copied at
     * instantiation.
     */
    private void requireCompletionGate(ObJourneyStep step) {
        List<ObJourneyStepItem> items = stepItems.findByStepIdOrderBySequenceAsc(step.getId());
        List<Long> templateItemIds = items.stream()
                .map(ObJourneyStepItem::getTemplateItemId)
                .filter(Objects::nonNull)
                .toList();
        Map<Long, Boolean> mandatoryByTemplateItemId = templateStepItems.findAllById(templateItemIds).stream()
                .collect(Collectors.toMap(ObJourneyTemplateStepItem::getId, ObJourneyTemplateStepItem::isMandatory));

        List<String> unanswered = items.stream()
                .filter(item -> item.getAnswer() == null)
                .filter(item -> mandatoryByTemplateItemId.getOrDefault(item.getTemplateItemId(), true))
                .map(ObJourneyStepItem::getLabel)
                .toList();

        long requiredDocs = step.getTemplateStepId() == null ? 0
                : templateStepDocs.findByStepIdOrderBySequenceAsc(step.getTemplateStepId()).stream()
                        .filter(ObJourneyTemplateStepDoc::isRequired)
                        .count();
        long attachedDocs = requiredDocs == 0 ? 0
                : attachments.countByStepIdAndScanStatusAndDeletedAtIsNull(step.getId(), ObAttachmentScanStatus.CLEAN);
        long missingDocs = Math.max(0, requiredDocs - attachedDocs);

        boolean signoffMissing = step.isRequiresSignoff()
                && !signoffs.existsByStepIdAndKindAndStatus(step.getId(), ObSignoffKind.STEP, ObSignoffStatus.SIGNED);

        if (!unanswered.isEmpty() || missingDocs > 0 || signoffMissing) {
            throw new CompletionGateException(step.getId(), unanswered, missingDocs, signoffMissing);
        }
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
