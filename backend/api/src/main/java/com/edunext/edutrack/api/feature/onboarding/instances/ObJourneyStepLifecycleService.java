package com.edunext.edutrack.api.feature.onboarding.instances;

import com.edunext.edutrack.domain.journal.ObStepJournal;
import com.edunext.edutrack.domain.masters.WorkingCalendarRepository;
import com.edunext.edutrack.domain.masters.WorkingHoursService;
import com.edunext.edutrack.domain.onboarding.ObAttachmentRepository;
import com.edunext.edutrack.domain.onboarding.ObAttachmentScanStatus;
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
import com.edunext.edutrack.domain.onboarding.ObProject;
import com.edunext.edutrack.domain.onboarding.ObProjectRepository;
import com.edunext.edutrack.domain.onboarding.ObProjectStatus;
import com.edunext.edutrack.domain.onboarding.ObSignoffKind;
import com.edunext.edutrack.domain.onboarding.ObSignoffRepository;
import com.edunext.edutrack.domain.onboarding.ObSignoffStatus;
import com.edunext.edutrack.domain.onboarding.ObStepClockAttribution;
import com.edunext.edutrack.domain.onboarding.ObStepClockActorType;
import com.edunext.edutrack.domain.onboarding.ObStepClockEvent;
import com.edunext.edutrack.domain.onboarding.ObStepClockEventRepository;
import com.edunext.edutrack.domain.onboarding.ObStepClockEventType;
import com.edunext.edutrack.domain.onboarding.ObStepHistory;
import com.edunext.edutrack.domain.onboarding.ObStepReviewState;
import com.edunext.edutrack.domain.onboarding.ObStepRowState;
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

        C-107's own skip() reads the same repository the same way, for a         sixth transition — a plain findById for obClientId, to put on the         ob_step_history row it builds. The FOR UPDATE lock that append needs         is ObStepJournal's own, taken inside domain/journal/ where         ScopeGuardRulesTest does not reach; this class never calls         findByIdForUpdate itself.

        C-123's settleJourney reads the same parent once more, for         completed_at — again the step's own journey, again after the         caller has proven they may act on that step.""")
public class ObJourneyStepLifecycleService {

    /** Plan §3's "override steps with logged reason" — {@link #skip}'s own capability. */
    private static final Set<String> MODERATOR_ROLES = Set.of("OB_MANAGER", "OB_ADMIN");

    /**
     * The one role that reviews without being named on the project — see
     * {@link #requireReviewer} for why the escape hatch exists at all.
     */
    private static final String OB_ADMIN_ROLE = "OB_ADMIN";

    /**
     * C-105 · the only pausing reason written today — {@code
     * ck_ob_clock_pause_reason}'s mandatory value on a {@code PAUSED} row.
     * See {@link ObStepClockEvent#getPauseReason()} for why the column
     * itself stays a plain string rather than an enum with one member.
     */
    private static final String WAITING_ON_CLIENT_PAUSE_REASON = "WAITING_ON_CLIENT";

    /**
     * The second pausing reason — a task sitting in {@code PENDING_REVIEW}.
     *
     * <p>Distinct from {@link #WAITING_ON_CLIENT_PAUSE_REASON} because the
     * two waits are charged to different people: {@code ObStepClockAttribution}
     * is {@code CLIENT} there and {@code INTERNAL} here, and the TAT report
     * that answers "who was this time lost to" reads exactly that. A review
     * queue backing up is our problem, and it should look like ours.
     */
    private static final String PENDING_REVIEW_PAUSE_REASON = "PENDING_REVIEW";

    /**
     * B-115 · the {@code gateFailures} vocabulary, named here because
     * {@link #completeOnClientAcceptance} is where they are produced and the
     * contract calls them <em>stable</em> — OB-09 and the owner's view both
     * branch on the string, so a literal retyped at a call site is a silent
     * dead branch on whichever side did not change.
     */
    private static final String GATE_ITEMS_UNANSWERED = "ob-step-items-unanswered";

    private static final String GATE_DOCS_MISSING = "ob-step-docs-missing";

    /**
     * Unreachable from {@link #completeOnClientAcceptance} today — the sign-off
     * is {@code SIGNED} before it is called — and mapped rather than omitted so
     * the gate cannot acquire a failure the response silently drops.
     */
    private static final String GATE_SIGNOFF_MISSING = "ob-step-signoff-missing";

    /** Not a gate failure but the same kind of answer: our side is not ready. */
    private static final String GATE_STEP_NOT_IN_PROGRESS = "ob-step-not-in-progress";

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
    private final ObJourneyDependencyRelease dependencyRelease;
    private final ObProjectRepository projects;

    public ObJourneyStepLifecycleService(ObJourneyStepRepository journeySteps, ObJourneyRepository journeys,
            ObJourneyStepItemRepository stepItems, ObJourneyTemplateStepItemRepository templateStepItems,
            ObJourneyTemplateStepDocRepository templateStepDocs, ObAttachmentRepository attachments,
            ObSignoffRepository signoffs, ObStepJournal stepJournal, WorkingHoursService workingHours,
            WorkingCalendarRepository workingCalendars, ObStepClockEventRepository clockEvents,
            ObStepClockRecorder clockRecorder, ObJourneyDependencyRelease dependencyRelease,
            ObProjectRepository projects) {
        this.dependencyRelease = dependencyRelease;
        this.projects = projects;
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
     * {@code PENDING → IN_PROGRESS}. Refused while the journey is held by
     * another ({@code held_by_journey_id}) — that hold waits on work nobody
     * on this journey can do.
     *
     * <h3>The prerequisite gate no longer refuses a start</h3>
     *
     * <p>Plan §5.2/§5.3's "clocks dead until the gate opens" held for every
     * path into {@code IN_PROGRESS}, so a client whose checklist was one
     * unverified document short could not begin any implementation work at
     * all. Operations asked for the checklist to be advisory: a
     * {@code LOCKED} gate is now something the screens <em>report</em> — the
     * chip, the banner and the checklist are all unchanged — and not
     * something that refuses the owner's own Start.
     *
     * <p>What the gate still does is the automatic half: {@link
     * ObPrerequisiteGateService} flips every {@code LOCKED} journey
     * {@code OPEN} when the last mandatory task verifies, activates the
     * first wave of steps and fires the kickoff mail. A journey born
     * {@code LOCKED} still activates nothing by itself — its owners choose
     * when to start, which is the whole of what "optional" buys them — so no
     * TAT clock starts behind anybody's back.
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
     * @throws JourneyNotOpenException           the journey is held behind a sibling
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
        if (journey.getHeldByJourneyId() != null) {
            throw new JourneyNotOpenException(journey.getId(), journey.getHeldByJourneyId());
        }
        requireDependencySatisfied(step);

        Instant startedAt = Instant.now();
        step.setStatus(ObJourneyStepStatus.IN_PROGRESS);
        step.setStartedAt(startedAt);
        step.setDueAt(computeDueAt(startedAt, step.getTatDays()));
        appendStatusHistory(journey, step, "STEP_ACTIVATED",
                ObJourneyStepStatus.PENDING, ObJourneyStepStatus.IN_PROGRESS, callerId, "status", null);
        return step;
    }

    /**
     * {@code IN_PROGRESS →} either {@code PENDING_REVIEW} or {@code DONE}.
     * C-106's completion gate runs after the transition check and before
     * anything is written — see {@link #requireCompletionGate} and the class
     * javadoc.
     *
     * <h2>Marking a task complete no longer closes it</h2>
     *
     * <p>Where {@link ObJourneyStep#isRequiresReview()} is set — the default,
     * for every template step and every in-flight task ({@code
     * V20260916_1700}) — this submits rather than closes: the task lands in
     * {@code PENDING_REVIEW} and an OB Manager decides.
     *
     * <h2>The implementor presses this twice</h2>
     *
     * <p>The first press submits the work. The second closes the task, and it
     * is only reachable once the review has come back with every row accepted
     * — {@link #reviewPassed}, which is what this branches on. In between, the
     * button is not theirs to press at all: the task is {@code PENDING_REVIEW},
     * {@link #requireStatus} refuses it, and the bar hides it rather than
     * offering a refusal.
     *
     * <p>So the manager's word is not the last one either. Review checks the
     * implementor's claim; it does not replace it, and the act of closing
     * stays with the person accountable for the work — see
     * {@link #returnAccepted}.
     *
     * <p>The <em>method name is unchanged deliberately</em>. It is the same
     * act by the same person from the same button; what differs is where it
     * lands. Renaming it to {@code submit} would leave every caller, test and
     * contract description saying "complete" about a route called something
     * else, and would suggest a second route exists for the other case — it
     * does not. Where review is off, this closes the task exactly as it
     * always has.
     *
     * <p>C-119 · once {@code DONE} — by either path — {@link
     * #activateEligibleSteps} re-evaluates the journey: any sibling step
     * whose only dependency was this one moves straight to {@code
     * IN_PROGRESS}. That half lives in {@link #closeStep}, called from here
     * and from {@link #closeReview}, so the cascade stays written once.
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

        ObJourney journey = requireJourney(step);
        return needsReview(step) && !reviewPassed(step)
                ? submitForReview(journey, step, callerId)
                : closeStep(journey, step, ObJourneyStepStatus.IN_PROGRESS, callerId);
    }

    /**
     * Has this task's check list already been through review and come back
     * accepted?
     *
     * <p>Every row {@code VERIFIED} is the whole condition, and it needs no
     * column of its own: it is only reachable from {@link #closeReview}'s
     * accepted branch, because a rejection leaves at least one row
     * {@code REJECTED} and a resubmission puts those rows back to
     * {@code NOT_REVIEWED}. So the check list carries the answer already, and
     * a {@code reviewPassedAt} stamp would be a second place for the same
     * fact to be wrong in.
     *
     * <p>This is what stops the implementor's closing press from bouncing
     * back into review. Their first press submits; the manager accepts; their
     * second press closes. Without it {@link #complete} would see
     * {@code requiresReview} and submit a second time, and the task would
     * never leave the loop.
     */
    private boolean reviewPassed(ObJourneyStep step) {
        List<ObJourneyStepItem> items = stepItems.findByStepIdOrderBySequenceAsc(step.getId());
        return !items.isEmpty()
                && items.stream().allMatch(i -> i.getRowState() == ObStepRowState.VERIFIED);
    }

    /**
     * Whether this task has anything for a manager to read.
     *
     * <h2>An empty check list is not a review, it is a dead end</h2>
     *
     * <p>{@link ObJourneyStep#isRequiresReview()} alone is not enough. A
     * verdict is recorded <em>per row</em> — {@link #reviewItem} is the only
     * way into {@link #closeReview} — so a task carrying no rows at all has
     * nothing to press, never settles, and sits in {@code PENDING_REVIEW}
     * with no route out for anybody, including an admin.
     *
     * <p>Observed on live data 17 Sep 2026: four tasks
     * ({@code Old Data Migration}, {@code Student Group}) whose templates
     * define no check list were submitted and stranded. They are not rare —
     * plenty of template steps are a single act with nothing to tick.
     *
     * <p>So a task with no rows closes on completion exactly as it did before
     * the review gate existed. That is also the honest reading: review here
     * means "somebody checked the check list", and there is no check list to
     * have checked. The alternative — a task-level approve button for this
     * case only — would put a second, differently-shaped way to close a task
     * on the one screen, reachable only on the tasks where the reviewer has
     * least to go on.
     */
    private boolean needsReview(ObJourneyStep step) {
        return step.isRequiresReview()
                && !stepItems.findByStepIdOrderBySequenceAsc(step.getId()).isEmpty();
    }

    /**
     * {@code IN_PROGRESS → PENDING_REVIEW} — the implementor's half of the
     * review gate.
     *
     * <h2>A resubmission is a smaller review than the first one</h2>
     *
     * <p>Only the rows that came back go in front of the manager again:
     * {@code REJECTED} returns to {@code NOT_REVIEWED}, and a {@code
     * VERIFIED} row keeps its verdict and is skipped. That is what keeps a
     * third and a fourth round affordable — round two puts one row on the
     * screen, not five — and it is the same fact that
     * {@link StepItemAlreadyVerifiedException} enforces against writes.
     *
     * <p><b>The TAT clock pauses here</b>, on the {@code PAUSED} row {@link
     * #waitOnClient} already uses, and resumes in {@link #closeReview} if
     * the task comes back. Charging an implementor for the hours their work
     * spends in somebody else's inbox measures the wrong person — and the
     * attribution is {@code INTERNAL} rather than {@code CLIENT}, because
     * this wait is ours.
     */
    private ObJourneyStep submitForReview(ObJourney journey, ObJourneyStep step, long callerId) {
        Instant now = Instant.now();
        for (ObJourneyStepItem item : stepItems.findByStepIdOrderBySequenceAsc(step.getId())) {
            if (item.getRowState().isWithImplementor() && item.getAnswer() != null) {
                sendRow(item, now, callerId);
            }
        }
        settleAfterRowMove(journey, step, now, callerId);
        return step;
    }

    /**
     * One row on to the reviewer's desk — the per-row send, {@code C-141}.
     *
     * <p>Every route that sends anything goes through here, so the bulk press
     * and the row's own button cannot drift apart: what <b>Mark complete</b>
     * does to five rows is exactly five of these.
     *
     * <p>A row that comes back and goes out again returns to
     * {@code NOT_REVIEWED}: round two puts one row in front of the manager
     * rather than five, and a verdict from the round before must not be read
     * as a verdict on the work that has replaced it. {@code outcomeSeenAt} is
     * cleared with it — the outcome it referred to no longer exists.
     */
    private void sendRow(ObJourneyStepItem item, Instant now, long callerId) {
        item.setRowState(ObStepRowState.SENT);
        item.setSubmittedAt(now);
        item.setSubmittedBy(callerId);
        item.setReviewState(ObStepReviewState.NOT_REVIEWED);
        item.setReviewedBy(null);
        item.setReviewedAt(null);
        item.setOutcomeSeenAt(null);
    }

    /**
     * One row back to its implementor, carrying the verdict the reviewer
     * recorded on it.
     *
     * <p>{@code outcomeSeenAt} is set to null rather than left alone: this is
     * a new outcome, and it is unseen until the implementor opens the task —
     * which is the whole of what makes the banner and the My Tasks highlight
     * a signal rather than permanent decoration.
     */
    private void releaseRow(ObJourneyStepItem item, Instant now, long reviewerId) {
        if (item.getReviewState() == ObStepReviewState.REJECTED
                && (item.getRemark() == null || item.getRemark().isBlank())) {
            throw new RejectReasonRequiredException(item.getId());
        }
        item.setRowState(item.getReviewState() == ObStepReviewState.VERIFIED
                ? ObStepRowState.VERIFIED
                : ObStepRowState.REJECTED);
        item.setReviewedBy(reviewerId);
        item.setReviewedAt(now);
        item.setOutcomeSeenAt(null);

        if (item.getRowState() == ObStepRowState.REJECTED) {
            // The claim is withdrawn with the verdict — see closeReview.
            item.setAnswer(null);
            item.setAnsweredBy(null);
            item.setAnsweredAt(null);
        }
    }

    /**
     * The task's status, recomputed from its rows — the one place
     * {@code PENDING_REVIEW} is written or taken away.
     *
     * <h2>Why the column survives at all</h2>
     *
     * <p>With the row carrying the state machine, a task's "status" is
     * properly a reading of its rows. It is still stored, because
     * {@link #requireCompletionGate}, {@link #activateEligibleSteps}, the RAG
     * service, My Tasks and every dashboard query read that column — teaching
     * each of them to aggregate rows instead would be five copies of this
     * method, in SQL, kept in step by hand. So it is materialised here, once,
     * after every row that moves.
     *
     * <p><b>Only the review pair is touched.</b> {@code PENDING},
     * {@code BLOCKED}, {@code WAITING_ON_CLIENT}, {@code DONE} and
     * {@code SKIPPED} are facts about the task that no row can contradict, so
     * a task in any of them is left exactly where it is — a blocked task whose
     * rows are being reviewed stays blocked.
     *
     * <p>The clock pairs with the same transition: {@code PAUSED} as the first
     * row goes out, {@code RESUMED} as the last one comes back. Per-row
     * pausing would be the more precise answer and the wrong one — the ledger
     * is per step, and five overlapping pauses on one step cannot be summed.
     */
    private void settleAfterRowMove(ObJourney journey, ObJourneyStep step, Instant now, long actorId) {
        List<ObJourneyStepItem> items = stepItems.findByStepIdOrderBySequenceAsc(step.getId());
        boolean anyOut = items.stream().anyMatch(i -> i.getRowState() == ObStepRowState.SENT);
        ObJourneyStepStatus was = step.getStatus();

        if (anyOut && was == ObJourneyStepStatus.IN_PROGRESS) {
            step.setStatus(ObJourneyStepStatus.PENDING_REVIEW);
            step.setSubmittedAt(now);
            step.setSubmittedBy(actorId);
            appendStatusHistory(journey, step, "SUBMITTED_FOR_REVIEW",
                    ObJourneyStepStatus.IN_PROGRESS, ObJourneyStepStatus.PENDING_REVIEW,
                    actorId, "status", outstandingNote(items));

            ObStepClockEvent paused = new ObStepClockEvent();
            paused.setStepId(step.getId());
            paused.setJourneyId(step.getJourneyId());
            paused.setEventType(ObStepClockEventType.PAUSED);
            paused.setPauseReason(PENDING_REVIEW_PAUSE_REASON);
            paused.setAttributedTo(ObStepClockAttribution.INTERNAL);
            paused.setOccurredAt(now);
            paused.setActorId(actorId);
            paused.setActorType(ObStepClockActorType.USER);
            clockRecorder.record(paused);
            return;
        }

        if (!anyOut && was == ObJourneyStepStatus.PENDING_REVIEW) {
            List<String> rejected = items.stream()
                    .filter(i -> i.getRowState() == ObStepRowState.REJECTED)
                    .map(ObJourneyStepItem::getLabel)
                    .toList();

            step.setStatus(ObJourneyStepStatus.IN_PROGRESS);
            step.setReviewedAt(now);
            step.setReviewedBy(actorId);
            appendStatusHistory(journey, step,
                    rejected.isEmpty() ? "REVIEW_ACCEPTED" : "REVIEW_REJECTED",
                    ObJourneyStepStatus.PENDING_REVIEW, ObJourneyStepStatus.IN_PROGRESS,
                    actorId, "status",
                    rejected.isEmpty()
                            ? "every row verified \u2014 with " + ownerLabel(step) + " to close"
                            : rejectionRemarks(rejected));

            recomputeDueAtOnResume(step, now);

            ObStepClockEvent resumed = new ObStepClockEvent();
            resumed.setStepId(step.getId());
            resumed.setJourneyId(step.getJourneyId());
            resumed.setEventType(ObStepClockEventType.RESUMED);
            resumed.setAttributedTo(ObStepClockAttribution.INTERNAL);
            resumed.setOccurredAt(now);
            resumed.setActorId(actorId);
            resumed.setActorType(ObStepClockActorType.USER);
            clockRecorder.record(resumed);
        }
    }

    /** What went out, for a history row a person reads rather than decodes. */
    private static String outstandingNote(List<ObJourneyStepItem> items) {
        long out = items.stream().filter(i -> i.getRowState() == ObStepRowState.SENT).count();
        long kept = items.size() - out;
        return kept == 0
                ? out + (out == 1 ? " row sent for review" : " rows sent for review")
                : out + " of " + items.size() + " rows sent for review";
    }

    /**
     * The {@code DONE} half, named once because two paths reach it.
     *
     * <p>{@code finishedAt}, the history row, the dependency cascade and the
     * journey settle used to sit inline in {@link #complete}. Review gave
     * them a second caller in {@link #closeReview}, and two copies of a
     * four-step closing sequence is one edit away from a task that completes
     * without releasing whatever was waiting on it.
     *
     * @param previousStatus what the step is moving from — {@code IN_PROGRESS}
     *                       on the unreviewed path, {@code PENDING_REVIEW}
     *                       when a review passed. The history row records it,
     *                       so it cannot be assumed.
     */
    private ObJourneyStep closeStep(ObJourney journey, ObJourneyStep step,
                                    ObJourneyStepStatus previousStatus, long actorId) {
        step.setStatus(ObJourneyStepStatus.DONE);
        step.setFinishedAt(Instant.now());
        appendStatusHistory(journey, step, "COMPLETED",
                previousStatus, ObJourneyStepStatus.DONE, actorId, "status", null);
        activateEligibleSteps(step.getJourneyId());
        settleJourney(step.getJourneyId());
        return step;
    }

    /**
     * One row's verdict — the OB Manager's only write in this feature.
     *
     * <h2>There is no "close the review" call</h2>
     *
     * <p>The task moves when the last row is decided, not when somebody
     * <p><b>A verdict decides a row, never the task.</b> Recording one leaves
     * the review open, so the reviewer may cycle a row as often as they like —
     * {@code Not reviewed → Verified → Rejected} — and change their mind right
     * up until they say they are finished. {@link #closeReview} is where the
     * task moves, and it moves once — <b>back to its implementor either way</b>:
     * accepted, with every row locked and nothing left but the closing press;
     * or rejected, with the refused rows reopened and unanswered.
     *
     * <p>This used to settle the task on the last verdict, which read as
     * economical and was simply wrong: on a single-row check list the first
     * press closed the task, locked the row and released its dependants — so
     * the three-state control had exactly one usable position and a reviewer
     * who meant Rejected had no way to say so.
     *
     * <p><b>Gated by {@link #requireReviewer}, not by ownership</b>: reviewing
     * is by definition an act on somebody else's work, so
     * {@link #requireOwnership} would refuse exactly the caller this route
     * exists for. It is the project's own
     * {@code implementor_manager_user_id} — not a role anybody can hold — so
     * a manager reviews the engagements they are accountable for and nothing
     * else.
     *
     * @param verdict {@code NOT_REVIEWED} is a legitimate value — it is how a
     *                manager takes back a mark they pressed by mistake, and
     *                it clears {@code reviewedBy}/{@code reviewedAt} with it.
     * @param remark  the reason, written to the row's own {@code remark} on a
     *                {@code REJECTED} and ignored otherwise, so a verdict of
     *                Verified can never rewrite the implementor's note it is
     *                passing.
     *
     *                <p><b>Not required here.</b> Rejecting is one press of a
     *                cycling button and the box that holds the reason only
     *                opens once the row is rejected — demanding it at this
     *                moment made the press impossible, which is exactly what
     *                it did until {@code V20260917_1015}. The rule is enforced
     *                where the review is finished instead: {@link #closeReview}
     *                refuses while any rejected row is unexplained, so nothing
     *                reaches an implementor without a reason.
     *
     * @throws JourneyStepItemNotFoundException    no such row
     * @throws JourneyStepNotFoundException        the caller holds no onboarding role at all —
     *                                             404 rather than 403, so the route discloses nothing
     * @throws NotAnOnboardingModeratorException   the caller is neither this project's implementor
     *                                             manager nor an {@code OB_ADMIN}
     * @throws InvalidStepTransitionException      the step is not {@code PENDING_REVIEW}
     * @throws StepItemAlreadyVerifiedException    the row was verified in an earlier round
     */
    @Transactional
    public ObJourneyStepItem reviewItem(long itemId, long callerId, String moduleRole,
                                        ObStepReviewState verdict, String remark) {
        ObJourneyStepItem item = stepItems.findById(itemId)
                .orElseThrow(() -> new JourneyStepItemNotFoundException(itemId));
        ObJourneyStep step = requireStep(item.getStepId());
        requireReviewer(step, callerId, moduleRole);

        // A verdict is recordable exactly while the row is on the reviewer's
        // desk. Released rows are final — that is what `rowState` says, and it
        // replaces the submittedAt/reviewedAt comparison this used to need to
        // tell "verified in this review" from "verified in an earlier one".
        if (item.getRowState() != ObStepRowState.SENT) {
            throw item.getRowState() == ObStepRowState.VERIFIED
                    ? new StepItemAlreadyVerifiedException(itemId)
                    : new InvalidStepTransitionException(step.getId(), "review", step.getStatus());
        }

        String trimmed = remark == null || remark.isBlank() ? null : remark.trim();

        item.setReviewState(verdict);
        item.setReviewedBy(verdict.isDecided() ? callerId : null);
        item.setReviewedAt(verdict.isDecided() ? Instant.now() : null);
        // Only ever written, never cleared. Rejecting is one press of a cycling
        // button and the reason is typed afterwards, so a reject that arrives
        // without one must not wipe what is already on the row — and a verdict
        // of Verified must not touch the implementor's own note at all.
        if (verdict == ObStepReviewState.REJECTED && trimmed != null) {
            item.setRemark(trimmed);
        }

        // Nothing settles here. A verdict is a mark on a row, not a decision
        // about the task — the reviewer may cycle any row as often as they
        // like, and `closeReview` is where they say they are finished.
        return item;
    }

    /**
     * The manager's deliberate close — {@code PENDING_REVIEW → DONE}.
     *
     * <h2>Why rejection settles itself and acceptance does not</h2>
     *
     * <p>They are not the same kind of act. A rejection is <em>already</em>
     * explicit: the manager pressed Rejected and typed a reason, so the task
     * going back needs no second confirmation. Accepting is one press of a
     * button whose previous position was also one press away, and closing on
     * it would take the control away in the same instant — a manager who
     * pressed Verified meaning Reject would find the row locked and the task
     * closed, with its dependants already released.
     *
     * <p>So a verdict of Verified is reversible for as long as the review is
     * open, and this is what ends it. It is also the irreversible half — it
     * sets {@code finishedAt}, releases whatever was waiting on this task and
     * can settle the whole Step — which is exactly the kind of thing worth one
     * deliberate press.
     *
     * @throws JourneyStepNotFoundException      no such step, or the caller holds
     *                                           no onboarding role at all
     * @throws NotAnOnboardingModeratorException the caller is neither this project's
     *                                           implementor manager nor an {@code OB_ADMIN}
     * @throws InvalidStepTransitionException    the step is not {@code PENDING_REVIEW}
     * @throws CompletionGateException           a row is still unreviewed, or one was
     *                                           rejected — in which case the task is on
     *                                           its way back and there is nothing to close
     */
    @Transactional
    public ObJourneyStep closeReview(long stepId, long callerId, String moduleRole) {
        ObJourneyStep step = requireStep(stepId);
        requireReviewer(step, callerId, moduleRole);
        requireStatus(step, "close review on", ObJourneyStepStatus.PENDING_REVIEW);

        List<ObJourneyStepItem> items = stepItems.findByStepIdOrderBySequenceAsc(step.getId());
        List<ObJourneyStepItem> out = items.stream()
                .filter(i -> i.getRowState() == ObStepRowState.SENT)
                .toList();

        List<String> undecided = out.stream()
                .filter(i -> i.getReviewState() == ObStepReviewState.NOT_REVIEWED)
                .map(ObJourneyStepItem::getLabel)
                .toList();
        if (!undecided.isEmpty()) {
            // The owner's own completion answers with this shape, so a client
            // that understands one understands both.
            throw new CompletionGateException(stepId, undecided, 0, false);
        }

        ObJourney journey = requireJourney(step);
        Instant now = Instant.now();

        // Reasons are checked across the whole set before anything is written,
        // so a press that is going to be refused refuses having changed
        // nothing. `releaseRow` throws on its own row; doing it here as well
        // is what makes the refusal all-or-nothing rather than "the first two
        // went and the third did not".
        for (ObJourneyStepItem row : out) {
            if (row.getReviewState() == ObStepReviewState.REJECTED
                    && (row.getRemark() == null || row.getRemark().isBlank())) {
                throw new RejectReasonRequiredException(row.getId());
            }
        }
        for (ObJourneyStepItem row : out) {
            releaseRow(row, now, callerId);
        }

        if (out.stream().anyMatch(i -> i.getRowState() == ObStepRowState.REJECTED)) {
            step.setReviewRound(step.getReviewRound() + 1);
        }
        settleAfterRowMove(journey, step, now, callerId);
        return step;
    }

    /**
     * One row out for review — the implementor's per-row Send, {@code C-141}.
     *
     * <h2>Two of five, and the other three keep</h2>
     *
     * <p>This is the half of the row-level flow that belongs to the person
     * doing the work: a row is finished, so it goes, and nothing waits for the
     * rest of the check list. The task's own <b>Mark complete</b> is unchanged
     * and still sends everything that is ready in one press — it is this
     * method five times over — so the bulk path and the row path cannot drift.
     *
     * <p><b>Answered first.</b> A row with no answer has nothing to verify;
     * sending one would put a blank line in front of a reviewer and ask them
     * what they think of it.
     *
     * @throws JourneyStepItemNotFoundException no such row
     * @throws NotStepOwnerException            not the caller's row to send
     * @throws StepAlreadyTerminalException     the task is closed
     * @throws StepUnderReviewException         the row is already out
     * @throws StepItemAlreadyVerifiedException the row is approved and shut
     * @throws CompletionGateException          the row has no answer yet
     */
    @Transactional
    public ObJourneyStepItem submitItem(long itemId, long callerId) {
        ObJourneyStepItem item = stepItems.findById(itemId)
                .orElseThrow(() -> new JourneyStepItemNotFoundException(itemId));
        ObJourneyStep step = requireStep(item.getStepId());
        requireOwnership(step, callerId);

        if (step.getStatus().isTerminal()) {
            throw new StepAlreadyTerminalException(step.getId(), step.getStatus());
        }
        if (item.getRowState() == ObStepRowState.VERIFIED) {
            throw new StepItemAlreadyVerifiedException(itemId);
        }
        if (item.getRowState() == ObStepRowState.SENT) {
            throw new StepUnderReviewException(step.getId(), itemId);
        }
        if (item.getAnswer() == null) {
            throw new CompletionGateException(step.getId(), List.of(item.getLabel()), 0, false);
        }

        ObJourney journey = requireJourney(step);
        Instant now = Instant.now();
        sendRow(item, now, callerId);
        settleAfterRowMove(journey, step, now, callerId);
        return item;
    }

    /**
     * One row back to its implementor — the reviewer's per-row Send,
     * {@code C-141}.
     *
     * <h2>Recording a verdict is not sending one</h2>
     *
     * <p>{@link #reviewItem} cycles the verdict and keeps it reversible;
     * this is what releases it. Two presses deliberately: the first is a
     * thought, the second is a message somebody else starts acting on, and
     * releasing on the first would turn a mis-click into work another person
     * begins doing.
     *
     * <p>The task itself moves only when the <em>last</em> row comes back —
     * {@link #settleAfterRowMove} — so a manager may release two now and read
     * the rest after lunch without the task pretending the review is over.
     *
     * @throws InvalidStepTransitionException   the row is not out for review
     * @throws CompletionGateException          no verdict recorded on it yet
     * @throws RejectReasonRequiredException    rejected with an empty remark
     */
    @Transactional
    public ObJourneyStepItem releaseItem(long itemId, long callerId, String moduleRole) {
        ObJourneyStepItem item = stepItems.findById(itemId)
                .orElseThrow(() -> new JourneyStepItemNotFoundException(itemId));
        ObJourneyStep step = requireStep(item.getStepId());
        requireReviewer(step, callerId, moduleRole);

        if (item.getRowState() != ObStepRowState.SENT) {
            throw new InvalidStepTransitionException(step.getId(), "send back", step.getStatus());
        }
        if (item.getReviewState() == ObStepReviewState.NOT_REVIEWED) {
            throw new CompletionGateException(step.getId(), List.of(item.getLabel()), 0, false);
        }

        ObJourney journey = requireJourney(step);
        Instant now = Instant.now();
        releaseRow(item, now, callerId);
        if (item.getRowState() == ObStepRowState.REJECTED) {
            step.setReviewRound(step.getReviewRound() + 1);
        }
        settleAfterRowMove(journey, step, now, callerId);
        return item;
    }

    /**
     * The whole check list to the reviewer in one press — the implementor's
     * <b>Send for verification</b>.
     *
     * <h2>One request, not one per row</h2>
     *
     * <p>The screen sends the list as a unit now: there is no per-row Send and
     * a manager gives one verdict for the whole thing, so a half-sent check
     * list is a state neither side has a control for. Looping
     * {@link #submitItem} from the client produced exactly that whenever the
     * third of five calls failed — four rows on the reviewer's desk, one still
     * with its implementor, and a task whose {@code PENDING_REVIEW} was true
     * of most of it. One transaction here means the list moves or none of it
     * does.
     *
     * <p>It is {@link #sendRow} over every row rather than a second way of
     * sending one, so the row path and the bulk path cannot drift — the same
     * reason {@link #submitItem}'s own note gives.
     *
     * <h2>Every row must be answered</h2>
     *
     * <p>{@link #submitItem} let an implementor send two of five and keep the
     * rest; sending the list as a unit means the unit has to be complete. An
     * unanswered row is named in the gate rather than silently skipped, so the
     * refusal says which line to go back to.
     *
     * <p>Rows already {@code VERIFIED} are left alone and are not counted
     * against the gate: they are shut for good, and a rejection that brought
     * their neighbours back must not ask for them again.
     *
     * @return the step, whose status {@link #settleAfterRowMove} has moved to
     *         {@code PENDING_REVIEW}
     * @throws JourneyStepNotFoundException no such step
     * @throws NotStepOwnerException        not the caller's task to send
     * @throws StepAlreadyTerminalException the task is closed
     * @throws StepUnderReviewException     the list is already out
     * @throws CompletionGateException      a row is still unanswered
     */
    @Transactional
    public ObJourneyStep submitChecklist(long stepId, long callerId) {
        ObJourneyStep step = requireStep(stepId);
        requireOwnership(step, callerId);

        if (step.getStatus().isTerminal()) {
            throw new StepAlreadyTerminalException(step.getId(), step.getStatus());
        }

        List<ObJourneyStepItem> items = stepItems.findByStepIdOrderBySequenceAsc(stepId);
        List<ObJourneyStepItem> open = items.stream()
                .filter(item -> item.getRowState() != ObStepRowState.VERIFIED)
                .toList();

        List<String> unanswered = open.stream()
                .filter(item -> item.getAnswer() == null)
                .map(ObJourneyStepItem::getLabel)
                .toList();
        if (!unanswered.isEmpty()) {
            throw new CompletionGateException(stepId, unanswered, 0, false);
        }

        List<ObJourneyStepItem> toSend = open.stream()
                .filter(item -> item.getRowState() != ObStepRowState.SENT)
                .toList();
        if (toSend.isEmpty()) {
            // Either it is already on the reviewer's desk, or nothing is left
            // that is not shut. Both mean there is nothing to send, and
            // neither is a press that should look like it worked.
            throw new StepUnderReviewException(step.getId(), 0);
        }

        ObJourney journey = requireJourney(step);
        Instant now = Instant.now();
        toSend.forEach(item -> sendRow(item, now, callerId));
        settleAfterRowMove(journey, step, now, callerId);
        return step;
    }

    /**
     * One verdict for the whole check list — the reviewer's <b>Verification
     * done</b>.
     *
     * <h2>Why the verdict is not per row any more</h2>
     *
     * <p>A manager decides about the task, not about line four: they read the
     * list and either it is right or it goes back. Per-row verdicts asked them
     * to record five decisions to express one, and let a list return
     * half-approved — a state the implementor then had to reconcile row by
     * row. So this records the same verdict on every row that is out and
     * releases them together.
     *
     * <p><b>A rejection returns the whole list.</b> Every sent row comes back
     * unanswered carrying the manager's reason: {@link #releaseRow} withdraws
     * the claim with the verdict, which is unchanged — what changed is that it
     * happens to all of them at once rather than to the ones picked out. Rows
     * already {@code VERIFIED} in an earlier round stay shut.
     *
     * <p><b>An acceptance still does not close the task.</b> It sets every row
     * {@code VERIFIED} and hands the task back; {@link #closeReview} remains
     * the deliberate press that ends it, for the reason that method gives at
     * length — closing on the verdict would take the control away in the same
     * instant it was used.
     *
     * @return the step, whose status {@link #settleAfterRowMove} has moved
     * @throws JourneyStepNotFoundException      no such step, or no onboarding role
     * @throws NotAnOnboardingModeratorException not this project's manager, nor an admin
     * @throws InvalidStepTransitionException    the task is not out for review
     * @throws RejectReasonRequiredException     rejected with an empty remark
     * @throws CompletionGateException           nothing is out to give a verdict on
     */
    @Transactional
    public ObJourneyStep recordChecklistVerdict(long stepId, long callerId, String moduleRole,
            ObStepReviewState verdict, String remark) {
        ObJourneyStep step = requireStep(stepId);
        requireReviewer(step, callerId, moduleRole);

        if (step.getStatus() != ObJourneyStepStatus.PENDING_REVIEW) {
            throw new InvalidStepTransitionException(step.getId(), "review", step.getStatus());
        }
        if (!verdict.isDecided()) {
            // NOT_REVIEWED was how a per-row verdict was taken back. There is
            // no row to take it back on any more, and a press that means
            // nothing is not one this route accepts.
            throw new InvalidStepTransitionException(step.getId(), "review", step.getStatus());
        }

        String trimmed = remark == null || remark.isBlank() ? null : remark.trim();
        if (verdict == ObStepReviewState.REJECTED && trimmed == null) {
            throw new RejectReasonRequiredException(step.getId());
        }

        List<ObJourneyStepItem> out = stepItems.findByStepIdOrderBySequenceAsc(stepId).stream()
                .filter(item -> item.getRowState() == ObStepRowState.SENT)
                .toList();
        if (out.isEmpty()) {
            throw new CompletionGateException(stepId, List.of(), 0, false);
        }

        ObJourney journey = requireJourney(step);
        Instant now = Instant.now();
        for (ObJourneyStepItem item : out) {
            item.setReviewState(verdict);
            item.setReviewedBy(callerId);
            item.setReviewedAt(now);
            /*
              The reason goes on every row it is about, which on a rejection is
              all of them — the implementor opens any one and finds what the
              manager said. A verdict of Verified never touches the
              implementor's own note, exactly as reviewItem has it.
            */
            if (verdict == ObStepReviewState.REJECTED) {
                item.setRemark(trimmed);
            }
            releaseRow(item, now, callerId);
        }
        if (verdict == ObStepReviewState.REJECTED) {
            step.setReviewRound(step.getReviewRound() + 1);
        }
        settleAfterRowMove(journey, step, now, callerId);
        return step;
    }

    /**
     * Mark this task's outcomes as looked at.
     *
     * <h2>What makes a signal a signal</h2>
     *
     * <p>"2 rows came back" has to stop saying so once they have been read, or
     * it is not a notification but a permanent label — and it has to survive a
     * refresh, or it is not a notification but a flicker. Both need the same
     * one thing: a stamp on the row saying when its implementor last opened
     * the outcome. That is {@code outcome_seen_at}, and this is the only
     * writer.
     *
     * <p>Idempotent, and deliberately not a transition: calling it on a task
     * with nothing new writes nothing and answers zero. The client calls it
     * whenever the task is opened rather than working out whether it needs to.
     *
     * <p><b>The owner's, not the reader's.</b> Seen-ness is about the person
     * the outcome is addressed to, so a manager or an admin opening the task
     * does not quietly mark it read for the implementor who has not.
     *
     * @return how many rows this press cleared
     */
    @Transactional
    public int markOutcomesSeen(long stepId, long callerId) {
        ObJourneyStep step = requireStep(stepId);
        requireOwnership(step, callerId);

        Instant now = Instant.now();
        int cleared = 0;
        for (ObJourneyStepItem item : stepItems.findByStepIdOrderBySequenceAsc(stepId)) {
            if (item.isUnseenOutcome()) {
                item.setOutcomeSeenAt(now);
                cleared++;
            }
        }
        return cleared;
    }

    /** Who the task goes back to, for a history row that is read by people. */
    private static String ownerLabel(ObJourneyStep step) {
        return step.getOwnerUserId() == null ? "its implementor" : "user " + step.getOwnerUserId();
    }

    /**
     * What the history row says a rejection was about.
     *
     * <p>The count leads and the labels follow, so a reader scanning the
     * chain sees how much came back before they read what. Not truncated:
     * {@code ob_step_history.remarks} is {@code TEXT}, and a task carrying
     * twenty rejected rows is exactly the event worth recording in full.
     */
    private static String rejectionRemarks(List<String> labels) {
        return labels.size() + (labels.size() == 1 ? " row rejected: " : " rows rejected: ")
                + String.join("; ", labels);
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
     * not checked here or anywhere else any more — PLAN.md §4, D-17 drops
     * it, and this gate has always counted a False as answered regardless.
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
        evaluateCompletionGate(step).ifPresent(failure -> {
            throw failure;
        });
    }

    /**
     * The gate itself, separated from the throwing so that B-115's client
     * acceptance can read the same verdict without catching an exception it
     * expects.
     *
     * <p>Split rather than duplicated for the reason the contract gives for
     * routing acceptance through this gate at all: PHASE-2-BUILD-PLAN §3 #4
     * found the prototype enforcing three gates on {@code stComplete} and
     * none on {@code signoffAccept}, so a client could accept a service whose
     * required documents were never attached. A second evaluation written
     * beside this one would be the same bug with a longer fuse — it would
     * agree today and drift on whichever change touches only one of them.
     */
    private java.util.Optional<CompletionGateException> evaluateCompletionGate(ObJourneyStep step) {
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
            return java.util.Optional.of(
                    new CompletionGateException(step.getId(), unanswered, missingDocs, signoffMissing));
        }
        return java.util.Optional.empty();
    }

    /**
     * B-115 · complete a step because the client accepted its sign-off, or
     * report why we could not.
     *
     * <h2>Why this is not {@link #complete}</h2>
     *
     * <p>Two things differ, and neither is the gate. There is <b>no caller</b>
     * — the actor is a customer holding a one-time session, who is not a user,
     * owns nothing and could never satisfy {@link #requireOwnership}. And the
     * gate <b>must not throw</b>: the contract is explicit that acceptance
     * succeeding while completion fails is "a normal outcome, not an error",
     * because "the client did accept, they are not the ones who left a document
     * unattached, and asking them to click twice for our own incomplete record
     * is the version of this that loses a signature".
     *
     * <p>Everything else is deliberately identical, and identical by sharing
     * rather than by resemblance: the same {@link #evaluateCompletionGate}, the
     * same {@code DONE} and {@code finishedAt}, and the same
     * {@link #activateEligibleSteps} sweep — a step completed by acceptance
     * unblocks its siblings exactly as one completed by its owner does, and a
     * journey that stalled because the last completion came through the public
     * surface would be the hardest kind of bug to see.
     *
     * <h2>The ownership check is skipped, not weakened</h2>
     *
     * <p>What stands in its place is upstream and stronger for this actor: the
     * caller proved possession of a mailed link <em>and</em> an OTP sent to the
     * contact on the row, and {@code ObSignoffAcceptService} passes only the
     * {@code step_id} of the sign-off that session was minted for. There is no
     * argument here a caller can choose.
     *
     * @return empty when the step completed; otherwise the contract's stable
     *         {@code gateFailures} codes, in a fixed order so two clients
     *         refused for the same reasons get the same array
     */
    @Transactional
    public List<String> completeOnClientAcceptance(long stepId) {
        ObJourneyStep step = requireStep(stepId);

        // Not IN_PROGRESS is its own answer rather than an exception. A step
        // already DONE has nothing to complete and the acceptance still stands;
        // one BLOCKED or WAITING_ON_CLIENT is our own state to clear, which is
        // exactly what gateFailures is for — telling the owner what is missing
        // while the client sees "we are finishing our side".
        if (step.getStatus() != ObJourneyStepStatus.IN_PROGRESS) {
            return step.getStatus() == ObJourneyStepStatus.DONE
                    ? List.of()
                    : List.of(GATE_STEP_NOT_IN_PROGRESS);
        }

        java.util.Optional<CompletionGateException> failure = evaluateCompletionGate(step);
        if (failure.isPresent()) {
            return gateFailureCodes(failure.get());
        }

        step.setStatus(ObJourneyStepStatus.DONE);
        step.setFinishedAt(Instant.now());
        activateEligibleSteps(step.getJourneyId());
        settleJourney(step.getJourneyId());
        return List.of();
    }

    /**
     * B-117 · revert a step because the client objected instead of signing.
     *
     * <h2>The two states this actually reverts</h2>
     *
     * <p>{@code IN_PROGRESS} and {@code WAITING_ON_CLIENT} — the two states a
     * step can be in while a sign-off it does not yet hold is still
     * {@code PENDING}. The clock resume only applies to the second: {@code
     * WAITING_ON_CLIENT} is C-105's own pause, so returning from it is the
     * plan's own "our clock resumes" — see {@link #resume}, whose {@code
     * RESUMED} write this reuses rather than re-implements. A step still
     * plainly {@code IN_PROGRESS} (a sign-off requested without pausing it)
     * needs no clock event: nothing here paused it.
     *
     * <p><b>{@code DONE}, {@code SKIPPED}, {@code PENDING} and {@code BLOCKED}
     * are left alone</b> — on {@link #completeOnClientAcceptance}'s own
     * precedent for the identical shape of caller: there is no user here to
     * refuse with an exception, and unwinding a step that already completed
     * (and may already have activated siblings and settled the journey) is a
     * cascade this task's own line does not ask for. The objection is still
     * journalled either way; a step in one of these states is the owner's own
     * state to reconcile, not this method's to force.
     *
     * <h2>The objection is logged unconditionally</h2>
     *
     * <p>{@link #appendObjectedHistory} runs whether or not the step actually
     * reverted, on {@link #skip}'s own precedent for what "logged to the
     * communication timeline" means one class over — an {@code OBJECTED} row
     * naming the previous status, the (possibly unchanged) new one, and the
     * client contact who raised it.
     *
     * @param objectingContactId {@code ob_signoffs.sent_to_contact_id} — the
     *                           contact the session was minted for, recorded
     *                           as {@code actor_contact_id} rather than a
     *                           staff {@code actor_id}
     * @param objectionNote      the mandatory reason, written to the
     *                           timeline's {@code remarks}
     * @return whether the step actually reverted, and its owner — B-117's
     *         caller uses the owner to route {@code SIGNOFF_OBJECTED}
     * @throws JourneyStepNotFoundException no such step
     */
    @Transactional
    public ObjectionResult revertOnClientObjection(long stepId, Long objectingContactId, String objectionNote) {
        ObJourneyStep step = requireStep(stepId);
        ObJourney journey = journeys.findById(step.getJourneyId())
                .orElseThrow(() -> new IllegalStateException(
                        "journey step " + stepId + " points at journey " + step.getJourneyId() + " which does not exist"));

        ObJourneyStepStatus previousStatus = step.getStatus();
        boolean reverted = previousStatus == ObJourneyStepStatus.IN_PROGRESS
                || previousStatus == ObJourneyStepStatus.WAITING_ON_CLIENT;

        if (reverted) {
            step.setStatus(ObJourneyStepStatus.IN_PROGRESS);

            if (previousStatus == ObJourneyStepStatus.WAITING_ON_CLIENT) {
                Instant resumedAt = Instant.now();
                recomputeDueAtOnResume(step, resumedAt);

                ObStepClockEvent resumed = new ObStepClockEvent();
                resumed.setStepId(step.getId());
                resumed.setJourneyId(step.getJourneyId());
                resumed.setEventType(ObStepClockEventType.RESUMED);
                resumed.setAttributedTo(ObStepClockAttribution.INTERNAL);
                resumed.setOccurredAt(resumedAt);
                resumed.setActorType(ObStepClockActorType.SYSTEM);
                clockRecorder.record(resumed);
            }
        }

        appendObjectedHistory(journey, step, previousStatus, objectingContactId, objectionNote);
        return new ObjectionResult(reverted, step.getOwnerUserId());
    }

    /** {@link #revertOnClientObjection}'s own answer — B-117's own record type. */
    public record ObjectionResult(boolean stepReverted, Long ownerUserId) {
    }

    /**
     * The gate's verdict as the contract's codes.
     *
     * <p>Codes rather than sentences, and the contract says why: "OB-09 shows
     * the client 'your acceptance is recorded; we are finishing our side' while
     * the same codes tell the owner exactly what to attach. One field, two
     * audiences, and only the internal one needs the detail." So the item
     * labels {@link CompletionGateException} carries are deliberately not
     * forwarded — they are our internal checklist wording, on an
     * unauthenticated response.
     *
     * <p>{@code signoffMissing} cannot fire on this path: the sign-off was set
     * {@code SIGNED} in the same transaction immediately before. It is mapped
     * anyway rather than assumed away, because an assumption that holds by
     * ordering is one refactor from being wrong silently.
     */
    private static List<String> gateFailureCodes(CompletionGateException failure) {
        List<String> codes = new ArrayList<>(3);
        if (!failure.unansweredMandatoryItems().isEmpty()) {
            codes.add(GATE_ITEMS_UNANSWERED);
        }
        if (failure.missingRequiredDocs() > 0) {
            codes.add(GATE_DOCS_MISSING);
        }
        if (failure.signoffMissing()) {
            codes.add(GATE_SIGNOFF_MISSING);
        }
        return List.copyOf(codes);
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

        ObJourney journey = requireJourney(step);
        step.setStatus(ObJourneyStepStatus.BLOCKED);
        step.setBlockedReasonCode(reasonCode);
        step.setBlockedNote(note);
        // `resume()` clears blockedReasonCode/blockedNote off the step row the
        // moment it reopens, which is correct for "what is blocking it right
        // now" but erases "what blocked it and why" the instant the answer
        // stops mattering to the ribbon. This row is where that survives —
        // fieldName carries reasonCode (BLOCKED is the one event type here
        // that has one; every other lifecycle event uses fieldName="status",
        // where it says nothing beyond what fromStatus/toStatus already do).
        appendStatusHistory(journey, step, "BLOCKED",
                ObJourneyStepStatus.IN_PROGRESS, ObJourneyStepStatus.BLOCKED, callerId, reasonCode, note);
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

        ObJourney journey = requireJourney(step);
        step.setStatus(ObJourneyStepStatus.WAITING_ON_CLIENT);
        appendStatusHistory(journey, step, "WAITING_ON_CLIENT",
                ObJourneyStepStatus.IN_PROGRESS, ObJourneyStepStatus.WAITING_ON_CLIENT, callerId, "status", null);

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

        ObJourney journey = requireJourney(step);
        step.setStatus(ObJourneyStepStatus.IN_PROGRESS);
        step.setBlockedReasonCode(null);
        step.setBlockedNote(null);
        appendStatusHistory(journey, step, "RESUMED",
                previousStatus, ObJourneyStepStatus.IN_PROGRESS, callerId, "status", null);

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
        settleJourney(step.getJourneyId());
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

    /** C-121: the same row-scope check used by the task action routes. */
    public ObJourneyStep requireAttachmentAccess(long stepId, long callerId) {
        ObJourneyStep step = requireStep(stepId);
        requireOwnership(step, callerId);
        return step;
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
     * C-111 · answer one Task List entry — OB-06's True / False / remark.
     *
     * <h2>Three states, because the question has three answers</h2>
     *
     * <p>{@code answer} is {@code true}, {@code false}, or {@code null} for not
     * yet answered. A task list entry is a question — <em>was the source data
     * received?</em> — and "no, the client has not sent it" is an answer rather
     * than the absence of one.
     *
     * <p><b>This route used to carry a single boolean</b>, and a standing 🔴
     * note here said so: two states cannot express three, so False-with-a-reason
     * was unreachable and {@code isDone: false} had to mean "return it to
     * unanswered". The column has been a nullable {@code Boolean} beside a
     * {@code remark} since A-118 wrote it; only the request was binary. It is
     * now {@code {answer, remark}} and the note is gone rather than reworded.
     *
     * <p><b>{@code isDone} still means "answered", not "answered yes"</b>, and
     * that is the reading under which this and {@link #requireCompletionGate}
     * agree — the gate filters on {@code getAnswer() == null}, so an item
     * answered False satisfies it exactly as a True does. §5.8 asks whether the
     * owner has addressed the item, not whether the answer was favourable.
     *
     * <p>A remark is kept only while the answer it explains stands: clearing to
     * unanswered clears it too, because a reason for a decision no longer
     * recorded is a sentence about nothing.
     *
     * <p><b>The remark is optional on both answers</b> — PLAN.md §4, D-17, a
     * recorded deviation from §5.8's "False requires a remark". This method
     * used to refuse a False with an empty remark ahead of
     * {@code ck_ob_journey_step_items_remark}; the constraint is dropped
     * (V20260916_1520) and so is the refusal. An implementor records a reason
     * where there is one to record.
     *
     * <h2>Three refusals the review gate adds</h2>
     *
     * <p><b>A step under review is shut.</b> An answer that changed while a
     * manager was reading it would make their verdict describe something they
     * never saw — so {@code PENDING_REVIEW} refuses every write, and says so
     * with {@link StepUnderReviewException} rather than borrowing the
     * terminal one, which would tell an implementor their open task was
     * closed.
     *
     * <p><b>A verified row is shut for good.</b> {@link
     * StepItemAlreadyVerifiedException} — this is what makes "only the
     * rejected rows reopen" a fact about the data rather than two disabled
     * controls.
     *
     * <p><b>A rejected row keeps its reason.</b> The rejection rides on this
     * same {@code remark} (see {@code V20260916_1700}'s header), so blanking
     * it while the row is still {@code REJECTED} would leave a rejection with
     * nothing on it — refused here with {@link RejectReasonRequiredException}
     * so the caller gets a 422 naming the field rather than the 500 that
     * {@code ck_ob_journey_step_items_reject_reason} would otherwise produce.
     * The implementor may freely <em>replace</em> the text; they may not
     * empty it.
     *
     * @throws JourneyStepItemNotFoundException no such item
     * @throws NotStepOwnerException            caller is neither owner nor backup owner of its step
     * @throws StepAlreadyTerminalException     the step is {@code DONE} or {@code SKIPPED}
     * @throws StepUnderReviewException         the step is {@code PENDING_REVIEW}
     * @throws StepItemAlreadyVerifiedException an OB Manager has verified this row
     * @throws RejectReasonRequiredException    the row is rejected and the remark would be blanked
     */
    @Transactional
    public ObJourneyStepItem answerItem(long itemId, long callerId, Boolean answer, String remark) {
        ObJourneyStepItem item = stepItems.findById(itemId)
                .orElseThrow(() -> new JourneyStepItemNotFoundException(itemId));
        ObJourneyStep step = requireStep(item.getStepId());
        requireOwnership(step, callerId);

        // A closed step's checklist is the record of how it closed. Editing
        // it afterwards would change what the completion gate was satisfied
        // by, retroactively — the same reasoning every other transition
        // applies to a terminal step.
        if (step.getStatus().isTerminal()) {
            throw new StepAlreadyTerminalException(step.getId(), step.getStatus());
        }
        // The gate is the ROW's, not the task's — V20260917_1210. A row out
        // with the manager is frozen so their verdict describes what they
        // actually saw; its neighbours are untouched, which is what lets
        // somebody carry on with rows three to five while one and two are
        // being read. The task-level refusal this replaces froze all five.
        if (item.getRowState() == ObStepRowState.VERIFIED) {
            throw new StepItemAlreadyVerifiedException(itemId);
        }
        if (item.getRowState() == ObStepRowState.SENT) {
            throw new StepUnderReviewException(step.getId(), itemId);
        }

        // Blank and absent are the same thing: a remark box somebody cleared
        // holds no reason, and storing "" would make `remark IS NOT NULL` a
        // question about whitespace.
        String trimmed = remark == null || remark.isBlank() ? null : remark.trim();

        boolean carriesRejection = item.getReviewState() == ObStepReviewState.REJECTED;
        if (carriesRejection && trimmed == null) {
            throw new RejectReasonRequiredException(itemId);
        }

        item.setAnswer(answer);
        item.setAnsweredBy(answer == null ? null : callerId);
        item.setAnsweredAt(answer == null ? null : Instant.now());
        // Clearing an answer normally clears the remark with it — a reason for
        // a decision no longer recorded is a sentence about nothing. A rejected
        // row is the exception: the remark there is the manager's, not the
        // answer's, and it outlives the answer being taken back.
        item.setRemark(answer == null && !carriesRejection ? null : trimmed);
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
        if (!ObStepOwnership.mayAct(callerId, step) && !isInheritingImplementor(step, callerId)) {
            throw new NotStepOwnerException(step.getId(), step.getOwnerUserId(), step.getBackupOwnerUserId());
        }
    }

    /**
     * Whether {@code callerId} is the implementor of the project this step's
     * journey belongs to, on a step that names nobody at all.
     *
     * <h2>The same fallback the read applies, applied to authorisation</h2>
     *
     * <p>{@code ObJourneyReadService#inheritedOwner} resolves a task with
     * neither owner nor backup onto the project's implementor, and OB-06 draws
     * it as theirs. This is the half that makes that true rather than
     * decorative: without it the page would offer Complete on a task the five
     * transitions then refuse, which is a worse answer than the unassigned
     * state it replaced.
     *
     * <p><b>Both nulls are required, not just the owner.</b> A step with a
     * backup owner already has somebody to inherit to — see
     * {@link ObBackupOwnerResolver} — and widening this to cover it would let a
     * project's implementor act on work a template deliberately routed
     * elsewhere.
     *
     * <p>Read through {@link ObJourneyRepository} rather than a scoped one, on
     * this class's own {@code @UnscopedAccess} reasoning: the journey being
     * read is this step's own parent, and it discloses nothing except who the
     * caller would have to be. A project row that has gone missing, or one with
     * no implementor, answers false — the step stays unassigned and the
     * transition is refused, which is the behaviour before this method existed.
     */
    private boolean isInheritingImplementor(ObJourneyStep step, long callerId) {
        if (step.getOwnerUserId() != null || step.getBackupOwnerUserId() != null) {
            return false;
        }
        return journeys.findById(step.getJourneyId())
                .map(ObJourney::getProjectId)
                .flatMap(projects::findById)
                .map(ObProject::getImplementorUserId)
                .filter(implementor -> implementor.longValue() == callerId)
                .isPresent();
    }

    private void requireStatus(ObJourneyStep step, String action, ObJourneyStepStatus required) {
        if (step.getStatus() != required) {
            throw new InvalidStepTransitionException(step.getId(), action, step.getStatus());
        }
    }

    /** The step's own journey — {@link #skip}'s own inline lookup, named once for the four other transitions that now also need it to write a history row. */
    private ObJourney requireJourney(ObJourneyStep step) {
        return journeys.findById(step.getJourneyId())
                .orElseThrow(() -> new IllegalStateException(
                        "journey step " + step.getId() + " points at journey " + step.getJourneyId() + " which does not exist"));
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
     * <p>A no-op while the journey is held behind a sibling: {@link #skip}
     * does not itself require the journey be unheld (its own javadoc), so
     * this re-checks fresh rather than trusting the caller's own state —
     * the same defence-in-depth {@link #start}'s own hold check already
     * applies to a manual transition.
     *
     * <p><b>It no longer re-asserts the prerequisite gate.</b> Since
     * {@link #start} admits a {@code LOCKED} journey, a step in one can be
     * running, and the step after it has to follow when that one completes
     * — a chain that activated its second step only once the checklist
     * cleared would be a worse answer than refusing the first one outright.
     * The two callers that <em>do</em> want the gate checked still check it
     * themselves and are unaffected: {@code ObJourneyInstantiationService}
     * kicks the first wave only for a journey born {@code OPEN}, and
     * {@link ObPrerequisiteGateService} calls this precisely because the
     * gate has just opened.
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
        if (journey.getHeldByJourneyId() != null) {
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
     * C-123 · the journey completes when <b>all</b> steps have landed —
     * {@code DONE} or {@code SKIPPED}, "parallel branches must all land, not
     * just the longest chain" (plan §5 item 6) — and its completion is what
     * releases every journey held behind it (§5 item 5). Called after
     * {@link #activateEligibleSteps} from the two transitions that can settle
     * a step, so a journey whose last step was skipped completes exactly as
     * one whose last step was done.
     *
     * <p>Idempotent on {@code completed_at}: a second settle after completion
     * (nothing can transition a settled step, but defence in depth is cheap
     * here) neither re-stamps the journey nor releases anything twice —
     * {@link ObJourneyDependencyRelease#release} clears the hold, so the
     * second call finds no held journeys.
     */
    private void settleJourney(long journeyId) {
        ObJourney journey = journeys.findById(journeyId)
                .orElseThrow(() -> new IllegalStateException("journey " + journeyId + " does not exist"));
        if (journey.getCompletedAt() != null) {
            return;
        }
        boolean allLanded = journeySteps.findByJourneyIdOrderBySequenceAsc(journeyId).stream()
                .allMatch(s -> s.getStatus() == ObJourneyStepStatus.DONE
                        || s.getStatus() == ObJourneyStepStatus.SKIPPED);
        if (!allLanded) {
            return;
        }
        journey.setCompletedAt(Instant.now());

        for (long released : dependencyRelease.release(journeyId)) {
            // The hold is gone, and with the prerequisite gate advisory there
            // is nothing left to wait for: a released journey activates its
            // first wave here whether or not its client's checklist has
            // cleared, exactly like any other.
            activateEligibleSteps(released);
            dependencyRelease.notifyUnblocked(released, journeyId);
        }

        completeProjectIfLastJourney(journey);
    }

    /**
     * The project's own earned transition, stamped where the journey's is.
     *
     * <p>{@code ObProject.complete()} is documented as what happens "when the
     * project's last journey completes", and {@code ObProjectWriteService}
     * refuses a hand-set {@code COMPLETED} on exactly that ground — but
     * nothing called it, so {@code ob_projects.status} stayed {@code RUNNING}
     * through a project whose every task was done. The Projects grid and the
     * project header both read that status, and both said so.
     *
     * <p>After the release loop rather than before it: a journey this one
     * unblocks is work the project is still waiting on, and it is counted by
     * the query below because its {@code completed_at} is null.
     *
     * <p>Only from {@code RUNNING}. A project somebody put {@code ON_HOLD} or
     * {@code DROPPED} has a status that records a decision rather than
     * progress, and a late-landing journey must not overwrite it.
     */
    private void completeProjectIfLastJourney(ObJourney journey) {
        Long projectId = journey.getProjectId();
        if (projectId == null) {
            return;
        }
        boolean stillRunning = journeys.existsByProjectIdAndArchivedAtIsNullAndCompletedAtIsNullAndIdNot(
                projectId, journey.getId());
        if (stillRunning) {
            return;
        }
        projects.findById(projectId)
                .filter(project -> project.getStatus() == ObProjectStatus.RUNNING)
                .ifPresent(ObProject::complete);
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
    /**
     * Who may record a verdict on this step — <b>the project's own manager</b>.
     *
     * <h2>Not a role, and that is the point</h2>
     *
     * <p>This was {@link #requireModerator} at first, on {@link #skip}'s
     * precedent, which let anybody holding {@code OB_MANAGER} review every
     * submitted task in the module. {@code ob_projects} carries
     * {@code implementor_manager_user_id} — "who is accountable for this
     * engagement above the implementor" — and that is a narrower and truer
     * answer to "whose judgement is this". A manager reviews the engagements
     * they own and is not shown anybody else's.
     *
     * <p><b>{@code OB_ADMIN} still passes.</b> A project whose manager has
     * left, is on leave, or was never set would otherwise have no way to close
     * a review at all, and the work would sit in {@code PENDING_REVIEW} for
     * ever with no route out. An admin is the unsticking path. {@code
     * OB_MANAGER} on its own is <em>not</em> enough any more — holding the role
     * says you manage something, not that you manage this.
     *
     * <p>Refused with {@link NotAnOnboardingModeratorException} — a 403, the
     * same shape {@link #skip} answers with. A caller holding no onboarding
     * role at all still gets {@link JourneyStepNotFoundException} from
     * {@link #requireModuleStanding}, so the route discloses nothing about
     * which item ids exist.
     */
    private void requireReviewer(ObJourneyStep step, long callerId, String moduleRole) {
        requireModuleStanding(step.getId(), moduleRole);
        if (OB_ADMIN_ROLE.equals(moduleRole)) {
            return;
        }
        boolean managesThisProject = journeys.findById(step.getJourneyId())
                .map(ObJourney::getProjectId)
                .flatMap(projects::findById)
                .map(ObProject::getImplementorManagerUserId)
                .filter(manager -> manager.longValue() == callerId)
                .isPresent();
        if (!managesThisProject) {
            throw new NotAnOnboardingModeratorException(moduleRole);
        }
    }

    /**
     * The half {@link #requireReviewer} shares with {@link #requireModerator}:
     * a caller with no onboarding standing at all is answered 404, not 403,
     * so neither route confirms that an id exists to somebody outside the
     * module.
     */
    private void requireModuleStanding(long stepId, String moduleRole) {
        if (moduleRole == null || moduleRole.isBlank()) {
            throw new JourneyStepNotFoundException(stepId);
        }
    }

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
    /**
     * The five plain status transitions — start, block, waiting-on-client,
     * resume, complete — one {@code ob_step_history} row each, on {@link
     * #appendSkippedHistory}'s exact shape. {@code skip} and {@code
     * revertOnClientObjection} keep their own named methods below: a skip
     * carries a moderator override {@link #requireModerator} already checked
     * and an objection carries a client actor neither of these five ever do,
     * so folding either in here would blur what this method can assume about
     * its caller.
     */
    private void appendStatusHistory(ObJourney journey, ObJourneyStep step, String eventType,
                                      ObJourneyStepStatus previousStatus, ObJourneyStepStatus newStatus,
                                      long actorId, String fieldName, String remarks) {
        ObStepHistory entry = new ObStepHistory();
        entry.setJourneyId(journey.getId());
        entry.setStepId(step.getId());
        entry.setObClientId(journey.getObClientId());
        entry.setEventType(eventType);
        entry.setFieldName(fieldName);
        entry.setOldValue(previousStatus == null ? null : previousStatus.name());
        entry.setNewValue(newStatus.name());
        entry.setActorId(actorId);
        entry.setActorType("USER");
        entry.setRemarks(remarks);
        stepJournal.append(entry);
    }

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
     * B-117 · one {@code OBJECTED} row per objection, on {@link
     * #appendSkippedHistory}'s exact shape one method up — {@code actorType}
     * is {@code CLIENT} rather than {@code USER} and {@code actorContactId}
     * carries the contact rather than {@code actorId}, which is the row's own
     * way of saying nobody on our staff made this decision.
     */
    private void appendObjectedHistory(ObJourney journey, ObJourneyStep step, ObJourneyStepStatus previousStatus,
                                        Long objectingContactId, String objectionNote) {
        ObStepHistory entry = new ObStepHistory();
        entry.setJourneyId(journey.getId());
        entry.setStepId(step.getId());
        entry.setObClientId(journey.getObClientId());
        entry.setEventType("OBJECTED");
        entry.setFieldName("status");
        entry.setOldValue(previousStatus.name());
        entry.setNewValue(step.getStatus().name());
        entry.setActorType("CLIENT");
        entry.setActorContactId(objectingContactId);
        entry.setRemarks(objectionNote);
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
