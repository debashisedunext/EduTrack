package com.edunext.edutrack.api.feature.onboarding.instances;

import com.edunext.edutrack.domain.onboarding.ObJourneyStep;
import com.edunext.edutrack.domain.onboarding.ObJourneyStepRagService;
import com.edunext.edutrack.domain.onboarding.ObJourneyStepStatus;
import com.edunext.edutrack.domain.onboarding.ObRag;
import com.edunext.edutrack.domain.onboarding.ObStepReviewState;
import com.edunext.edutrack.domain.onboarding.ObStepRowState;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.openapitools.jackson.nullable.JsonNullable;

import java.time.Instant;
import java.util.List;

/**
 * C-104 · the wire shapes for {@code /onboarding/journey-steps}'s five
 * lifecycle actions, per {@code contracts/openapi.yaml}'s
 * {@code onboarding-journeys} tag.
 *
 * <p>Record types throughout, on {@code ObJourneyTemplateDtos}'s own
 * convention — package-private, since nothing outside this controller layer
 * constructs them. {@code ObJourneyStepResponse}/{@code JourneyStepDetail}
 * rather than the shorter {@code StepResponse}/{@code Step} deliberately:
 * {@code ObJourneyTemplateDtos} already has a {@code StepResponse} for the
 * template's own step, and springdoc names OpenAPI schema components from a
 * Java class's simple name — the same collision {@code
 * ObJourneyTemplateResponse} was renamed to dodge (see that record's own
 * comment), caught the same way by {@code ContractConformanceTest}.
 */
final class ObJourneyStepLifecycleDtos {

    private ObJourneyStepLifecycleDtos() {
    }

    // ── requests ──────────────────────────────────────────────────────

    record BlockStepRequest(
            @NotBlank @Size(max = 40) String reasonCode,
            @Size(max = 500) String note) {
    }

    /** C-107 · {@code ObStepSkipRequest} — {@code reason} is mandatory, plan §3/§4. */
    record ObStepSkipRequest(
            @NotBlank @Size(min = 3, max = 500) String reason) {
    }

    /**
     * C-108 · {@code ObJourneyStepUpdateRequest} — every field optional,
     * absent meaning "leave unchanged" (the contract's own line). {@code
     * ownerUserId}/{@code backupOwnerUserId}/{@code dueAt} are also
     * explicitly clearable: the schema types them nullable, and the mock
     * (built ahead, per {@code onboardingSteps.ts}'s own `!== undefined`
     * checks) already treats a literal {@code null} as "clear this field"
     * rather than folding it into "unchanged" — so the real backend has to
     * draw the same distinction, which no plain Java type can without help.
     * {@link JsonNullable} is that help; {@code tatDays} stays a plain
     * {@code Integer} since the schema never allows it to be {@code null}.
     */
    record ObJourneyStepUpdateRequest(
            JsonNullable<Long> ownerUserId, JsonNullable<Long> backupOwnerUserId,
            @Min(0) Integer tatDays, JsonNullable<Instant> dueAt) {
    }

    // ── responses ─────────────────────────────────────────────────────

    record JourneyStepDetail(
            Long id, Long journeyId, int sequence, String name, ObJourneyStepStatus status,
            Long ownerUserId, Long backupOwnerUserId,
            String blockedReasonCode, String blockedNote,
            Instant startedAt, Instant finishedAt, Instant dueAt) {

        static JourneyStepDetail of(ObJourneyStep s) {
            return new JourneyStepDetail(s.getId(), s.getJourneyId(), s.getSequence(), s.getName(), s.getStatus(),
                    s.getOwnerUserId(), s.getBackupOwnerUserId(),
                    s.getBlockedReasonCode(), s.getBlockedNote(),
                    s.getStartedAt(), s.getFinishedAt(), s.getDueAt());
        }
    }

    record ObJourneyStepResponse(JourneyStepDetail data) {
        static ObJourneyStepResponse of(ObJourneyStep s) {
            return new ObJourneyStepResponse(JourneyStepDetail.of(s));
        }
    }

