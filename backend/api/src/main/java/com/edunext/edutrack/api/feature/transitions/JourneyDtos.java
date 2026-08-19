package com.edunext.edutrack.api.feature.transitions;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * C-058 · the shapes {@code GET /tickets/{ticketId}/journey} returns, matching
 * the contract's {@code JourneyResponse} field for field.
 *
 * <p>The names here are load-bearing rather than cosmetic. springdoc emits the
 * spec from these records and D-005's CI check fails the build when the
 * committed TypeScript client drifts from it, so a field renamed here is a
 * broken frontend one job later.
 */
final class JourneyDtos {

    /**
     * The contract's {@code UserRef}, declared locally rather than shared —
     * following {@code EffortLogDtos.UserRef}, {@code CommentDtos.UserRef} and
     * {@code AttachmentDtos.UserRef}, which each do the same for the same
     * reason: a shared two-field record across four features is a coupling that
     * buys nothing and breaks all four when one of them needs a third field.
     */
    record UserRef(long id, String displayName) {
    }

    @Schema(description = "One hop: a stage a ticket entered, who held it, and what it cost.")
    record JourneyRow(
            Integer iterationNo,
            Integer cycleNo,
            String stageCode,

            @Schema(description = "Null when the receiving role had nobody free and the ticket fell "
                    + "to a project-level queue (§4A.2). A real state, not missing data.")
            UserRef resource,

            String role,
            Instant enteredAt,

            @Schema(description = "Null for the hop the ticket is sitting in now — entered, not yet left.")
            Instant exitedAt,

            @Schema(description = "Null for an open hop, for the same reason as exitedAt. Zero would "
                    + "claim the stage took no time rather than that it is still running.")
            Integer durationMins,

            BigDecimal effortHrs,

            @Schema(description = "durationMins − Σ effort in this stage AND iteration AND cycle. The "
                    + "point of the endpoint: two days of duration against two hours of effort is a "
                    + "queue problem, not a capacity problem. Null whenever durationMins is.")
            Integer idleMins) {
    }

    record PerResource(UserRef resource, BigDecimal effortHrs) {
    }

    record JourneyData(
            List<JourneyRow> rows,
            List<PerResource> perResource,

            @Schema(description = "Every effort hour booked against the cycle being viewed.")
            BigDecimal cycleTotalHrs,

            @Schema(description = "Every effort hour booked against this ticket, all cycles. Read from "
                    + "the materialised tickets.total_effort_hrs (C-041), not summed from the rows "
                    + "above, which only cover one cycle.")
            BigDecimal allCyclesTotalHrs) {
    }

    record JourneyResponse(JourneyData data) {
    }

    private JourneyDtos() {
    }
}
