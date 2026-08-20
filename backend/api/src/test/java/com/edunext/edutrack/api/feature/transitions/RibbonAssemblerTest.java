package com.edunext.edutrack.api.feature.transitions;

import com.edunext.edutrack.domain.journal.TicketJournal;
import com.edunext.edutrack.domain.tickets.Ticket;
import com.edunext.edutrack.domain.tickets.TicketEffortLog;
import com.edunext.edutrack.domain.workflow.TicketStageTransition;
import com.edunext.edutrack.domain.workflow.WorkflowStage;
import com.edunext.edutrack.domain.workflow.WorkflowStageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * C-044 · one segment per template stage — one row per {@link WorkflowStage},
 * derived from the current cycle's hops rather than one row per hop. Proves
 * the state derivation ({@code PENDING}/{@code CURRENT}/{@code COMPLETED}/
 * {@code REWORKED}/{@code SKIPPED}) and that effort is summed per stage across
 * the whole cycle, not joined to one iteration.
 */
class RibbonAssemblerTest {

    private static final long TEMPLATE = 3L;
    private static final long TICKET = 347L;

    private final TicketJournal journal = mock(TicketJournal.class);
    private final WorkflowStageRepository stages = mock(WorkflowStageRepository.class);
    private final TransitionUserRefs people = mock(TransitionUserRefs.class);

    private final RibbonAssembler assembler = new RibbonAssembler(journal, stages, people);

    private Ticket ticket;

    @BeforeEach
    void setUp() {
        ticket = new Ticket();
        ticket.setId(TICKET);
        ticket.setWorkflowTemplateId(TEMPLATE);
        ticket.setStatus("IN_PROGRESS");
        ticket.setCurrentCycleNo((short) 1);
        ticket.setCurrentIteration((short) 2);
        ticket.setCurrentStage("QA");

        when(stages.findByTemplateIdOrderBySeqAsc(TEMPLATE))
                .thenReturn(List.of(stage("TRIAGE", 1), stage("DEV", 2), stage("QA", 3), stage("DEPLOY", 4)));
        when(people.resolve(any())).thenReturn(Map.of());
    }

    @Test
    @DisplayName("a stage never entered is PENDING with no owner, no dates, zero effort")
    void neverEnteredIsPending() {
        when(journal.hopsFor(TICKET, (short) 1)).thenReturn(List.of(hop("TRIAGE", false, "FORWARD")));
        when(journal.effortFor(TICKET, (short) 1)).thenReturn(List.of());

        RibbonWire.Ribbon ribbon = assembler.assembleCurrentCycle(ticket, true);

        RibbonWire.RibbonSegment deploy = segment(ribbon, "DEPLOY");
        assertThat(deploy.state()).isEqualTo(RibbonWire.SegmentState.PENDING);
        assertThat(deploy.owner()).isNull();
        assertThat(deploy.enteredAt()).isNull();
        assertThat(deploy.effortHrs()).isZero();
    }

    @Test
    @DisplayName("the open hop's stage is CURRENT")
    void openHopIsCurrent() {
        when(journal.hopsFor(TICKET, (short) 1)).thenReturn(List.of(
                hop("TRIAGE", false, "FORWARD"), hop("DEV", false, "FORWARD"), hop("QA", true, "FORWARD")));
        when(journal.effortFor(TICKET, (short) 1)).thenReturn(List.of());

        RibbonWire.Ribbon ribbon = assembler.assembleCurrentCycle(ticket, true);

        assertThat(segment(ribbon, "QA").state()).isEqualTo(RibbonWire.SegmentState.CURRENT);
        assertThat(segment(ribbon, "TRIAGE").state()).isEqualTo(RibbonWire.SegmentState.COMPLETED);
    }

