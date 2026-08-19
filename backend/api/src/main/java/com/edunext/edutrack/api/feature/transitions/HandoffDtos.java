package com.edunext.edutrack.api.feature.transitions;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * C-044 · the shapes {@code POST /tickets/{ticketId}/handoff} takes and
 * answers — {@code HandoffRequest} and {@code RibbonResponse} in the
 * contract, mirroring {@code ReopenDtos}' precedent for a feature-local copy.
 */
final class HandoffDtos {

    private HandoffDtos() {
    }

    /**
     * @param toStageCode   pre-filled from the workflow template by the
     *                      client; required here too, since {@code FORWARD}
     *                      with none would otherwise silently default to
     *                      {@code TransitionService.nextStageAfter} — right
     *                      for a generic transition, wrong for a dialog that
     *                      showed the user a specific destination
     * @param toUserId      must hold the receiving stage's owner role on this
     *                      project; the picker enforces that client-side, and
     *                      nothing here re-derives it, on the same reasoning
     *                      {@code TransitionService.advance} gives for not
     *                      re-deriving {@code toStageCode}'s validity beyond
     *                      "is it a stage of this template"
     * @param effortHours   effort for the stage being left. {@code null} is a
     *                      400 ({@link EffortHoursRequiredException}) —
     *                      mandatory by default (governance decision G-1) —
     *                      but {@code 0} is accepted and simply logs nothing,
     *                      since {@code ck_effort_hours} rejects a zero-hour
     *                      row outright
     * @param attachmentIds accepted and otherwise unused — see
     *                      {@code HandoffService}'s class javadoc
     */
    record HandoffRequest(
            @NotBlank @Size(max = 20) String toStageCode,
            @NotNull Long toUserId,
            @Size(max = 4000) String note,
            @DecimalMin(value = "0", inclusive = true) BigDecimal effortHours,
            List<Long> attachmentIds) {
    }

    /** {@code RibbonResponse} — the envelope is {@code { data }}, per §1652. */
    record RibbonResponse(RibbonWire.Ribbon data) {
    }
}
