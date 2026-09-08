package com.edunext.edutrack.api.feature.onboarding.instances;

import com.edunext.edutrack.domain.onboarding.ObJourneyStep;
import com.edunext.edutrack.domain.onboarding.ObJourneyStepRagService;
import com.edunext.edutrack.domain.onboarding.ObJourneyStepStatus;
import com.edunext.edutrack.domain.onboarding.ObRag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

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
                case WAITING_ON_CLIENT -> PAUSED;
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
     */
    record ObJourneyStepItem(
            Long id, Long stepId, int sequence, String label,
            boolean isMandatory, boolean isDone, Instant doneAt, UserRef doneBy) {
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
     */
    record ObJourneyStepDetail(
            Long id, Long journeyId, int sequence, String name, ObJourneyStepStatus status,
            Long ownerUserId, Long backupOwnerUserId,
            String blockedReasonCode, String blockedNote,
            Instant startedAt, Instant finishedAt, Instant dueAt,
            String description, ObStepClockState clockState, ObRag rag,
            int tatDays, boolean requiresSignoff, Long dependsOnStepId,
            String skipReason, Long skippedByUserId,
            List<ObJourneyStepItem> items, List<ObJourneyStepDoc> docs) {

        static ObJourneyStepDetail of(ObJourneyStep s, ObRag rag) {
            return of(s, rag, List.of(), List.of());
        }

        /**
         * C-111 · the same shape with its checklist filled in — what {@code
         * GET /onboarding/journey-steps/{stepId}} answers.
         *
         * <p>The empty-list overload above is kept rather than replaced:
         * {@code skip}'s response and the {@code ETag} precondition both use
         * it, and neither needs a checklist. Making them pay for two extra
         * queries to send fields the caller is not reading would be a cost
         * with no reader.
         */
        static ObJourneyStepDetail of(ObJourneyStep s, ObRag rag,
                List<ObJourneyStepItem> items, List<ObJourneyStepDoc> docs) {
            return new ObJourneyStepDetail(
                    s.getId(), s.getJourneyId(), s.getSequence(), s.getName(), s.getStatus(),
                    s.getOwnerUserId(), s.getBackupOwnerUserId(),
                    s.getBlockedReasonCode(), s.getBlockedNote(),
                    s.getStartedAt(), s.getFinishedAt(), s.getDueAt(),
                    s.getDescription(), ObStepClockState.of(s.getStatus()), rag,
                    s.getTatDays(), s.isRequiresSignoff(), s.getDependsOnStepId(),
                    s.getSkipReason(), s.getSkippedBy(),
                    items, docs);
        }
    }

    record ObJourneyStepDetailResponse(ObJourneyStepDetail data) {
        static ObJourneyStepDetailResponse of(ObJourneyStep s, ObRag rag) {
            return new ObJourneyStepDetailResponse(ObJourneyStepDetail.of(s, rag));
        }

        static ObJourneyStepDetailResponse of(ObJourneyStep s, ObRag rag,
                List<ObJourneyStepItem> items, List<ObJourneyStepDoc> docs) {
            return new ObJourneyStepDetailResponse(ObJourneyStepDetail.of(s, rag, items, docs));
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
    record ObJourneyStepItemUpdateRequest(@NotNull Boolean isDone) {
    }

    record ObJourneyStepItemResponse(ObJourneyStepItem data) {
    }
}
