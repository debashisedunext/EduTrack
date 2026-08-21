package com.edunext.edutrack.api.feature.transitions;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * C-047 · the shapes {@code POST /tickets/{ticketId}/skip-stage} takes and
 * answers — the contract's inline {@code skipStage} request body and
 * {@code RibbonResponse}, on {@code HandoffDtos}' and {@code ForceMoveDtos}'
 * own precedent for a feature-local copy.
 */
final class SkipDtos {

    private SkipDtos() {
    }

    /**
     * @param reason      mandatory, and this route is the only place that is
     *                    enforced: {@code TicketJournal.append}'s own javadoc
     *                    names {@code SKIP} as deliberately absent from the
     *                    backward-action set whose reason the ledger requires
     *                    ("SKIP moves forwards"), exactly as it does for
     *                    {@code OVERRIDE} and for the reason
     *                    {@code ForceMoveDtos} records. The bounds are the
     *                    contract's own — {@code minLength: 3, maxLength: 500}
     *                    — and deliberately not {@code ForceMoveRequest}'s
     *                    2000: a skip reason is a sentence a PM types into a
     *                    tooltip that has to stay readable on hover, which is
     *                    the contract's judgement rather than this DTO's
     * @param toStageCode <b>where the ticket lands</b>, not which stage is
     *                    being skipped — the same meaning this field carries on
     *                    {@code /handoff}, {@code /rework} and
     *                    {@code /force-move}, and the stage being skipped is
     *                    always the one the ticket is standing in. Optional:
     *                    absent, it defaults to the template's next stage after
     *                    the one being skipped, which is what the contract says
     *                    and what {@code frontend/src/mocks/handlers/ribbon.ts}
     *                    has defaulted since D-004
     */
    record SkipRequest(
            @NotBlank @Size(min = 3, max = 500) String reason,
            @Size(max = 20) String toStageCode) {
    }

    /** {@code RibbonResponse} — the envelope is {@code { data }}, per §1652. */
    record RibbonResponse(RibbonWire.Ribbon data) {
    }
}