    /**
     * C-107 · {@code ObStepClockState} — what the TAT clock is doing, per the
     * contract's own description: derived from {@code ob_step_clock_events}
     * and never stored. {@link #of} derives it from {@code status} instead,
     * which is sufficient today because C-105 (the clock-event rows
     * themselves) has not landed — the mapping is exact regardless, since
     * {@code PAUSED} means and only means {@code WAITING_ON_CLIENT} and
     * nothing else changes what the clock is doing.
     */
    enum ObStepClockState {
        RUNNING, PAUSED, STOPPED;

        static ObStepClockState of(ObJourneyStepStatus status) {
            return switch (status) {
                /*
                  Both pause the TAT clock, and PENDING_REVIEW pauses it with
                  the same PAUSED event WAITING_ON_CLIENT writes — see
                  ObJourneyStepStatus.PENDING_REVIEW. The two are one case here
                  because a client and a manager are the same thing to a clock:
                  somebody who is not the implementor, holding the work.
                */
                case WAITING_ON_CLIENT, PENDING_REVIEW -> PAUSED;
                case DONE, SKIPPED -> STOPPED;
                case PENDING, IN_PROGRESS, BLOCKED -> RUNNING;
            };
        }
    }

    /** C-107 · local, on this file's own {@code UserRef} convention — see {@link ObJourneyStepDetail}'s own javadoc. */
    record UserRef(long id, String displayName) {
    }

    /**
     * C-107 · {@code ObJourneyStepItem} — one checklist entry, wire shape.
     * Populated as of C-111 by {@code GET /journey-steps/{stepId}}; still
     * empty on {@code skip}'s response, which needs no checklist.
     *
     * <p><b>{@code isDone} means "answered".</b> The column behind it is a
     * three-state {@code Boolean} and the completion gate is satisfied by
     * <em>any</em> answer, True or False — see {@code
     * ObJourneyStepLifecycleService#answerItem}. Mapping this to "answered
     * True" instead would show an item as outstanding that the server is
     * perfectly willing to complete over.
     *
     * <p><b>{@code reviewState} is the second ledger on the row</b> —
     * {@code V20260916_1700}'s manager review gate. {@code answer} is what
     * the implementor claims, {@code reviewState} is whether an OB Manager
     * accepted it, and they are separate fields for the same reason they are
     * separate columns: one field would mean the verdict overwrites the claim
     * it is judging.
     *
     * @param isDone answered, either way — {@code answer != null}.
     * @param answer the three states the column actually holds: True, False,
     *               and null for not yet answered.
     * @param remark why. The implementor's note while the row is theirs —
     *               optional on either answer, PLAN.md §4 D-17 — and the
     *               manager's reason once they have rejected it, where it is
     *               mandatory. One field, two authors; see the migration
     *               header for why there is no second column.
     * @param reviewState {@code NOT_REVIEWED}, {@code VERIFIED} or {@code
     *               REJECTED}. Never null, unlike {@code answer}: a row
     *               always has a verdict position, even if it is "none yet".
     * @param reviewedAt when the verdict was recorded, null while none is.
     * @param reviewedBy who recorded it. Id only, like {@code doneBy} — no
     *               route on this tree resolves display names.
     * @param reviewLocked the row was verified by a review that has already
     *               <b>closed</b>, and is shut to everybody: the implementor
     *               may not revise an accepted answer, and the reviewer is not
     *               shown it again.
     *
     *               <p>Computed here rather than derived by each client from
     *               {@code reviewState}, because "verified" and "locked" are
     *               not the same moment. A verdict recorded against the current
     *               submission is still the reviewer's to change — that is what
     *               makes the three-state control usable — and only a review
     *               that has ended makes it permanent. A screen that read
     *               {@code reviewState == VERIFIED} as "locked" would disable
     *               the control on the press that set it.
     */
    record ObJourneyStepItem(
            Long id, Long stepId, int sequence, String label,
            boolean isMandatory, boolean isDone, Boolean answer, String remark,
            Instant doneAt, UserRef doneBy,
            ObStepReviewState reviewState, Instant reviewedAt, UserRef reviewedBy,
            boolean reviewLocked,
            ObStepRowState rowState, Instant submittedAt, UserRef submittedBy,
            Instant outcomeSeenAt, boolean unseenOutcome) {
    }

