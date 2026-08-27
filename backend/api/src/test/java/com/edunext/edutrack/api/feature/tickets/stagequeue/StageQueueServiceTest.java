package com.edunext.edutrack.api.feature.tickets.stagequeue;

import com.edunext.edutrack.api.feature.tickets.list.TicketListRefs;
import com.edunext.edutrack.api.security.scope.ScopedTickets;
import com.edunext.edutrack.common.pagination.CursorPage;
import com.edunext.edutrack.domain.masters.WorkingHoursService;
import com.edunext.edutrack.domain.tickets.Ticket;
import com.edunext.edutrack.domain.workflow.WorkflowStage;
import com.edunext.edutrack.domain.workflow.WorkflowStageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C-062 · what {@link StageQueueService} guarantees on top of the pieces that
 * already existed — the queue reads through the wider {@code queuePage} scope,
 * time in stage is working minutes, and a stage with no SLA is never breached.
 */
class StageQueueServiceTest {

    private static final long TEMPLATE = 3L;
    private static final Instant NOW = Instant.parse("2026-08-27T12:00:00Z");
    private static final Instant ENTERED = Instant.parse("2026-08-27T08:00:00Z");

    private final ScopedTickets tickets = mock(ScopedTickets.class);
    private final TicketListRefs refs = mock(TicketListRefs.class);
    private final WorkflowStageRepository stages = mock(WorkflowStageRepository.class);
    private final WorkingHoursService workingHours = mock(WorkingHoursService.class);

    private final StageQueueService service = new StageQueueService(
            tickets, refs, stages, workingHours, Clock.fixed(NOW, ZoneOffset.UTC));

    private final Authentication caller = new TestingAuthenticationToken("qa", "n/a");

