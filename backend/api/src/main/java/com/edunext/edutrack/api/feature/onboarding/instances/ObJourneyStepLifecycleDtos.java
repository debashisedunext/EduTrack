package com.edunext.edutrack.api.feature.onboarding.instances;

import com.edunext.edutrack.domain.onboarding.ObJourneyStep;
import com.edunext.edutrack.domain.onboarding.ObJourneyStepStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

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
}