    /**
     * Ask for one row to be looked at — the body-less
     * {@code POST /onboarding/journey-step-items/{itemId}/submit}.
     *
     * <p>No request record, because there is nothing to say: the row already
     * carries its answer and its remark, and this is the act of handing it
     * over. A body would invite a client to change the answer in the same
     * call, which is exactly the write the reviewer's verdict has to be able
     * to trust did not happen after they read it.
     */
    record ObStepItemSendResponse(ObJourneyStepItem data) {
    }

    /**
     * How many outcomes this press cleared —
     * {@code POST /onboarding/journey-steps/{stepId}/outcomes-seen}.
     *
     * <p>A count rather than {@code 204}, so a client can tell "there were
     * three and now there are none" from "there was nothing to clear" without
     * re-reading the task.
     */
    record ObStepOutcomesSeenResponse(ObStepOutcomesSeen data) {
    }

    record ObStepOutcomesSeen(long stepId, int cleared) {
    }

    /**
     * The manager's verdict on one row — the body of
     * {@code PATCH /onboarding/journey-step-items/{itemId}/review}.
     *
     * <p>{@code state} is mandatory and {@code NOT_REVIEWED} is a legitimate
     * value: it is how a manager takes back a mark pressed by mistake. A
     * nullable field meaning the same thing would make "clear this verdict"
     * indistinguishable from "leave it alone", which is the distinction
     * {@code ObJourneyStepUpdateRequest} needs {@link JsonNullable} for — not
     * needed here, because every call states a position.
     *
     * <p>{@code remark} is the reason, and the service refuses a {@code
     * REJECTED} without one. It is ignored on the other two states rather
     * than rejected as a bad request: a client that sends the box's contents
     * along with every verdict is doing something reasonable, and silently
     * not writing it is kinder than a 400 it cannot act on.
     */
    record ObStepItemReviewRequest(
            @NotNull ObStepReviewState state,
            @Size(max = 500) String remark) {
    }

    /**
     * C-107 · {@code ObJourneyStepDoc} — one required-document entry, wire
     * shape. Populated as of C-111 from the <em>template</em> step's
     * checklist, because {@code ob_journey_step_docs} still does not exist.
     *
     * <p>{@code attachmentId} is therefore always {@code null}: nothing links
     * one attachment to one checklist entry, so {@code isSatisfied} is
     * counted rather than matched and no single attachment can honestly be
     * named as the one that satisfied a given row.
     */
    record ObJourneyStepDoc(
            Long id, Long stepId, String label, boolean isRequired, boolean isSatisfied, Long attachmentId) {
    }

