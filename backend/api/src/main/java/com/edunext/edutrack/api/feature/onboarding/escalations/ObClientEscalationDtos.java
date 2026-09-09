package com.edunext.edutrack.api.feature.onboarding.escalations;

import com.edunext.edutrack.common.pagination.PageMeta;

import java.time.Instant;
import java.util.List;

/**
 * C-126 · the wire shapes for {@code /onboarding/client-escalations},
 * matching {@code contracts/openapi.yaml}'s {@code ObClientEscalation}
 * family — the staff side of {@code ob_client_escalations}. The client's own
 * raise route is {@code PortalOnboardingDtos}, a deliberately separate and
 * narrower shape (see that class's own note).
 */
final class ObClientEscalationDtos {

    private ObClientEscalationDtos() {
    }

    /** {@code UserRef} — duplicated per package on {@code ObEscalationDtos.UserRef}'s own precedent. */
    record UserRef(long id, String displayName) {

        static UserRef of(Long id, String displayName) {
            return id == null ? null : new UserRef(id, displayName);
        }
    }

    /**
     * {@code ObContact} — duplicated per package on {@code UserRef}'s own
     * precedent above, rather than importing {@code
     * feature.onboarding.clients.ObClientDtos.ObContact}: that record is
     * package-private in a different stream's package (B-102), and widening
     * it for one field here would be a cross-stream edit for a ten-field
     * record this route only ever reads, never writes.
     */
    record ObContact(long id, String name, String designation, String email, String phone,
                     boolean whatsappOptIn, Instant whatsappOptInAt, String whatsappOptInSource,
                     boolean isPrimary, boolean isActive) {
    }

    /**
     * One row of {@code ob_client_escalations}, the shape the contract's own
     * {@code ObClientEscalation} schema describes — named identically so
     * {@code ObWireConformanceTest}'s scan matches it and checks every field
     * the contract requires is actually emitted.
     */
    record ObClientEscalation(
            long id, long obClientId, String obClientName, long journeyId, long stepId, String stepTitle,
            ObContact raisedByContact, String comment, Instant raisedAt,
            UserRef resolvedBy, Instant resolvedAt, String resolutionNote) {

        static ObClientEscalation of(ObClientEscalationRepository.Row row) {
            return new ObClientEscalation(
                    row.id(), row.obClientId(), row.obClientName(), row.journeyId(), row.stepId(), row.stepTitle(),
                    new ObContact(row.contactId(), row.contactName(), row.contactDesignation(),
                            row.contactEmail(), row.contactPhone(),
                            row.contactWhatsappOptIn(), row.contactWhatsappOptInAt(), row.contactWhatsappOptInSource(),
                            row.contactIsPrimary(), row.contactIsActive()),
                    row.comment(), row.raisedAt(),
                    UserRef.of(row.resolvedBy(), row.resolvedByName()), row.resolvedAt(), row.resolutionNote());
        }
    }

    record ObClientEscalationResponse(ObClientEscalation data) {
    }

    record ObClientEscalationListResponse(List<ObClientEscalation> data, PageMeta meta) {
    }
}
