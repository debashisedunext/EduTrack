package com.edunext.edutrack.api.feature.onboarding.escalations;

import com.edunext.edutrack.common.pagination.PageMeta;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * C-115 · the wire shapes for {@code /onboarding/escalations}, matching
 * {@code contracts/openapi.yaml}'s {@code ObEscalation} family.
 */
final class ObEscalationDtos {

    private ObEscalationDtos() {
    }

    /** {@code UserRef} — one field, one convention, duplicated per package on {@code CallerIdentityAccess}'s own precedent. */
    record UserRef(long id, String displayName) {

        static UserRef of(Long id, String displayName) {
            return id == null ? null : new UserRef(id, displayName);
        }
    }

    record ObEscalationResponseData(
            long id,
            long obClientId,
            long journeyId,
            long stepId,
            String stepTitle,
            String obClientName,
            String level,
            String reason,
            UserRef escalatedTo,
            Instant escalatedAt,
            UserRef acknowledgedBy,
            Instant acknowledgedAt,
            UserRef resolvedBy,
            Instant resolvedAt,
            String resolutionNote) {

        static ObEscalationResponseData of(ObEscalationReadRepository.Row row) {
            return new ObEscalationResponseData(
                    row.id(), row.obClientId(), row.journeyId(), row.stepId(), row.stepTitle(), row.obClientName(),
                    row.level(), row.reason(),
                    UserRef.of(row.escalatedTo(), row.escalatedToName()), row.escalatedAt(),
                    UserRef.of(row.acknowledgedBy(), row.acknowledgedByName()), row.acknowledgedAt(),
                    UserRef.of(row.resolvedBy(), row.resolvedByName()), row.resolvedAt(),
                    row.resolutionNote());
        }
    }

    record ObEscalationResponse(ObEscalationResponseData data) {
    }

    record ObEscalationListResponse(List<ObEscalationResponseData> data, PageMeta meta) {
    }

    /** {@code note}: mandatory — CONVENTIONS.md's own reading of what a breach review reads afterwards. */
    record ObEscalationResolveRequest(@NotBlank @Size(max = 2000) String note) {
    }
}