    /**
     * C-107 · {@code ObJourneyStepView} + {@code ObJourneyStepDetail}
     * flattened into one wire record — A-118's OB-06 panel shape, first
     * populated here for the one route this task owns: {@code skip}. The
     * schema is {@code allOf}, and the JSON it describes is one flat object,
     * so one record renders it exactly rather than nesting a base and an
     * extension the wire never separates.
     *
     * <p><b>{@code items} and {@code docs} were always empty here until
     * C-111.</b> They are filled by {@code GET /journey-steps/{stepId}} and
     * still empty on {@code skip}'s response, which does not need them.
     * C-107's note that this needed "the template-item join C-106's
     * completion gate owns" was exactly right, and that is how it was
     * resolved: {@code ObJourneyStepLifecycleService#mandatoryByTemplateItemId}
     * is now shared by the gate and the read, so the two cannot drift.
     * {@code ob_journey_step_docs} still does not exist, so the documents
     * come from the template step — see {@link ObJourneyStepDoc}.
     *
     * <p><b>{@code rag}, by contrast, is real as of C-114.</b> {@link
     * #of(ObJourneyStep, ObRag)} takes it as a parameter rather than
     * computing it inline, on this record's own convention: it stays a
     * plain data holder, and {@link ObJourneyStepRagService} (which needs
     * {@code WorkingHoursService}/{@code WorkingCalendarRepository}/{@code
     * ObStepClockEventRepository} — three collaborators this file has never
     * carried) does the work, one level up in the controller. {@code
     * elapsedHours} stays omitted — optional in the schema, and nothing
     * here currently needs the raw hours a client could derive from {@code
     * dueAt} and {@code tatDays} itself.
     *
     * <p>{@link #clockState}, similarly, is a pure function of {@code
     * status} (see {@link ObStepClockState#of}) computed inline, since it
     * needs no collaborator at all.
     *
     * <p><b>{@code ownerIsInherited} is false on every route but the journey
     * read.</b> Only that read knows the project, and only it resolves an
     * ownerless task onto the project's implementor — see
     * {@link #withInheritedOwner}. A transition's response reports the owner
     * the row actually carries, which is what a caller who just changed that
     * row is asking about.
     */
    record ObJourneyStepDetail(
            Long id, Long journeyId, int sequence, String name, ObJourneyStepStatus status,
            Long ownerUserId, Long backupOwnerUserId,
            String blockedReasonCode, String blockedNote,
            Instant startedAt, Instant finishedAt, Instant dueAt,
            String description, ObStepClockState clockState, ObRag rag,
            int tatDays, boolean requiresSignoff, Long dependsOnStepId,
            String skipReason, Long skippedByUserId,
            List<ObJourneyStepItem> items, List<ObJourneyStepDoc> docs,
            Long effectiveOwnerUserId,
            Long stageKey, String stageName, Double tatUsedPercent,
            boolean ownerIsInherited) {

        /**
         * The same step, told which implementation stage it belongs to.
         *
         * <h2>A copy rather than two more factory parameters</h2>
         *
         * <p>Eleven call sites build this record and exactly one of them — the
         * journey read — knows the stage. The stage is resolved by a join back
         * to the template, so the five lifecycle transitions and the ETag
         * precondition would each have to run that query to fill in an argument
         * their callers never read.
         *
         * <p>So the stage is absent everywhere by default and added by the one
         * caller that has it. Absent is honest here: it is null because nothing
         * looked it up, not because the task belongs to no stage — a task
         * outside a stage reports key {@code 0} and the name {@code Ungrouped},
         * which is a different answer and stays distinguishable from this one.
         *
         * @param stageKey  folded identically to {@code ObProjectStage.stageKey}
         *                  and {@code ObStepDot.stageKey} — see
         *                  {@code ObJourneyReadRepository#stagesOfJourney}.
         * @param stageName the stage's published name.
         */
        ObJourneyStepDetail withStage(Long stageKey, String stageName) {
            return new ObJourneyStepDetail(
                    id, journeyId, sequence, name, status,
                    ownerUserId, backupOwnerUserId,
                    blockedReasonCode, blockedNote,
                    startedAt, finishedAt, dueAt,
                    description, clockState, rag,
                    tatDays, requiresSignoff, dependsOnStepId,
                    skipReason, skippedByUserId,
                    items, docs, effectiveOwnerUserId,
                    stageKey, stageName, tatUsedPercent,
                    ownerIsInherited);
        }

        /**
         * The same task, carrying what fraction of its TAT budget is gone.
         *
         * <p>A second copy method rather than two more factory parameters, on
         * {@link #withStage}'s reasoning exactly: one caller — the journey read
         * — can compute this, and the five transitions would each have to run
         * the calculation to fill in an argument their callers never read.
         */
        ObJourneyStepDetail withTatUsed(Double percent) {
            return new ObJourneyStepDetail(
                    id, journeyId, sequence, name, status,
                    ownerUserId, backupOwnerUserId,
                    blockedReasonCode, blockedNote,
                    startedAt, finishedAt, dueAt,
                    description, clockState, rag,
                    tatDays, requiresSignoff, dependsOnStepId,
                    skipReason, skippedByUserId,
                    items, docs, effectiveOwnerUserId,
                    stageKey, stageName, percent,
                    ownerIsInherited);
        }

        /**
         * C-108 · {@code effectiveOwnerUserId} needs {@link ObBackupOwnerResolver},
         * which needs a repository call this static factory cannot make on its
         * own — so it arrives here already resolved, exactly as C-114's {@code
         * rag} does from {@link ObJourneyStepRagService}. Every caller passes
         * all three.
         */
        static ObJourneyStepDetail of(ObJourneyStep s, ObRag rag, Long effectiveOwnerUserId) {
            return of(s, rag, List.of(), List.of(), effectiveOwnerUserId);
        }

        /**
         * C-111 · the same shape with its checklist filled in — what {@code
         * GET /onboarding/journey-steps/{stepId}} answers.
         *
         * <p>The empty-list overload above is kept rather than replaced:
         * {@code skip}'s response, {@code PATCH}'s response and the {@code
         * ETag} precondition all use it, and none needs a checklist. Making
         * them pay for two extra queries to send fields the caller is not
         * reading would be a cost with no reader.
         */
        static ObJourneyStepDetail of(ObJourneyStep s, ObRag rag,
                List<ObJourneyStepItem> items, List<ObJourneyStepDoc> docs, Long effectiveOwnerUserId) {
            return new ObJourneyStepDetail(
                    s.getId(), s.getJourneyId(), s.getSequence(), s.getName(), s.getStatus(),
                    s.getOwnerUserId(), s.getBackupOwnerUserId(),
                    s.getBlockedReasonCode(), s.getBlockedNote(),
                    s.getStartedAt(), s.getFinishedAt(), s.getDueAt(),
                    s.getDescription(), ObStepClockState.of(s.getStatus()), rag,
                    s.getTatDays(), s.isRequiresSignoff(), s.getDependsOnStepId(),
                    s.getSkipReason(), s.getSkippedBy(),
                    items, docs, effectiveOwnerUserId,
                    null, null, null,
                    false);
        }

        /**
         * The same task, told that its owner was inherited from the project.
         *
         * <p>A third copy method, on {@link #withStage}'s reasoning exactly:
         * only the journey read knows the project, and the five transitions
         * would each have to look one up to fill in an argument their callers
         * never read. Their responses report the owner the row actually
         * carries, which is the honest answer for a transition.
         *
         * @param ownerUserId the resolved owner — the project's implementor.
         * @see ObJourneyReadService#detail
         */
        ObJourneyStepDetail withInheritedOwner(Long ownerUserId) {
            return new ObJourneyStepDetail(
                    id, journeyId, sequence, name, status,
                    ownerUserId, backupOwnerUserId,
                    blockedReasonCode, blockedNote,
                    startedAt, finishedAt, dueAt,
                    description, clockState, rag,
                    tatDays, requiresSignoff, dependsOnStepId,
                    skipReason, skippedByUserId,
                    items, docs, ownerUserId,
                    stageKey, stageName, tatUsedPercent,
                    true);
        }
    }