    @BeforeEach
    void setUp() {
        when(refs.resolve(any())).thenReturn(new TicketListRefs.Resolved(Map.of(), Map.of(), Map.of()));
        when(workingHours.workingHoursBetween(any(), any(), any(), any())).thenReturn(new BigDecimal("3.00"));
        when(stages.findByTemplateIdAndStageCode(any(), any())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("reads through queuePage — the project-membership scope, not the assignee one")
    void readsThroughTheQueueScope() {
        given(ticket(1L, "QA"));

        service.queue(caller, "QA", null, null, null, null);

        // ScopedTickets.page applies §10.2's assigned_to = me, which would make
        // a queue of unassigned work permanently empty. queuePage is the point.
        verify(tickets, times(1)).queuePage(eq(caller), any(), any());
        verify(tickets, org.mockito.Mockito.never()).page(any(), any(), any());
    }

    @Test
    @DisplayName("a lower-case stage code still matches — it is uppercased before the query")
    void stageCodeIsNormalised() {
        given(ticket(1L, "QA"));

        service.queue(caller, "  qa  ", null, null, null, null);

        // Asserted through the SLA lookup, which is handed the same normalised
        // code the specification filters on.
        verify(stages).findByTemplateIdAndStageCode(TEMPLATE, "QA");
    }

    @Test
    @DisplayName("time in stage is working minutes, not the four hours of wall clock")
    void timeInStageIsWorkingMinutes() {
        given(ticket(1L, "QA"));

        CursorPage<StageQueueDtos.QueueRow> page = service.queue(caller, "QA", null, null, null, null);

        // 3.00 working hours from the calendar against 4h of wall clock.
        assertThat(page.data()).singleElement()
                .satisfies(row -> assertThat(row.timeInStageMins()).isEqualTo(180L));
    }

    @Test
    @DisplayName("working minutes are capped at wall-clock — a misconfigured calendar cannot invent time")
    void workingMinutesNeverExceedWallClock() {
        given(ticket(1L, "QA"));
        when(workingHours.workingHoursBetween(any(), any(), any(), any())).thenReturn(new BigDecimal("9.00"));

        CursorPage<StageQueueDtos.QueueRow> page = service.queue(caller, "QA", null, null, null, null);

        assertThat(page.data()).singleElement()
                .satisfies(row -> assertThat(row.timeInStageMins()).isEqualTo(240L));
    }

    @Test
    @DisplayName("a stage with no SLA is never breached, rather than always breached")
    void noSlaIsNeverBreached() {
        given(ticket(1L, "QA"));

        CursorPage<StageQueueDtos.QueueRow> page = service.queue(caller, "QA", null, null, null, null);

        assertThat(page.data()).singleElement()
                .satisfies(row -> assertThat(row.stageSlaBreached()).isFalse());
    }

    @Test
    @DisplayName("a declared SLA breaches once working minutes pass it")
    void declaredSlaBreaches() {
        given(ticket(1L, "QA"));
        when(stages.findByTemplateIdAndStageCode(TEMPLATE, "QA"))
                .thenReturn(Optional.of(stageWithSla(new BigDecimal("2.00"))));

        CursorPage<StageQueueDtos.QueueRow> page = service.queue(caller, "QA", null, null, null, null);

        // 180 working minutes against a 120-minute SLA.
        assertThat(page.data()).singleElement()
                .satisfies(row -> assertThat(row.stageSlaBreached()).isTrue());
    }

    @Test
    @DisplayName("an SLA not yet passed is not breached — the boundary is strict")
    void slaNotYetPassed() {
        given(ticket(1L, "QA"));
        when(stages.findByTemplateIdAndStageCode(TEMPLATE, "QA"))
                .thenReturn(Optional.of(stageWithSla(new BigDecimal("3.00"))));

        CursorPage<StageQueueDtos.QueueRow> page = service.queue(caller, "QA", null, null, null, null);

        // Exactly 180 against exactly 180 — waited the SLA, has not passed it.
        assertThat(page.data()).singleElement()
                .satisfies(row -> assertThat(row.stageSlaBreached()).isFalse());
    }

    @Test
    @DisplayName("the SLA is looked up once per template, not once per row")
    void slaIsResolvedOncePerTemplate() {
        given(ticket(1L, "QA"), ticket(2L, "QA"), ticket(3L, "QA"));

        service.queue(caller, "QA", null, null, null, null);

        // Three rows, one template, one lookup — the n+1 this cache exists for.
        verify(stages, times(1)).findByTemplateIdAndStageCode(TEMPLATE, "QA");
    }

    @Test
    @DisplayName("a ticket with no template has no SLA and is not looked up")
    void noTemplateIsNotLookedUp() {
        Ticket t = ticket(1L, "QA");
        t.setWorkflowTemplateId(null);
        given(t);

        CursorPage<StageQueueDtos.QueueRow> page = service.queue(caller, "QA", null, null, null, null);

        assertThat(page.data()).singleElement()
                .satisfies(row -> assertThat(row.stageSlaBreached()).isFalse());
        verify(stages, org.mockito.Mockito.never()).findByTemplateIdAndStageCode(any(), any());
    }

    @Test
    @DisplayName("the next cursor names stage_entered_at, which is what the keyset predicate parses back")
    void cursorNamesTheSortColumn() {
        // limit+1 rows fetched means there is a next page, so meta carries one.
        List<Ticket> fetched = new java.util.ArrayList<>();
        for (long id = 1; id <= 3; id++) {
            fetched.add(ticket(id, "QA"));
        }
        givenAll(fetched);

        CursorPage<StageQueueDtos.QueueRow> page = service.queue(caller, "QA", null, null, null, 2);

        assertThat(page.data()).hasSize(2);
        assertThat(page.meta().nextCursor()).isNotNull();
        // Decoding it back must yield an instant StageQueueSpecs.after can parse —
        // a cursor naming any other column silently restarts the queue.
        com.edunext.edutrack.common.pagination.Cursor decoded =
                com.edunext.edutrack.common.pagination.Cursor.decode(page.meta().nextCursor());
        assertThat(Instant.parse(decoded.sortKey())).isEqualTo(ENTERED);
        assertThat(decoded.id()).isEqualTo(2L);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private void given(Ticket... rows) {
        givenAll(List.of(rows));
    }

    private void givenAll(List<Ticket> rows) {
        when(tickets.queuePage(any(), any(), any())).thenReturn(new PageImpl<>(rows, Pageable.unpaged(), rows.size()));
    }

    private static Ticket ticket(long id, String stage) {
        Ticket t = new Ticket();
        t.setId(id);
        t.setTicketCode("CRM-26-0000" + id);
        t.setTitle("Fee total ignores the discount");
        t.setProjectId(8L);
        t.setWorkflowTemplateId(TEMPLATE);
        t.setStatus("IN_PROGRESS");
        t.setLevel("MEDIUM");
        t.setOriginalLevel("MEDIUM");
        t.setCurrentStage(stage);
        t.setCurrentCycleNo((short) 1);
        t.setCurrentIteration((short) 1);
        t.setStageEnteredAt(ENTERED);
        return t;
    }

    private static WorkflowStage stageWithSla(BigDecimal slaHours) {
        WorkflowStage s = new WorkflowStage();
        ReflectionTestUtils.setField(s, "stageCode", "QA");
        s.setSlaHours(slaHours);
        return s;
    }
}
