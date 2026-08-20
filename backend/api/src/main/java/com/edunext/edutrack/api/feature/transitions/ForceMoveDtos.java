package com.edunext.edutrack.api.feature.transitions;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * C-048 · the shapes {@code POST /tickets/{ticketId}/force-move} takes and
 * answers — {@code ForceMoveRequest} and {@code RibbonResponse} in the
 * contract, {@code HandoffDtos}' own precedent for a feature-local copy.
 */
final class ForceMoveDtos {

    private ForceMoveDtos() {
    }

    /**
     * @param toStageCode any stage of the ticket's workflow template, and
     *                    required here for the reason {@code HandoffDtos.HandoffRequest}
     *                    gives for the same field on {@code FORWARD}: leaving
     *                    it blank would fall through to
     *                    {@code TransitionService.resolveToStage}'s default,
     *                    right for a generic transition and wrong for a route
     *                    whose whole point is "any stage" chosen deliberately —
     *                    {@link ToStageRequiredException} is in fact
     *                    unreachable through this route, since {@code advance}
     *                    is never called with a blank one
     * @param reason      mandatory, so an override reads as self-explaining in
     *                    the ribbon's history as a rework's reason does. Not
     *                    enforced by {@code TicketJournal.append} — its own
     *                    javadoc names {@code OVERRIDE} as deliberately absent
     *                    from the actions a backward move's mandatory reason
     *                    covers — so this is the one place it is required
     * @param toUserId    the receiving owner; {@code null} keeps the current one
     */
    record ForceMoveRequest(
            @NotBlank @Size(max = 20) String toStageCode,
            @NotBlank @Size(min = 3, max = 2000) String reason,
            Long toUserId) {
    }

    /** {@code RibbonResponse} — the envelope is {@code { data }}, per §1652. */
    record RibbonResponse(RibbonWire.Ribbon data) {
    }
}