    record ObJourneyStepDetailResponse(ObJourneyStepDetail data) {
        static ObJourneyStepDetailResponse of(ObJourneyStep s, ObRag rag, Long effectiveOwnerUserId) {
            return new ObJourneyStepDetailResponse(ObJourneyStepDetail.of(s, rag, effectiveOwnerUserId));
        }

        static ObJourneyStepDetailResponse of(ObJourneyStep s, ObRag rag,
                List<ObJourneyStepItem> items, List<ObJourneyStepDoc> docs, Long effectiveOwnerUserId) {
            return new ObJourneyStepDetailResponse(
                    ObJourneyStepDetail.of(s, rag, items, docs, effectiveOwnerUserId));
        }
    }

    /**
     * C-111 · {@code PATCH /onboarding/journey-step-items/{itemId}}.
     *
     * <p>🔴 <b>One boolean against a three-state column.</b> {@code
     * ob_journey_step_items.answer} is a nullable {@code Boolean} —
     * unanswered, True, or False-with-a-mandatory-remark — and this request
     * can name two of those three. See {@code
     * ObJourneyStepLifecycleService#answerItem} for what that costs and why
     * the fix is a contract change rather than a workaround here.
     *
     * <p>{@code Boolean} rather than {@code boolean} so a missing field is a
     * clean {@code 400} from {@code @NotNull} instead of silently defaulting
     * to {@code false} and un-answering an item the caller never mentioned.
     */
    /**
     * @param answer {@code true}, {@code false}, or {@code null} to clear back
     *               to unanswered. Deliberately <b>not</b> {@code @NotNull}:
     *               null is a meaningful value here, not a missing one.
     * @param remark why, where there is a why. Optional on either answer
     *               since PLAN.md §4, D-17 — it was compulsory on a false.
     */
    record ObJourneyStepItemUpdateRequest(Boolean answer, @Size(max = 500) String remark) {
    }

    record ObJourneyStepItemResponse(ObJourneyStepItem data) {
    }
}
