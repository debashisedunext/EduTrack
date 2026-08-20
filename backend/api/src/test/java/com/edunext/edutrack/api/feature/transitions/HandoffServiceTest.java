package com.edunext.edutrack.api.feature.transitions;

import com.edunext.edutrack.api.security.dev.DevPrincipal;
import com.edunext.edutrack.api.security.scope.ScopedTickets;
import com.edunext.edutrack.domain.journal.TicketJournal;
import com.edunext.edutrack.domain.tickets.Ticket;
import com.edunext.edutrack.domain.tickets.TicketCycle;
import com.edunext.edutrack.domain.tickets.TicketCycleRepository;
import com.edunext.edutrack.domain.tickets.TicketEffortLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C-044 · what {@link HandoffService#handoff} guarantees beyond
 * {@link TransitionService#advance} itself (which {@code TransitionServiceTest}
 * already covers): the mandatory effort confirmation, captured against the
 * stage being left rather than the one the ticket lands in, and that it never
 * reaches the ledger for a caller {@code advance} was going to refuse anyway.
 */
class HandoffServiceTest {

    private static final long TICKET = 347L;
    private static final long PROJECT = 8L;
    private static final long ACTOR = 12L;
    private static final long OTHER_ASSIGNEE = 55L;
    private static final String TICKET_CODE = "CRM-26-00347";

    private final ScopedTickets tickets = mock(ScopedTickets.class);
    private final TransitionService transitionService = mock(TransitionService.class);
    private final TicketJournal journal = mock(TicketJournal.class);
    private final TicketCycleRepository cycles = mock(TicketCycleRepository.class);
    private final RibbonAssembler ribbon = mock(RibbonAssembler.class);
    private final HandoffNotifier notifier = mock(HandoffNotifier.class);

    private final HandoffService service =
            new HandoffService(tickets, transitionService, journal, cycles, ribbon, notifier);

    private final Authentication pm = new TestingAuthenticationToken(
            new DevPrincipal(ACTOR, "priya", "Priya Nair", "PM", List.of(PROJECT), List.of()),
            "n/a", "ticket.handoff");

    private Ticket ticket;

    @BeforeEach
    void setUp() {
        ticket = ticket();
        when(tickets.requireByCode(any(), eq(TICKET_CODE))).thenReturn(ticket);
        when(journal.append(any(TicketEffortLog.class))).thenAnswer(call -> {
            TicketEffortLog entry = call.getArgument(0);
            entry.setId(9001L);
            return entry;
        });
        when(cycles.findByTicketIdAndCycleNo(eq(TICKET), anyShort())).thenReturn(Optional.of(cycle()));
        when(ribbon.assembleCurrentCycle(any(), anyBoolean())).thenReturn(
                new RibbonWire.Ribbon(1, 1, false, "QA", true, List.of()));
    }

    @Nested
    @DisplayName("effortHours mandatory — G-1's default")
    class EffortMandatory {

        @Test
        @DisplayName("null effortHours is refused before advance or the effort ledger is touched")
        void nullIsRefused() {
            assertThatThrownBy(() -> service.handoff(pm, TICKET_CODE, request("QA", 99L, null)))
                    .isInstanceOf(EffortHoursRequiredException.class);

            verify(transitionService, never()).advance(any(), anyLong(), any());
            verify(journal, never()).append(any(TicketEffortLog.class));
        }

        @Test
        @DisplayName("zero hours advances the ticket but logs nothing — ck_effort_hours rejects a zero row")
        void zeroLogsNothing() {
            service.handoff(pm, TICKET_CODE, request("QA", 99L, BigDecimal.ZERO));

            verify(transitionService).advance(any(), eq(TICKET), any());
            verify(journal, never()).append(any(TicketEffortLog.class));
        }

        @Test
        @DisplayName("positive hours advances and logs")
        void positiveLogsOne() {
            service.handoff(pm, TICKET_CODE, request("QA", 99L, new BigDecimal("2.50")));

            verify(transitionService).advance(any(), eq(TICKET), any());
            verify(journal).append(any(TicketEffortLog.class));
        }
    }

    @Nested
    @DisplayName("effort is captured against the stage being left")
    class CapturedBeforeAdvance {

        @Test
        @DisplayName("stamped with the pre-advance stage/cycle/iteration, even though advance mutates the ticket")
        void stampedBeforeMutation() {
            // Simulate what the real TransitionService.advance does: move the
            // ticket on. If HandoffService read the ticket's stage AFTER this
            // call instead of before, the effort would land in "QA", not "DEV".
            when(transitionService.advance(any(), eq(TICKET), any())).thenAnswer(call -> {
                ticket.setCurrentStage("QA");
                ticket.setCurrentCycleNo((short) 2);
                ticket.setCurrentIteration((short) 3);
                return null;
            });

            service.handoff(pm, TICKET_CODE, request("QA", 99L, new BigDecimal("2.50")));

            ArgumentCaptor<TicketEffortLog> captor = ArgumentCaptor.forClass(TicketEffortLog.class);
            verify(journal).append(captor.capture());
            TicketEffortLog logged = captor.getValue();
            assertThat(logged.getStageCode()).isEqualTo("DEV");
            assertThat(logged.getCycleNo()).isEqualTo((short) 1);
            assertThat(logged.getIterationNo()).isEqualTo((short) 1);
            assertThat(logged.getHours()).isEqualByComparingTo("2.50");
        }

        @Test
        @DisplayName("attributed to the caller confirming it, not the ticket's assignee")
        void attributedToCaller() {
            service.handoff(pm, TICKET_CODE, request("QA", 99L, new BigDecimal("1.00")));

            ArgumentCaptor<TicketEffortLog> captor = ArgumentCaptor.forClass(TicketEffortLog.class);
            verify(journal).append(captor.capture());
            // pm is ACTOR (12), the ticket's assignee is OTHER_ASSIGNEE (55).
            assertThat(captor.getValue().getUserId()).isEqualTo(ACTOR);
        }

        @Test
        @DisplayName("refreshes the cycle and ticket running totals")
        void refreshesTotals() {
            TicketCycle cycle = cycle();
            when(cycles.findByTicketIdAndCycleNo(eq(TICKET), anyShort())).thenReturn(Optional.of(cycle));

            service.handoff(pm, TICKET_CODE, request("QA", 99L, new BigDecimal("2.00")));

            assertThat(cycle.getEffortHrs()).isEqualByComparingTo("2.00");
            assertThat(ticket.getTotalEffortHrs()).isEqualByComparingTo("2.00");
        }
    }

    @Nested
    @DisplayName("a refusal inside advance leaves nothing written")
    class AdvanceRefuses {

        @Test
        @DisplayName("advance's exception propagates and the effort ledger is never touched")
        void propagatesAndSkipsLedger() {
            when(transitionService.advance(any(), eq(TICKET), any()))
                    .thenThrow(new NotCurrentStageOwnerException(TICKET, OTHER_ASSIGNEE));

            assertThatThrownBy(() -> service.handoff(pm, TICKET_CODE, request("QA", 99L, new BigDecimal("2.00"))))
                    .isInstanceOf(NotCurrentStageOwnerException.class);

            verify(journal, never()).append(any(TicketEffortLog.class));
            verify(notifier, never()).received(any(), anyLong(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("C-045: the receiving owner is notified")
    class ReceivingOwnerNotified {

        @Test
        @DisplayName("notified after advance succeeds, with the caller, the new owner and the destination stage")
        void notifiesAfterAdvance() {
            service.handoff(pm, TICKET_CODE, request("QA", 99L, new BigDecimal("2.00")));

            // "DEV" is the stage being left, captured before advance moved the
            // ticket on — D-037 needs it to tell a deployment hop from any
            // other one, and it is the same value the effort entry is stamped with.
            verify(notifier).received(ticket, 99L, ACTOR, "QA", "DEV");
        }

        @Test
        @DisplayName("a zero-hour handoff still notifies — the effort skip is independent")
        void notifiesEvenWithNoEffortLogged() {
            service.handoff(pm, TICKET_CODE, request("QA", 99L, BigDecimal.ZERO));

            // "DEV" is the stage being left, captured before advance moved the
            // ticket on — D-037 needs it to tell a deployment hop from any
            // other one, and it is the same value the effort entry is stamped with.
            verify(notifier).received(ticket, 99L, ACTOR, "QA", "DEV");
        }
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private static HandoffDtos.HandoffRequest request(String toStageCode, Long toUserId, BigDecimal effortHours) {
        return new HandoffDtos.HandoffRequest(toStageCode, toUserId, null, effortHours, null);
    }

    private Ticket ticket() {
        Ticket t = new Ticket();
        t.setId(TICKET);
        t.setTicketCode(TICKET_CODE);
        t.setProjectId(PROJECT);
        t.setWorkflowTemplateId(3L);
        t.setStatus("IN_PROGRESS");
        t.setAssignedTo(OTHER_ASSIGNEE);
        t.setCurrentCycleNo((short) 1);
        t.setCurrentIteration((short) 1);
        t.setCurrentStage("DEV");
        t.setTotalEffortHrs(BigDecimal.ZERO);
        return t;
    }

    private TicketCycle cycle() {
        TicketCycle c = new TicketCycle();
        c.setId(1L);
        c.setTicketId(TICKET);
        c.setCycleNo((short) 1);
        c.setEffortHrs(BigDecimal.ZERO);
        return c;
    }
}
