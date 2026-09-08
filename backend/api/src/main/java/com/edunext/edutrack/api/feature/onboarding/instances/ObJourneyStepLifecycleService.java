package com.edunext.edutrack.api.feature.onboarding.instances;

import com.edunext.edutrack.domain.journal.ObStepJournal;
import com.edunext.edutrack.domain.masters.WorkingCalendarRepository;
import com.edunext.edutrack.domain.masters.WorkingHoursService;
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
import com.edunext.edutrack.domain.onboarding.ObStepClockAttribution;
import com.edunext.edutrack.domain.onboarding.ObStepClockActorType;
import com.edunext.edutrack.domain.onboarding.ObStepClockEvent;
import com.edunext.edutrack.domain.onboarding.ObStepClockEventRepository;
import com.edunext.edutrack.domain.onboarding.ObStepClockEventType;
import com.edunext.edutrack.domain.onboarding.ObStepHistory;
import com.edunext.edutrack.domain.onboarding.ObStepTatBudget;
import org.openapitools.jackson.nullable.JsonNullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * C-104 · Step lifecycle — plan's own line for this task: start, complete,
 * block-with-mandatory-reason, waiting-on-client, resume. Five actions on
 * {@link ObJourneyStepStatus}'s {@code PENDING → IN_PROGRESS ⇄
 * {BLOCKED, WAITING_ON_CLIENT} → DONE} shape; {@code SKIPPED} is C-107's own
 * transition (see {@link #skip}) and is not one of the five.
 *
 * <h2>C-105 · the clock</h2>
 *
 * <p>{@link #start} now computes {@code due_at} working-calendar-aware from
 * {@code tatDays} (see {@link #computeDueAt}); {@link #waitOnClient} and
 * {@link #resume} record pause and resume as {@code ob_step_clock_events}
 * rows rather than leaving the wait a bare status flip, and {@link #resume}
 * recomputes {@code due_at} when the pause it is closing actually stopped
 * the clock (see {@link #recomputeDueAtOnResume}). {@link #block} is
 * unchanged: internal {@code BLOCKED} does not pause the clock, per plan
 * §5.7, so it writes no event and touches no date.
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
 *
 * <h2>C-107 · skip</h2>
 *
 * <p>{@link #skip} is the sixth transition and the odd one out: not row-
 * scoped by ownership, not gated by anything {@link #complete} checks, and
 * the first method in this class to write anywhere other than the {@code
 * ob_journey_steps} row it mutates — see its own javadoc and {@link
 * #appendSkippedHistory}.
 *
 * <h2>C-108 · update</h2>
 *
 * <p>{@link #update} is not a transition at all — {@code status} is
 * deliberately absent from its request shape (the contract's own line).
 * Reassignment, re-planning TAT and re-planning the due date are what it
 * covers, gated by {@link #requireModerator} exactly like {@link #skip}
 * rather than {@link #requireOwnership}: an owner reassigning themselves off
 * their own step is the one rewrite this route must not permit silently.
 *
 * <h2>C-119 · the dependency graph</h2>
 *
 * <p>{@code dependsOnStepId} (plan §5.6): {@link #start} now refuses a step
 * whose dependency has not finished, naming the blocker (see
 * {@link #requireDependencySatisfied}), and {@link #complete} and
 * {@link #skip} both re-evaluate the whole journey afterwards and move
 * every newly-eligible {@code PENDING} step straight to {@code IN_PROGRESS}
 * — see {@link #activateEligibleSteps}. A step with no dependency needs
 * none of this: it was always eligible, which is what "dependency-free
 * steps run in parallel" means in practice — nothing here ever blocks one.
 *
 * <p>{@link #activateEligibleSteps} is also {@code
 * ObJourneyInstantiationService}'s own call for a journey instantiated
 * already {@code OPEN} (a product bought after this client's gate cleared)
 * — see {@code ObJourneyStep}'s class javadoc for why that path defers to
 * this method instead of duplicating the same eligibility check in the
 * clone constructor.
 */
@Service
@UnscopedAccess("""
        A-112's guard, C-104's transitions: this class reads ObJourneyRepository         once, and not as a caller-scoped read. requireOwnership has already         refused anyone who is neither the step's owner nor its backup, and         OnboardingScopeResolver grants OB_STEP_OWNER exactly the journeys         containing their steps — owner or backup, deliberately — so a caller         who reaches the findById below is provably in scope for that journey         already. The read is of the step's own parent, for gate_status and         held_by_journey_id, and it can disclose nothing the caller did not         just prove they may act on.

        Routing it through ScopedJourneys would also be worse than redundant         here: this method takes a callerId, not an Authentication, so it would         need a second principal shape threaded through five transitions to         re-answer a question requireOwnership has already answered — and a         scope miss would surface as the IllegalStateException below, a 500,         where the whole point of the guard is a 404.

        C-107's own skip() reads the same repository the same way, for a         sixth transition — a plain findById for obClientId, to put on the         ob_step_history row it builds. The FOR UPDATE lock that append needs         is ObStepJournal's own, taken inside domain/journal/ where         ScopeGuardRulesTest does not reach; this class never calls         findByIdForUpdate itself.""")
public class ObJourneyStepLifecycleService {

    /** Plan §3's "override steps with logged reason" — {@link #skip}'s own capability. */
    private static final Set<String> MODERATOR_ROLES = Set.of("OB_MANAGER", "OB_ADMIN");

    /**
     * C-105 · the only pausing reason written today — {@code
     * ck_ob_clock_pause_reason}'s mandatory value on a {@code PAUSED} row.
     * See {@link ObStepClockEvent#getPauseReason()} for why the column
     * itself stays a plain string rather than an enum with one member.
     */
    private static final String WAITING_ON_CLIENT_PAUSE_REASON = "WAITING_ON_CLIENT";

    private final ObJourneyStepRepository journeySteps;
    private final ObJourneyRepository journeys;
    private final ObJourneyStepItemRepository stepItems;
    private final ObJourneyTemplateStepItemRepository templateStepItems;
    private final ObJourneyTemplateStepDocRepository templateStepDocs;
    private final ObAttachmentRepository attachments;
    private final ObSignoffRepository signoffs;
    private final ObStepJournal stepJournal;
    private final WorkingHoursService workingHours;
    private final WorkingCalendarRepository workingCalendars;
    private final ObStepClockEventRepository clockEvents;
    private final ObStepClockRecorder clockRecorder;

    public ObJourneyStepLifecycleService(ObJourneyStepRepository journeySteps, ObJourneyRepository journeys,
            ObJourneyStepItemRepository stepItems, ObJourneyTemplateStepItemRepository templateStepItems,
            ObJourneyTemplateStepDocRepository templateStepDocs, ObAttachmentRepository attachments,
            ObSignoffRepository signoffs, ObStepJournal stepJournal, WorkingHoursService workingHours,
            WorkingCalendarRepository workingCalendars, ObStepClockEventRepository clockEvents,
            ObStepClockRecorder clockRecorder) {
        this.journeySteps = journeySteps;
        this.journeys = journeys;
        this.stepItems = stepItems;
        this.templateStepItems = templateStepItems;
        this.templateStepDocs = templateStepDocs;
        this.attachments = attachments;
        this.signoffs = signoffs;
        this.stepJournal = stepJournal;
        this.workingHours = workingHours;
        this.workingCalendars = workingCalendars;
        this.clockEvents = clockEvents;
        this.clockRecorder = clockRecorder;
    }

    /**
     * {@code PENDING → IN_PROGRESS}. Refused while the journey's own gate is
     * still {@code LOCKED} or the journey is held by another
     * ({@code held_by_journey_id}) — "clocks dead until the gate opens" is
     * literal, not merely about the initial instantiation.
     *
     * <p>C-105 · {@code due_at} is computed here, working-calendar aware,
     * from {@link ObJourneyStep#getTatDays()} — see {@link
     * #computeDueAt(Instant, int)}. No clock-event row accompanies it: {@link
     * ObStepClockEventType#STARTED} is reserved rather than written, on that
     * enum's own javadoc — {@code startedAt} already answers "when did the
     * clock start".
     *
     * @throws JourneyStepNotFoundException     no such step
     * @throws NotStepOwnerException             caller is neither owner nor backup owner
     * @throws JourneyNotOpenException           the journey is locked or held
     * @throws InvalidStepTransitionException    step is not {@code PENDING}
     * @throws StepDependencyNotSatisfiedException C-119 · {@code dependsOnStepId} has not finished
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
        requireDependencySatisfied(step);

        Instant startedAt = Instant.now();
        step.setStatus(ObJourneyStepStatus.IN_PROGRESS);
        step.setStartedAt(startedAt);
        step.setDueAt(computeDueAt(startedAt, step.getTatDays()));
        return step;
    }

    /**
     * {@code IN_PROGRESS → DONE}. C-106's completion gate runs after the
     * transition check and before anything is written — see
     * {@link #requireCompletionGate} and the class javadoc. C-119 · once
     * {@code DONE}, {@link #activateEligibleSteps} re-evaluates the journey:
     * any sibling step whose only dependency was this one moves straight to
     * {@code IN_PROGRESS}.
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
        activateEligibleSteps(step.getJourneyId());
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
        Map<Long, Boolean> mandatoryByTemplateItemId = mandatoryByTemplateItemId(items);

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
     * BLOCKED does not [pause the clock]; WAITING_ON_CLIENT pauses." C-105 ·
     * this is the pause, recorded as a {@link ObStepClockEventType#PAUSED}
     * row rather than inferred from the status column — {@code
     * attributedTo(CLIENT)} is the module plan §1.1 item 1 mitigation for
     * TAT disputes, fixed at the moment the wait began rather than derived
     * later from {@code pauseReason}.
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

        ObStepClockEvent paused = new ObStepClockEvent();
        paused.setStepId(step.getId());
        paused.setJourneyId(step.getJourneyId());
        paused.setEventType(ObStepClockEventType.PAUSED);
        paused.setPauseReason(WAITING_ON_CLIENT_PAUSE_REASON);
        paused.setAttributedTo(ObStepClockAttribution.CLIENT);
        paused.setOccurredAt(Instant.now());
        paused.setActorId(callerId);
        paused.setActorType(ObStepClockActorType.USER);
        clockRecorder.record(paused);

        return step;
    }

    /**
     * {@code BLOCKED → IN_PROGRESS} or {@code WAITING_ON_CLIENT → IN_PROGRESS}.
     * Clears {@code blockedReasonCode}/{@code blockedNote} — a resumed step
     * is no longer blocked, and a stale reason left on the row would read as
     * though it still were.
     *
     * <p>C-105 · {@code due_at} is recomputed <b>only</b> when the step is
     * resuming from {@code WAITING_ON_CLIENT}. A resume from {@code BLOCKED}
     * leaves it untouched, on the module plan's own §5.7 line: "internal
     * BLOCKED does not [pause the clock]" — nothing paused, so there is
     * nothing to give back. See {@link #recomputeDueAtOnResume} for the
     * maths and {@link ObStepClockEventRepository}'s own javadoc for why the
     * most recent, unmatched {@code PAUSED} row is the one this reads.
     *
     * @throws JourneyStepNotFoundException  no such step
     * @throws NotStepOwnerException          caller is neither owner nor backup owner
     * @throws InvalidStepTransitionException step is neither {@code BLOCKED} nor {@code WAITING_ON_CLIENT}
     */
    @Transactional
    public ObJourneyStep resume(long stepId, long callerId) {
        ObJourneyStep step = requireStep(stepId);
        requireOwnership(step, callerId);
        ObJourneyStepStatus previousStatus = step.getStatus();
        if (previousStatus != ObJourneyStepStatus.BLOCKED && previousStatus != ObJourneyStepStatus.WAITING_ON_CLIENT) {
            throw new InvalidStepTransitionException(stepId, "resume", previousStatus);
        }

        step.setStatus(ObJourneyStepStatus.IN_PROGRESS);
        step.setBlockedReasonCode(null);
        step.setBlockedNote(null);

        if (previousStatus == ObJourneyStepStatus.WAITING_ON_CLIENT) {
            Instant resumedAt = Instant.now();
            recomputeDueAtOnResume(step, resumedAt);

            ObStepClockEvent resumed = new ObStepClockEvent();
            resumed.setStepId(step.getId());
            resumed.setJourneyId(step.getJourneyId());
            resumed.setEventType(ObStepClockEventType.RESUMED);
            resumed.setAttributedTo(ObStepClockAttribution.INTERNAL);
            resumed.setOccurredAt(resumedAt);
            resumed.setActorId(callerId);
            resumed.setActorType(ObStepClockActorType.USER);
            clockRecorder.record(resumed);
        }

        return step;
    }

    /**
     * C-107 · any status → {@code SKIPPED}. Manager/Admin only — plan §3's
     * "override steps with logged reason" — with a mandatory {@code reason}
     * and a hash-chained {@code ob_step_history} row recording who and why.
     *
     * <p><b>Not row-scoped by ownership, unlike the five transitions above.</b>
     * {@link #requireOwnership} does not apply: this is exactly the override
     * plan §3 grants a Manager or Admin over a step they may not own, which is
     * the point of an override.
     *
     * <p><b>No gate or dependency check, deliberately.</b> Unlike {@link
     * #start}, this does not refuse a {@code LOCKED} or held journey. An
     * override that only worked once the ordinary rules already permitted
     * action would not be an override — plan §3 draws no such exception, and
     * C-119's dependency graph re-evaluates a journey on every step that
     * finishes or is skipped regardless of how it got there.
     *
     * @param moduleRole the caller's role inside the {@code ONBOARDING}
     *                   module ({@link CallerIdentityAccess#onboardingModuleRole}),
     *                   or {@code null}/blank if they hold none
     * @throws JourneyStepNotFoundException        no such step, <em>or</em> the caller holds no
     *                                              standing in {@code ONBOARDING} at all — see
     *                                              {@link #requireModerator}'s own javadoc for why
     *                                              the two are answered identically
     * @throws NotAnOnboardingModeratorException   the caller holds a role in {@code ONBOARDING},
     *                                              and it is neither {@code OB_MANAGER} nor {@code OB_ADMIN}
     * @throws StepAlreadyTerminalException        the step is already {@code DONE} or {@code SKIPPED}
     */
    @Transactional
    public ObJourneyStep skip(long stepId, long callerId, String moduleRole, String reason) {
        ObJourneyStep step = requireStep(stepId);
        requireModerator(stepId, moduleRole);
        requireNotTerminal(step);

        // A plain read — for obClientId, to put on the history entry.
        // ObStepJournal#append takes its own findByIdForUpdate lock before
        // touching the chain; this class does not need to hold one itself.
        ObJourney journey = journeys.findById(step.getJourneyId())
                .orElseThrow(() -> new IllegalStateException(
                        "journey step " + stepId + " points at journey " + step.getJourneyId() + " which does not exist"));

        ObJourneyStepStatus previousStatus = step.getStatus();
        step.setStatus(ObJourneyStepStatus.SKIPPED);
        step.setSkipReason(reason);
        step.setSkippedBy(callerId);

        appendSkippedHistory(journey, step, previousStatus, callerId, reason);
        activateEligibleSteps(step.getJourneyId());
        return step;
    }

    /**
     * C-108 · owner, backup owner, TAT and due date — the fields a manager
     * adjusts without the step changing state (contract's own operation
     * description for {@code PATCH .../journey-steps/{stepId}}). {@code
     * ownerUserId}/{@code backupOwnerUserId}/{@code dueAt} are {@link
     * JsonNullable} — {@link JsonNullable#isPresent()} false means "absent,
     * leave unchanged"; present-with-{@code null} means "clear it". {@code
     * tatDays} stays a plain, nullable {@code Integer}: the schema never
     * allows it to be cleared, only left absent.
     *
     * <p>Gated the same way {@link #skip} is — {@link #requireModerator},
     * not {@link #requireOwnership} — on the same reasoning: reassigning a
     * step off its own owner, or re-planning its TAT, is exactly the kind of
     * override a step's owner should not be able to grant themselves.
     *
     * @throws JourneyStepNotFoundException        no such step, <em>or</em> the caller holds no
     *                                              standing in {@code ONBOARDING} at all
     * @throws NotAnOnboardingModeratorException   the caller holds a role in {@code ONBOARDING},
     *                                              and it is neither {@code OB_MANAGER} nor {@code OB_ADMIN}
     * @throws StepAlreadyTerminalException        the step is already {@code DONE} or {@code SKIPPED} —
     *                                              its recorded TAT is what TAT reports are built on
     */
    @Transactional
    public ObJourneyStep update(long stepId, long callerId, String moduleRole,
            JsonNullable<Long> ownerUserId, JsonNullable<Long> backupOwnerUserId,
            Integer tatDays, JsonNullable<Instant> dueAt) {
        ObJourneyStep step = requireStep(stepId);
        requireModerator(stepId, moduleRole);
        requireNotTerminal(step);

        ObJourney journey = journeys.findById(step.getJourneyId())
                .orElseThrow(() -> new IllegalStateException(
                        "journey step " + stepId + " points at journey " + step.getJourneyId() + " which does not exist"));

        if (ownerUserId.isPresent() && !Objects.equals(ownerUserId.get(), step.getOwnerUserId())) {
            appendFieldChangedHistory(journey, step, callerId, "owner_user_id",
                    step.getOwnerUserId(), ownerUserId.get());
            step.setOwnerUserId(ownerUserId.get());
        }
        if (backupOwnerUserId.isPresent() && !Objects.equals(backupOwnerUserId.get(), step.getBackupOwnerUserId())) {
            appendFieldChangedHistory(journey, step, callerId, "backup_owner_user_id",
                    step.getBackupOwnerUserId(), backupOwnerUserId.get());
            step.setBackupOwnerUserId(backupOwnerUserId.get());
        }
        if (tatDays != null && tatDays != step.getTatDays()) {
            appendFieldChangedHistory(journey, step, callerId, "tat_days", step.getTatDays(), tatDays);
            step.setTatDays(tatDays);
        }
        if (dueAt.isPresent() && !Objects.equals(dueAt.get(), step.getDueAt())) {
            appendFieldChangedHistory(journey, step, callerId, "due_at", step.getDueAt(), dueAt.get());
            step.setDueAt(dueAt.get());
        }
        return step;
    }

    /** C-107 · a plain read, for the controller's {@code ETag} precondition check — no transition, no lock. */
    public ObJourneyStep getStep(long stepId) {
        return requireStep(stepId);
    }

    // ------------------------------------------------------------------
    // C-111 · the read side, and the one write that feeds it
    // ------------------------------------------------------------------

    /**
     * C-111 · one checklist entry as OB-06 needs it — the stored row, plus
     * the mandatory-ness that lives on the template rather than on it.
     *
     * <p>{@code ObJourneyStepItem} carries no {@code mandatory} column by
     * design (its own javadoc says so); the value is joined back through
     * {@code templateItemId}, and an ad-hoc item that no template governs
     * defaults to mandatory. That default is not restated here — it comes
     * from the same lookup {@link #requireCompletionGate} reads, so the
     * panel and the gate cannot disagree about which items hold completion.
     */
    public record ChecklistItem(ObJourneyStepItem row, boolean mandatory) {
    }

    /**
     * C-111 · one required-document entry.
     *
     * <p><b>Derived from the template step, because there is no instance
     * table.</b> {@code ob_journey_step_docs} exists in no migration —
     * {@code ObJourneyStepLifecycleDtos} already says so — so the checklist
     * is the template's and {@code satisfied} is <em>counted</em> rather
     * than matched, exactly as {@link #requireCompletionGate} counts it:
     * nothing links one attachment to one checklist entry, so the first
     * <var>n</var> required rows are satisfied by <var>n</var> clean
     * attachments.
     *
     * <p>That is also why no {@code attachmentId} is carried: naming a
     * specific attachment for a specific entry would invent a link the
     * schema does not have, and a screen offering to open "the" document
     * would open an arbitrary one.
     */
    public record ChecklistDoc(Long id, String label, boolean required, boolean satisfied) {
    }

    public record ObStepChecklist(List<ChecklistItem> items, List<ChecklistDoc> docs) {
    }

    /**
     * Which of these items are mandatory, joined back through {@code
     * templateItemId}.
     *
     * <p>Extracted from {@link #requireCompletionGate}, which is the only
     * reason it exists as a method: the panel that <em>shows</em> the gate
     * and the service that <em>enforces</em> it must read mandatory-ness the
     * same way, including the "an ad-hoc item defaults to mandatory" arm,
     * which lives in the caller's {@code getOrDefault(..., true)}. Two
     * copies of this join would drift, and the drift would show as a screen
     * saying a step can be completed while the server refuses it.
     */
    private Map<Long, Boolean> mandatoryByTemplateItemId(List<ObJourneyStepItem> items) {
        List<Long> templateItemIds = items.stream()
                .map(ObJourneyStepItem::getTemplateItemId)
                .filter(Objects::nonNull)
                .toList();
        return templateStepItems.findAllById(templateItemIds).stream()
                .collect(Collectors.toMap(ObJourneyTemplateStepItem::getId, ObJourneyTemplateStepItem::isMandatory));
    }

    /**
     * C-111 · the Task List and required documents behind OB-06's panel.
     *
     * <p>Read-only and deliberately not guarded by ownership: <em>seeing</em>
     * a step is not <em>acting</em> on it. Plan §9's OB-06 row is explicit
     * that the panel is "read-only for anybody else's step", which is a
     * statement that everybody may read it. {@link #answerItem} is where
     * {@link ObStepOwnership} applies.
     */
    @Transactional(readOnly = true)
    public ObStepChecklist checklistFor(long stepId) {
        ObJourneyStep step = requireStep(stepId);

        List<ObJourneyStepItem> items = stepItems.findByStepIdOrderBySequenceAsc(step.getId());
        Map<Long, Boolean> mandatory = mandatoryByTemplateItemId(items);
        List<ChecklistItem> checklistItems = items.stream()
                .map(item -> new ChecklistItem(item, mandatory.getOrDefault(item.getTemplateItemId(), true)))
                .toList();

        return new ObStepChecklist(checklistItems, docsFor(step));
    }

    /**
     * The template's required-document checklist, with the count-based
     * satisfaction {@link #requireCompletionGate} enforces.
     *
     * <p>Required entries are marked satisfied first and in sequence. With
     * no attachment-to-entry link there is no better answer, and a stable
     * order at least means one entry does not read satisfied on one call
     * and outstanding on the next.
     */
    private List<ChecklistDoc> docsFor(ObJourneyStep step) {
        if (step.getTemplateStepId() == null) {
            return List.of();
        }
        long remaining = attachments.countByStepIdAndScanStatusAndDeletedAtIsNull(
                step.getId(), ObAttachmentScanStatus.CLEAN);

        List<ChecklistDoc> docs = new ArrayList<>();
        for (ObJourneyTemplateStepDoc doc : templateStepDocs.findByStepIdOrderBySequenceAsc(step.getTemplateStepId())) {
            boolean satisfied = true;
            if (doc.isRequired()) {
                satisfied = remaining > 0;
                if (satisfied) {
                    remaining--;
                }
            }
            // An optional entry never holds the gate, so it is never
            // reported outstanding — it has nothing to be outstanding against.
            docs.add(new ChecklistDoc(doc.getId(), doc.getLabel(), doc.isRequired(), satisfied));
        }
        return docs;
    }

    /**
     * C-111 · answer one Task List entry — OB-06's checkbox.
     *
     * <p><b>{@code isDone} means "answered", and that is the only reading
     * under which the panel and the completion gate agree.</b> {@link
     * #requireCompletionGate} filters on {@code getAnswer() == null}: an
     * item answered <em>False</em> satisfies the gate exactly as one
     * answered True does, because §5.8's question is whether the owner has
     * addressed the item, not whether the answer was yes. So {@code
     * isDone: true} records True, and {@code false} returns it to
     * unanswered.
     *
     * <p>🔴 <b>The consequence is that False-with-remark is unreachable
     * through this route, and that is a defect in the contract rather than
     * a choice made here.</b> {@code ObJourneyStepItemUpdateRequest} carries
     * one boolean; the column it writes is a three-state {@code Boolean}
     * whose False arm requires a remark ({@code
     * ck_ob_journey_step_items_remark}). Two states cannot express three.
     * Recording "no, and here is why" needs a contract change — Stream A's,
     * since A-118 owns the schema — and until then this writes only the two
     * states it can name honestly rather than inventing a remark to satisfy
     * a check constraint.
     *
     * @throws JourneyStepItemNotFoundException no such item
     * @throws NotStepOwnerException            caller is neither owner nor backup owner of its step
     * @throws StepAlreadyTerminalException     the step is {@code DONE} or {@code SKIPPED}
     */
    @Transactional
    public ObJourneyStepItem answerItem(long itemId, long callerId, boolean isDone) {
        ObJourneyStepItem item = stepItems.findById(itemId)
                .orElseThrow(() -> new JourneyStepItemNotFoundException(itemId));
        ObJourneyStep step = requireStep(item.getStepId());
        requireOwnership(step, callerId);

        // A closed step's checklist is the record of how it closed. Editing
        // it afterwards would change what the completion gate was satisfied
        // by, retroactively — the same reasoning every other transition
        // applies to a terminal step.
        if (step.getStatus() == ObJourneyStepStatus.DONE || step.getStatus() == ObJourneyStepStatus.SKIPPED) {
            throw new StepAlreadyTerminalException(step.getId(), step.getStatus());
        }

        item.setAnswer(isDone ? Boolean.TRUE : null);
        item.setAnsweredBy(isDone ? callerId : null);
        item.setAnsweredAt(isDone ? Instant.now() : null);
        if (!isDone) {
            // A remark belongs to the answer it explains. Keeping one after
            // clearing the other leaves a reason for a decision no longer
            // recorded.
            item.setRemark(null);
        }
        return item;
    }

    /**
     * C-105 · where a step's TAT budget lands, through the working calendar
     * — {@link WorkingHoursService#addWorkingHours} against {@code tatDays}
     * converted to hours via {@link ObStepTatBudget#hours}.
     *
     * <p>Precise hours rather than {@code OnboardingFixtureSchedule}'s own
     * whole-day walk (start date + N working days, landing at day-end): that
     * generator seeds demo data ahead of this method existing and its own
     * comment says as much. A step that starts mid-morning earns credit for
     * the rest of that day rather than being charged a full one, which is
     * the more defensible answer once the real calculation is available —
     * flagged here rather than silently diverging from the seed corpus.
     *
     * <p>C-114 · the hours-budget conversion moved to {@link ObStepTatBudget}
     * (this method's own body, unchanged) so {@link ObJourneyStepRagService}
     * can share it rather than recompute the same working-day-length maths
     * a second way.
     */
    private Instant computeDueAt(Instant startedAt, int tatDays) {
        return workingHours.addWorkingHours(startedAt, ObStepTatBudget.hours(workingCalendars, tatDays));
    }

    /**
     * C-105 · {@code due_at} recomputation on resume from {@code
     * WAITING_ON_CLIENT}. The step's own {@code due_at} — untouched since it
     * was set, since neither {@link #block} nor {@link #waitOnClient} write
     * it — is exactly what remained of the TAT budget as of the most recent
     * {@code PAUSED} row's {@code occurredAt}: {@link
     * WorkingHoursService#workingHoursBetween} between that instant and the
     * old {@code due_at} gives the working hours still owed, and {@link
     * WorkingHoursService#addWorkingHours} lands them from {@code resumedAt}
     * instead. A step already breached when it paused ({@code pausedAt} at
     * or after the old {@code due_at}) owes zero hours and resumes exactly
     * at {@code resumedAt} — still breached, not handed a fresh grace
     * period it did not earn.
     *
     * @throws IllegalStateException no {@code PAUSED} row exists for this
     *         step — a step cannot reach {@code WAITING_ON_CLIENT} without
     *         {@link #waitOnClient} having written one first
     */
    private void recomputeDueAtOnResume(ObJourneyStep step, Instant resumedAt) {
        ObStepClockEvent lastPause = clockEvents
                .findFirstByStepIdAndEventTypeOrderByOccurredAtDescIdDesc(step.getId(), ObStepClockEventType.PAUSED)
                .orElseThrow(() -> new IllegalStateException(
                        "step " + step.getId() + " is WAITING_ON_CLIENT with no PAUSED clock event on record"));

        BigDecimal hoursOwed = workingHours.workingHoursBetween(lastPause.getOccurredAt(), step.getDueAt());
        step.setDueAt(workingHours.addWorkingHours(resumedAt, hoursOwed));
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

    /**
     * C-119 · plan §5.6's "manual start of a step whose dependency is
     * incomplete is refused, naming the blocking step." {@code
     * dependsOnStepId} is {@code null} = parallel ({@link ObJourneyStep}'s
     * own javadoc), so a dependency-free step never reaches the lookup
     * below at all.
     *
     * <p>The composite FK ({@code fk_ob_journey_steps_depends_on}, scoped to
     * {@code (journey_id, id)}) guarantees the referenced step exists and is
     * a sibling in the same journey — {@code findById} failing here would
     * mean the row it points at was deleted out from under a live FK, not a
     * caller mistake, so it is an {@link IllegalStateException} rather than
     * a checked business refusal.
     */
    private void requireDependencySatisfied(ObJourneyStep step) {
        Long dependsOnStepId = step.getDependsOnStepId();
        if (dependsOnStepId == null) {
            return;
        }
        ObJourneyStep blocker = journeySteps.findById(dependsOnStepId)
                .orElseThrow(() -> new IllegalStateException(
                        "journey step " + step.getId() + " depends on step " + dependsOnStepId
                                + " which does not exist"));
        if (blocker.getStatus() != ObJourneyStepStatus.DONE && blocker.getStatus() != ObJourneyStepStatus.SKIPPED) {
            throw new StepDependencyNotSatisfiedException(step.getId(), blocker.getId(), blocker.getName(),
                    blocker.getStatus());
        }
    }

    /**
     * C-119 · plan §5.6: "completing any step re-evaluates the whole journey
     * and activates every step whose dependency is now satisfied." Called
     * after {@link #complete} and {@link #skip} — either can be the
     * dependency a sibling {@code PENDING} step was waiting on — and by
     * {@code ObJourneyInstantiationService} for a journey instantiated
     * already {@code OPEN}, where this is the first evaluation any step in
     * it has ever had.
     *
     * <p>A no-op while the journey is {@code LOCKED} or held: {@link #skip}
     * does not itself require the journey be open (its own javadoc), so
     * this re-checks fresh rather than trusting the caller's own state —
     * the same defence-in-depth {@link #start}'s own gate check already
     * applies to a manual transition.
     *
     * <p>One pass over every {@code PENDING} step is enough — activating a
     * step moves it to {@code IN_PROGRESS}, which does not itself satisfy
     * anyone else's dependency (only {@code DONE}/{@code SKIPPED} does), so
     * there is nothing for a second pass in the same call to find that the
     * first did not already see.
     */
    void activateEligibleSteps(long journeyId) {
        ObJourney journey = journeys.findById(journeyId)
                .orElseThrow(() -> new IllegalStateException("journey " + journeyId + " does not exist"));
        if (journey.getGateStatus() != ObGateStatus.OPEN || journey.getHeldByJourneyId() != null) {
            return;
        }

        List<ObJourneyStep> steps = journeySteps.findByJourneyIdOrderBySequenceAsc(journeyId);
        Map<Long, ObJourneyStep> byId = steps.stream()
                .collect(Collectors.toMap(ObJourneyStep::getId, s -> s));
        Instant activatedAt = Instant.now();

        for (ObJourneyStep candidate : steps) {
            if (candidate.getStatus() != ObJourneyStepStatus.PENDING) {
                continue;
            }
            Long dependsOnStepId = candidate.getDependsOnStepId();
            ObJourneyStep blocker = dependsOnStepId == null ? null : byId.get(dependsOnStepId);
            boolean satisfied = dependsOnStepId == null
                    || (blocker != null && (blocker.getStatus() == ObJourneyStepStatus.DONE
                            || blocker.getStatus() == ObJourneyStepStatus.SKIPPED));
            if (!satisfied) {
                continue;
            }
            candidate.setStatus(ObJourneyStepStatus.IN_PROGRESS);
            candidate.setStartedAt(activatedAt);
            candidate.setDueAt(computeDueAt(activatedAt, candidate.getTatDays()));
        }
    }

    /**
     * C-107 · two different refusals behind {@code moduleRole}, on {@code
     * ModuleAccessGuard}'s own two-part reasoning even though that guard is
     * not wired into this route yet (see the controller's class javadoc):
     *
     * <ul>
     *   <li><b>No standing in {@code ONBOARDING} at all</b> — {@code
     *       moduleRole} null or blank, exactly what {@link
     *       com.edunext.edutrack.api.security.CallerIdentity#moduleRole}
     *       returns for a caller who holds no grant in the module. Answered
     *       identically to a step that does not exist: {@code
     *       ModuleAccessGuard}'s own javadoc argues a module-gate 403 would
     *       tell a ticketing-only caller that onboarding is deployed at all,
     *       which is a larger disclosure than for one row. Reusing {@link
     *       JourneyStepNotFoundException} rather than inventing a second 404
     *       makes that indistinguishability structural rather than a promise
     *       two exception classes have to keep in step.</li>
     *   <li><b>Holds the module, wrong role within it</b> — {@code OB_SALES},
     *       {@code OB_STEP_OWNER}, {@code OB_VIEWER}, or anything the {@code
     *       ck_user_module_access_module_role} CHECK does not contain. This
     *       is the genuine capability failure {@code contracts/openapi.yaml}
     *       documents as {@code 403} on this route, and it does not leak row
     *       existence — the caller already knows the module exists, since
     *       they hold a role in it.</li>
     * </ul>
     */
    private void requireModerator(long stepId, String moduleRole) {
        if (moduleRole == null || moduleRole.isBlank()) {
            throw new JourneyStepNotFoundException(stepId);
        }
        if (!MODERATOR_ROLES.contains(moduleRole)) {
            throw new NotAnOnboardingModeratorException(moduleRole);
        }
    }

    private void requireNotTerminal(ObJourneyStep step) {
        if (step.getStatus() == ObJourneyStepStatus.DONE || step.getStatus() == ObJourneyStepStatus.SKIPPED) {
            throw new StepAlreadyTerminalException(step.getId(), step.getStatus());
        }
    }

    /**
     * C-107 · the only writer {@code ob_step_history} has today, and it does
     * not write directly: {@code AppendOnlyRulesTest.theProtectedTablesAreWrittenOnlyThroughTheJournal}
     * is stated over {@code assignableTo(AppendOnly.class)}, so {@link
     * ObStepHistoryRepository} — the moment it extends {@code AppendOnly} —
     * falls under the identical door rule {@code TicketHistoryRepository}
     * does. {@link ObStepJournal} is that door, one module over from {@code
     * TicketJournal} in Stream A's {@code domain/journal/} (TEAM-PLAN.md §6,
     * flagged there rather than added quietly). This method only builds the
     * unhashed entry; the lock, the chain tail and the hash are the
     * journal's job.
     */
    private void appendSkippedHistory(ObJourney journey, ObJourneyStep step, ObJourneyStepStatus previousStatus,
                                       long actorId, String reason) {
        ObStepHistory entry = new ObStepHistory();
        entry.setJourneyId(journey.getId());
        entry.setStepId(step.getId());
        entry.setObClientId(journey.getObClientId());
        entry.setEventType("SKIPPED");
        entry.setFieldName("status");
        entry.setOldValue(previousStatus.name());
        entry.setNewValue(ObJourneyStepStatus.SKIPPED.name());
        entry.setActorId(actorId);
        entry.setActorType("USER");
        entry.setRemarks(reason);
        stepJournal.append(entry);
    }

    /**
     * C-108 · one {@code FIELD_CHANGED} row per field {@link #update} actually
     * changes — {@code TicketWriteService#patch}'s own convention one module
     * over ("one FIELD_CHANGED history row per field that actually
     * differs"), reused here rather than invented fresh.
     */
    private void appendFieldChangedHistory(ObJourney journey, ObJourneyStep step, long actorId,
                                            String fieldName, Object oldValue, Object newValue) {
        ObStepHistory entry = new ObStepHistory();
        entry.setJourneyId(journey.getId());
        entry.setStepId(step.getId());
        entry.setObClientId(journey.getObClientId());
        entry.setEventType("FIELD_CHANGED");
        entry.setFieldName(fieldName);
        entry.setOldValue(oldValue == null ? null : oldValue.toString());
        entry.setNewValue(newValue == null ? null : newValue.toString());
        entry.setActorId(actorId);
        entry.setActorType("USER");
        stepJournal.append(entry);
    }
}