    @Test
    @DisplayName("a stage entered twice in the cycle is REWORKED with loopBackCount 1")
    void reenteredIsReworked() {
        when(journal.hopsFor(TICKET, (short) 1)).thenReturn(List.of(
                hop("DEV", false, "FORWARD"), hop("QA", false, "REWORK"), hop("DEV", true, "FORWARD")));
        when(journal.effortFor(TICKET, (short) 1)).thenReturn(List.of());

        RibbonWire.Ribbon ribbon = assembler.assembleCurrentCycle(ticket, true);

        RibbonWire.RibbonSegment dev = segment(ribbon, "DEV");
        assertThat(dev.state()).isEqualTo(RibbonWire.SegmentState.CURRENT);
        assertThat(dev.loopBackCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a SKIP action renders SKIPPED with the reason, not the stage's history state")
    void skippedCarriesReason() {
        TicketStageTransition skip = hop("DEV", false, "SKIP");
        skip.setReason("no dev work needed, straight to QA");
        when(journal.hopsFor(TICKET, (short) 1)).thenReturn(List.of(skip));
        when(journal.effortFor(TICKET, (short) 1)).thenReturn(List.of());

        RibbonWire.Ribbon ribbon = assembler.assembleCurrentCycle(ticket, true);

        RibbonWire.RibbonSegment dev = segment(ribbon, "DEV");
        assertThat(dev.state()).isEqualTo(RibbonWire.SegmentState.SKIPPED);
        assertThat(dev.skipReason()).isEqualTo("no dev work needed, straight to QA");
    }

    @Test
    @DisplayName("effort is summed per stage across the whole cycle, not per iteration")
    void effortSummedPerStageAcrossCycle() {
        when(journal.hopsFor(TICKET, (short) 1)).thenReturn(List.of(
                hop("DEV", false, "FORWARD"), hop("QA", false, "REWORK"), hop("DEV", true, "FORWARD")));
        when(journal.effortFor(TICKET, (short) 1)).thenReturn(List.of(
                effort("DEV", (short) 1, new BigDecimal("2.00")),
                effort("DEV", (short) 2, new BigDecimal("1.50"))));

        RibbonWire.Ribbon ribbon = assembler.assembleCurrentCycle(ticket, true);

        assertThat(segment(ribbon, "DEV").effortHrs()).isEqualTo(3.50);
    }

    @Test
    @DisplayName("a ticket with no workflow template answers an empty segment list, not a guess")
    void noTemplateIsEmpty() {
        ticket.setWorkflowTemplateId(null);

        RibbonWire.Ribbon ribbon = assembler.assembleCurrentCycle(ticket, true);

        assertThat(ribbon.segments()).isEmpty();
    }

    @Test
    @DisplayName("isSealed follows the ticket's CLOSED status; currentStageCode is hidden once sealed")
    void sealedHidesCurrentStage() {
        ticket.setStatus("CLOSED");
        when(journal.hopsFor(TICKET, (short) 1)).thenReturn(List.of());
        when(journal.effortFor(TICKET, (short) 1)).thenReturn(List.of());

        RibbonWire.Ribbon ribbon = assembler.assembleCurrentCycle(ticket, false);

        assertThat(ribbon.isSealed()).isTrue();
        assertThat(ribbon.currentStageCode()).isNull();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private static RibbonWire.RibbonSegment segment(RibbonWire.Ribbon ribbon, String stageCode) {
        return ribbon.segments().stream()
                .filter(s -> s.stageCode().equals(stageCode))
                .findFirst()
                .orElseThrow();
    }

    private static WorkflowStage stage(String code, int seq) {
        WorkflowStage s = new WorkflowStage();
        ReflectionTestUtils.setField(s, "stageCode", code);
        s.setSeq((short) seq);
        s.setDisplayName(code);
        s.setOwnerRole("DEVELOPER");
        return s;
    }

    private static TicketStageTransition hop(String toStage, boolean current, String actionCode) {
        TicketStageTransition h = new TicketStageTransition();
        h.setTicketId(TICKET);
        h.setCycleNo((short) 1);
        h.setIterationNo((short) 1);
        h.setToStage(toStage);
        h.setToUserId(1L);
        h.setActionCode(actionCode);
        h.setEnteredAt(Instant.parse("2026-08-17T09:00:00Z"));
        if (!current) {
            h.setExitedAt(Instant.parse("2026-08-17T13:00:00Z"));
            h.setDurationMins(240);
            h.setCurrent(false);
        }
        return h;
    }

    private static TicketEffortLog effort(String stageCode, short iterationNo, BigDecimal hours) {
        TicketEffortLog e = new TicketEffortLog();
        e.setTicketId(TICKET);
        e.setCycleNo((short) 1);
        e.setStageCode(stageCode);
        e.setIterationNo(iterationNo);
        e.setUserId(1L);
        e.setHours(hours);
        return e;
    }
}
