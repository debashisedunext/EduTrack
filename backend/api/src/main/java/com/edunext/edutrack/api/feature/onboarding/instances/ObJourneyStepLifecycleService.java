package com.edunext.edutrack.api.feature.onboarding.instances;

import com.edunext.edutrack.domain.journal.ObStepJournal;
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
import com.edunext.edutrack.domain.onboarding.ObStepHistory;
import org.openapitools.jackson.nullable.JsonNullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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
 */
@Service
@UnscopedAccess("""
        A-112's guard, C-104's transitions: this class reads ObJourneyRepository         once, and not as a caller-scoped read. requireOwnership has already         refused anyone who is neither the step's owner nor its backup, and         OnboardingScopeResolver grants OB_STEP_OWNER exactly the journeys         containing their steps — owner or backup, deliberately — so a caller         who reaches the findById below is provably in scope for that journey         already. The read is of the step's own parent, for gate_status and         held_by_journey_id, and it can disclose nothing the caller did not         just prove they may act on.

        Routing it through ScopedJourneys would also be worse than redundant         here: this method takes a callerId, not an Authentication, so it would         need a second principal shape threaded through five transitions to         re-answer a question requireOwnership has already answered — and a         scope miss would surface as the IllegalStateException below, a 500,         where the whole point of the guard is a 404.

        C-107's own skip() reads the same repository the same way, for a         sixth transition — a plain findById for obClientId, to put on the         ob_step_history row it builds. The FOR UPDATE lock that append needs         is ObStepJournal's own, taken inside domain/journal/ where         ScopeGuardRulesTest does not reach; this class never calls         findByIdForUpdate itself.""")
public class ObJourneyStepLifecycleService {

    /** Plan §3's "override steps with logged reason" — {@link #skip}'s own capability. */
    private static final Set<String> MODERATOR_ROLES = Set.of("OB_MANAGER", "OB_ADMIN");

    private final ObJourneyStepRepository journeySteps;
    private final ObJourneyRepository journeys;
    private final ObJourneyStepItemRepository stepItems;
    private final ObJourneyTemplateStepItemRepository templateStepItems;
    private final ObJourneyTemplateStepDocRepository templateStepDocs;
    private final ObAttachmentRepository attachments;
    private final ObSignoffRepository signoffs;
    private final ObStepJournal stepJournal;

    public ObJourneyStepLifecycleService(ObJourneyStepRepository journeySteps, ObJourneyRepository journeys,
            ObJourneyStepItemRepository stepItems, ObJourneyTemplateStepItemRepository templateStepItems,
            ObJourneyTemplateStepDocRepository templateStepDocs, ObAttachmentRepository attachments,
            ObSignoffRepository signoffs, ObStepJournal stepJournal) {
        this.journeySteps = journeySteps;
        this.journeys = journeys;
        this.stepItems = stepItems;
        this.templateStepItems = templateStepItems;
        this.templateStepDocs = templateStepDocs;
        this.attachments = attachments;
        this.signoffs = signoffs;
        this.stepJournal = stepJournal;
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
