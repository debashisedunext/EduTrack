package com.edunext.edutrack.api.feature.onboarding.instances;

import com.edunext.edutrack.domain.onboarding.ObJourneyStep;
import com.edunext.edutrack.domain.onboarding.ObJourneyStepStatus;
import jakarta.validation.constraints.NotBlank;
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

    /** C-107 · the health colour, per {@code ObRag}. Never computed here — see {@link ObJourneyStepDetail}'s own javadoc. */
    enum ObRag {
        GREEN, AMBER, RED
    }

    /** C-107 · local, on this file's own {@code UserRef} convention — see {@link ObJourneyStepDetail}'s own javadoc. */
    record UserRef(long id, String displayName) {
    }

    /**
     * C-107 · {@code ObJourneyStepItem} — one checklist entry, wire shape.
     * Never populated by this route today; see {@link ObJourneyStepDetail}'s
     * own javadoc for why.
     */
    record ObJourneyStepItem(
            Long id, Long stepId, int sequence, String label,
            boolean isMandatory, boolean isDone, Instant doneAt, UserRef doneBy) {
    }

    /**
     * C-107 · {@code ObJourneyStepDoc} — one required-document entry, wire
     * shape. Never populated by this route today; see {@link
     * ObJourneyStepDetail}'s own javadoc for why.
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
     * <p><b>{@code items} and {@code docs} are always empty here, and {@code
     * rag} is always {@code null}.</b> Named rather than silently wrong:
     * populating a checklist's real {@code isMandatory} needs the
     * template-item join C-106's completion gate owns, and {@code
     * ob_journey_step_docs} — the instance-level required-document table —
     * does not exist in any migration yet. {@code rag} needs the
     * working-calendar TAT-consumed percentage C-105/C-120 compute; "null
     * where there is nothing to colour" is the schema's own allowance and is
     * accurate for a route that cannot compute it, not a guess standing in
     * for one. {@code elapsedHours} is omitted for the identical reason —
     * optional in the schema, and there is nothing honest to put there before
     * C-105's clock events exist.
     *
     * <p>{@link #clockState}, by contrast, <em>is</em> real: it is a pure
     * function of {@code status} (see {@link ObStepClockState#of}), so it
     * costs nothing to get right now and there is no reason to leave it a
     * placeholder.
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

        static ObJourneyStepDetail of(ObJourneyStep s) {
            return new ObJourneyStepDetail(
                    s.getId(), s.getJourneyId(), s.getSequence(), s.getName(), s.getStatus(),
                    s.getOwnerUserId(), s.getBackupOwnerUserId(),
                    s.getBlockedReasonCode(), s.getBlockedNote(),
                    s.getStartedAt(), s.getFinishedAt(), s.getDueAt(),
                    s.getDescription(), ObStepClockState.of(s.getStatus()), null,
                    s.getTatDays(), s.isRequiresSignoff(), s.getDependsOnStepId(),
                    s.getSkipReason(), s.getSkippedBy(),
                    List.of(), List.of());
        }
    }

    record ObJourneyStepDetailResponse(ObJourneyStepDetail data) {
        static ObJourneyStepDetailResponse of(ObJourneyStep s) {
            return new ObJourneyStepDetailResponse(ObJourneyStepDetail.of(s));
        }
    }
}
