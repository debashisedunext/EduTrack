package com.edunext.edutrack.api.feature.transitions;

import java.time.Instant;

/**
 * C-044 · the contract's {@code Ribbon} / {@code RibbonSegment} shapes, and
 * nothing that renders them.
 *
 * <p>Extracted here rather than waited on, on {@code TicketWire}'s own
 * precedent: {@code handoffTicket} answers {@code RibbonResponse} exactly as
 * {@code reworkTicket} and {@code skipStage} will (C-046/C-047), so a second
 * copy of this shape per route is the same mistake {@code TicketWire}'s
 * javadoc names for a second copy of {@code Ticket}.
 *
 * <p><b>What this is not.</b> {@code GET /tickets/{ticketId}/ribbon} — the
 * cycle-selector-aware read C-051/C-053 own — is not built here; only the
 * shape both routes will share. {@link RibbonAssembler} only ever assembles
 * the ticket's <em>current</em> cycle, which is all a handoff response needs.
 */
public final class RibbonWire {

    private RibbonWire() {
    }

    /** {@code UserRef} — a feature-local copy, on {@code EffortLogDtos.UserRef}'s own precedent. */
    record UserRef(long id, String displayName) {
    }

    /** {@code SegmentState} — the six states blueprint §4A.3/C-051 name. */
    enum SegmentState {
        COMPLETED, CURRENT, PENDING, REWORKED, SKIPPED, BLOCKED
    }

    /** One segment of the ribbon — one row per workflow-template stage. */
    record RibbonSegment(
            String stageCode,
            String displayName,
            String icon,
            SegmentState state,
            int sequence,
            UserRef owner,
            String ownerRole,
            Instant enteredAt,
            Instant exitedAt,
            Integer durationMins,
            double effortHrs,
            Integer idleMins,
            int iterationNo,
            int loopBackCount,
            String skipReason,
            String handoffNote) {
    }

    /** {@code Ribbon} — one cycle's journey. */
    public record Ribbon(
            int cycleNo,
            int iterationNo,
            boolean isSealed,
            String currentStageCode,
            boolean canAdvance,
            java.util.List<RibbonSegment> segments) {
    }

    /** {@code RibbonResponse} — the envelope is {@code { data }}, per §1652. */
    record RibbonResponse(Ribbon data) {
    }
}
