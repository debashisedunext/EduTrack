package com.edunext.edutrack.api.feature.transitions;

import com.edunext.edutrack.domain.journal.TicketJournal;
import com.edunext.edutrack.domain.tickets.Ticket;
import com.edunext.edutrack.domain.tickets.TicketEffortLog;
import com.edunext.edutrack.domain.workflow.TicketStageTransition;
import com.edunext.edutrack.domain.workflow.WorkflowStage;
import com.edunext.edutrack.domain.workflow.WorkflowStageRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * C-044 · builds the {@link RibbonWire.Ribbon} a handoff (and, on the same
 * shape, C-046's rework and C-047's skip) answers with.
 *
 * <h2>Scope</h2>
 *
 * <p>Only the ticket's <b>current</b> cycle. That is everything a lifecycle
 * route's response needs — the caller already knows which ticket they just
 * moved — and it is deliberately not the cycle-selector-aware read C-051's
 * ribbon component and C-053's cycle selector own for {@code GET
 * /tickets/{ticketId}/ribbon}. This class exists so that route can share the
 * shape rather than invent it, not to pre-empt the route itself.
 *
 * <h2>One segment per template stage, not one per hop</h2>
 *
 * <p>Mirrors the shape {@code frontend/src/mocks/handlers/tickets.ts}'
 * {@code buildRibbon} already committed the frontend to: the ribbon renders
 * one segment per {@link WorkflowStage} the ticket's template declares, and a
 * stage entered more than once in this cycle (a rework loop) is still one
 * segment — {@code REWORKED}, with {@link RibbonWire.RibbonSegment#loopBackCount}
 * carrying how many extra times. A ticket with no {@code workflow_template_id}
 * (predates B's designer, {@code TransitionService.resolveToStage}'s own note)
 * answers an empty segment list rather than guessing a template.
 *
 * <h2>C-047 · a skipped segment is the stage the ticket left</h2>
 *
 * <p>A {@code SKIP} hop records that the ticket <em>left</em> {@code from_stage}
 * by skipping it and landed on {@code to_stage}; the stage to strike through is
 * therefore the departure's, not the arrival's. This class read the arriving
 * hop's action code until C-047 and struck through the destination — see
 * {@link #toSegment}. The skip only wins while it is the last thing that
 * happened to that stage: a ticket skipped past and later reworked back into a
 * stage is standing in it, not past it.
 *
 * <h2>Effort per segment</h2>
 *
 * <p>Summed across the whole cycle for that stage — every iteration, not
 * joined to one — because a ribbon segment represents the stage as a whole,
 * however many times the ticket looped back into it. This is deliberately
 * <b>not</b> the §4A.4 Journey roll-up's per-iteration join (PLAN.md §3.4,
 * C-058): that grid answers "how much effort in stage X, iteration 2 of cycle
 * 3", and this answers "how much effort in stage X this cycle, in total".
 * Both are real questions; conflating them would make this segment's number
 * disagree with what a resource actually logged.
 */
@Component
class RibbonAssembler {

    private static final String SKIP = "SKIP";

    private final TicketJournal journal;
    private final WorkflowStageRepository stages;
    private final TransitionUserRefs people;

    RibbonAssembler(TicketJournal journal, WorkflowStageRepository stages, TransitionUserRefs people) {
        this.journal = journal;
        this.stages = stages;
        this.people = people;
    }

    /** @param canAdvance already resolved by the caller — {@link StageOwnership#mayAdvance} */
    RibbonWire.Ribbon assembleCurrentCycle(Ticket ticket, boolean canAdvance) {
        short cycleNo = ticket.getCurrentCycleNo();
        boolean isSealed = "CLOSED".equals(ticket.getStatus());

        List<WorkflowStage> template = ticket.getWorkflowTemplateId() == null
                ? List.of()
                : stages.findByTemplateIdOrderBySeqAsc(ticket.getWorkflowTemplateId());

        List<TicketStageTransition> hops = journal.hopsFor(ticket.getId(), cycleNo);
        List<TicketEffortLog> effort = journal.effortFor(ticket.getId(), cycleNo);

        Map<String, List<TicketStageTransition>> hopsByStage = new HashMap<>();
        for (TicketStageTransition hop : hops) {
            hopsByStage.computeIfAbsent(hop.getToStage(), key -> new ArrayList<>()).add(hop);
        }
        Map<String, BigDecimal> effortByStage = new HashMap<>();
        for (TicketEffortLog entry : effort) {
            effortByStage.merge(entry.getStageCode(), entry.getHours(), BigDecimal::add);
        }

        // C-047 · a SKIP hop is keyed on the stage it LEFT, not the one it
        // entered — see skipDeparture below. Last one wins: a stage skipped,
        // reworked back into and skipped again shows the most recent reason.
        Map<String, TicketStageTransition> skipsByStageLeft = new HashMap<>();
        for (TicketStageTransition hop : hops) {
            if (SKIP.equals(hop.getActionCode()) && hop.getFromStage() != null) {
                skipsByStageLeft.put(hop.getFromStage(), hop);
            }
        }

        Map<Long, RibbonWire.UserRef> owners = people.resolve(hops.stream()
                .map(TicketStageTransition::getToUserId).toList());

        List<RibbonWire.RibbonSegment> segments = new ArrayList<>();
        for (WorkflowStage stage : template) {
            segments.add(toSegment(stage, hopsByStage.get(stage.getStageCode()),
                    skipsByStageLeft.get(stage.getStageCode()),
                    effortByStage.getOrDefault(stage.getStageCode(), BigDecimal.ZERO), owners));
        }

        return new RibbonWire.Ribbon(
                cycleNo,
                ticket.getCurrentIteration(),
                isSealed,
                isSealed ? null : ticket.getCurrentStage(),
                canAdvance,
                segments);
    }

    /**
     * @param skipDeparture C-047 · the hop by which the ticket <em>left</em>
     *        this stage with action {@code SKIP}, if there is one. A skip is
     *        recorded on the departing hop — {@code from_stage} is the stage
     *        that was skipped and {@code to_stage} is where the ticket landed
     *        — so keying the state on the arriving hop's action code, as this
     *        method did until C-047, struck through the <em>destination</em>
     *        segment instead. Nothing had exercised it: no route wrote a
     *        {@code SKIP} row until {@code SkipService}.
     */
    private RibbonWire.RibbonSegment toSegment(WorkflowStage stage, List<TicketStageTransition> hopsForStage,
                                               TicketStageTransition skipDeparture,
                                               BigDecimal effortHrs, Map<Long, RibbonWire.UserRef> owners) {
        if (hopsForStage == null || hopsForStage.isEmpty()) {
            // A stage the ticket never entered cannot have been departed
            // either, so skipDeparture is unreachable here — and if a
            // malformed ledger ever produced one, PENDING is the honest answer
            // rather than a struck-through segment with no history behind it.
            return new RibbonWire.RibbonSegment(
                    stage.getStageCode(), stage.getDisplayName(), stage.getIcon(),
                    RibbonWire.SegmentState.PENDING, stage.getSeq(),
                    null, stage.getOwnerRole(), null, null, null,
                    round(effortHrs), null, 1, 0, null, null);
        }

        TicketStageTransition last = hopsForStage.get(hopsForStage.size() - 1);
        int loopBackCount = Math.max(0, hopsForStage.size() - 1);
        double effort = round(effortHrs);
        Integer idleMins = last.getDurationMins() != null
                ? Math.max(0, last.getDurationMins() - (int) Math.round(effort * 60))
                : null;

        // Only if the skip is the last thing that happened to this stage. A
        // ticket skipped past QA and later reworked back into it is standing
        // in QA now, and a struck-through label would be a lie about where the
        // work is — seqNo is strictly increasing within a cycle, so comparing
        // the two hops answers it without a second query.
        boolean skipped = skipDeparture != null && skipDeparture.getSeqNo() > last.getSeqNo();

        RibbonWire.SegmentState state;
        if (skipped) {
            state = RibbonWire.SegmentState.SKIPPED;
        } else if (last.isCurrent()) {
            state = RibbonWire.SegmentState.CURRENT;
        } else if (loopBackCount > 0) {
            state = RibbonWire.SegmentState.REWORKED;
        } else {
            state = RibbonWire.SegmentState.COMPLETED;
        }

        return new RibbonWire.RibbonSegment(
                stage.getStageCode(), stage.getDisplayName(), stage.getIcon(),
                state, stage.getSeq(),
                owners.get(last.getToUserId()), stage.getOwnerRole(),
                last.getEnteredAt(), last.getExitedAt(), last.getDurationMins(),
                effort, idleMins, last.getIterationNo(), loopBackCount,
                skipped ? skipDeparture.getReason() : null,
                last.getHandoffNote());
    }

    /** Two decimal places — {@code EffortLogDtos}' own rounding for an hours figure. */
    private static double round(BigDecimal value) {
        return value.setScale(2, java.math.RoundingMode.HALF_UP).doubleValue();
    }
}
