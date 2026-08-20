package com.edunext.edutrack.api.feature.transitions;

import com.edunext.edutrack.api.security.dev.DevPrincipal;
import com.edunext.edutrack.api.security.scope.ScopedTickets;
import com.edunext.edutrack.domain.tickets.Ticket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C-048 · what {@link ForceMoveService#forceMove} adds on top of
 * {@link TransitionService#advance} itself (which {@code TransitionServiceTest}
 * already covers): fixing the action code to {@code OVERRIDE}, requiring an
 * explicit destination on every call, and answering the same
 * {@code RibbonResponse} shape {@code HandoffService} does —
 * {@code HandoffServiceTest}'s own shape, one route thinner.
 */
class ForceMoveServiceTest {

    private static final long TICKET = 347L;
    private static final long ACTOR = 12L;
    private static final long OTHER_ASSIGNEE = 55L;
    private static final String TICKET_CODE = "CRM-26-00347";

    private final ScopedTickets tickets = mock(ScopedTickets.class);
    private final TransitionService transitionService = mock(TransitionService.class);
    private final RibbonAssembler ribbon = mock(RibbonAssembler.class);

    private final ForceMoveService service = new ForceMoveService(tickets, transitionService, ribbon);

    private final Authentication pm = new TestingAuthenticationToken(
            new DevPrincipal(ACTOR, "priya", "Priya Nair", "PM", List.of(8L), List.of()),
            "n/a", "ticket.force_move");

    private Ticket ticket;

    @BeforeEach
    void setUp() {
        ticket = ticket();
        when(tickets.requireByCode(any(), eq(TICKET_CODE))).thenReturn(ticket);
        when(ribbon.assembleCurrentCycle(any(), anyBoolean())).thenReturn(
                new RibbonWire.Ribbon(1, 1, false, "RELEASE", true, List.of()));
    }

    @Nested
    @DisplayName("the action code is always OVERRIDE, never taken from the request")
    class ActionCodeFixed {

        @Test
        @DisplayName("advance is called with OVERRIDE regardless of which stage is named")
        void alwaysOverride() {
            service.forceMove(pm, TICKET_CODE, request("RELEASE", "Deploying ahead of the QA sign-off", null));

            ArgumentCaptor<TransitionDtos.TransitionRequest> captor =
                    ArgumentCaptor.forClass(TransitionDtos.TransitionRequest.class);
            verify(transitionService).advance(eq(pm), eq(TICKET), captor.capture());
            assertThat(captor.getValue().actionCode()).isEqualTo("OVERRIDE");
        }
    }

    @Nested
    @DisplayName("the request maps straight onto TransitionService's shape")
    class RequestMapping {

        @Test
        @DisplayName("toStageCode and reason are carried through verbatim")
        void carriesStageAndReason() {
            service.forceMove(pm, TICKET_CODE, request("RELEASE", "Client escalation, PM approved", null));

            ArgumentCaptor<TransitionDtos.TransitionRequest> captor =
                    ArgumentCaptor.forClass(TransitionDtos.TransitionRequest.class);
            verify(transitionService).advance(any(), eq(TICKET), captor.capture());
            assertThat(captor.getValue().toStageCode()).isEqualTo("RELEASE");
            assertThat(captor.getValue().reason()).isEqualTo("Client escalation, PM approved");
            assertThat(captor.getValue().handoffNote()).isNull();
        }

        @Test
        @DisplayName("an explicit toUserId reassigns; null keeps the current assignee")
        void assigneeIsOptional() {
            service.forceMove(pm, TICKET_CODE, request("RELEASE", "reassigning on override", 99L));

            ArgumentCaptor<TransitionDtos.TransitionRequest> captor =
                    ArgumentCaptor.forClass(TransitionDtos.TransitionRequest.class);
            verify(transitionService).advance(any(), eq(TICKET), captor.capture());
            assertThat(captor.getValue().assigneeId()).isEqualTo(99L);
        }
    }

    @Nested
    @DisplayName("a refusal inside advance propagates, unassembled")
    class AdvanceRefuses {

        @Test
        @DisplayName("advance's exception propagates and the ribbon is never assembled")
        void propagates() {
            when(transitionService.advance(any(), eq(TICKET), any()))
                    .thenThrow(new NoOpenStageException(TICKET));

            assertThatThrownBy(() -> service.forceMove(pm, TICKET_CODE, request("RELEASE", "no open stage", null)))
                    .isInstanceOf(NoOpenStageException.class);

            verify(ribbon, never()).assembleCurrentCycle(any(), anyBoolean());
        }
    }

    @Nested
    @DisplayName("the response wraps RibbonAssembler's current-cycle view")
    class ResponseShape {

        @Test
        @DisplayName("canAdvance is resolved for the calling PM, not read off the request")
        void assemblesWithResolvedCanAdvance() {
            ForceMoveDtos.RibbonResponse response =
                    service.forceMove(pm, TICKET_CODE, request("RELEASE", "PM override", null));

            verify(ribbon).assembleCurrentCycle(eq(ticket), eq(true));
            assertThat(response.data().currentStageCode()).isEqualTo("RELEASE");
        }
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private static ForceMoveDtos.ForceMoveRequest request(String toStageCode, String reason, Long toUserId) {
        return new ForceMoveDtos.ForceMoveRequest(toStageCode, reason, toUserId);
    }

    private Ticket ticket() {
        Ticket t = new Ticket();
        t.setId(TICKET);
        t.setTicketCode(TICKET_CODE);
        t.setProjectId(8L);
        t.setWorkflowTemplateId(3L);
        t.setStatus("IN_PROGRESS");
        t.setAssignedTo(OTHER_ASSIGNEE);
        t.setCurrentCycleNo((short) 1);
        t.setCurrentIteration((short) 1);
        t.setCurrentStage("DEV");
        return t;
    }
}
