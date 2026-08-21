package com.edunext.edutrack.api.feature.transitions;

import com.edunext.edutrack.api.security.dev.DevPrincipal;
import com.edunext.edutrack.api.security.scope.ScopedTickets;
import com.edunext.edutrack.domain.tickets.Ticket;
import com.edunext.edutrack.domain.workflow.WorkflowStage;
import com.edunext.edutrack.domain.workflow.WorkflowStageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C-047 · what {@link SkipService#skip} adds on top of
 * {@link TransitionService#advance} itself (which {@code TransitionServiceTest}
 * covers): fixing the action code to {@code SKIP}, refusing a stage the
 * template does not mark optional, defaulting the destination, and folding the
 * authoriser into the stored reason — {@code ForceMoveServiceTest}'s own shape,
 * one route over.
 */
class SkipServiceTest {

    private static final long TICKET = 512L;
    private static final long ACTOR = 12L;
    private static final long ASSIGNEE = 55L;
    private static final String TICKET_CODE = "CRM-26-00512";
    private static final long TEMPLATE = 3L;

    private final ScopedTickets tickets = mock(ScopedTickets.class);
    private final TransitionService transitionService = mock(TransitionService.class);
    private final WorkflowStageRepository stages = mock(WorkflowStageRepository.class);
    private final TransitionUserRefs people = mock(TransitionUserRefs.class);
    private final RibbonAssembler ribbon = mock(RibbonAssembler.class);

    private final SkipService service = new SkipService(tickets, transitionService, stages, people, ribbon);

    private final Authentication pm = new TestingAuthenticationToken(
            new DevPrincipal(ACTOR, "priya", "Priya Nair", "PM", List.of(8L), List.of()),
            "n/a", "ticket.skip_stage");

    private Ticket ticket;

    @BeforeEach
    void setUp() {
        ticket = ticket();
        when(tickets.requireByCode(any(), eq(TICKET_CODE))).thenReturn(ticket);
        when(stages.findByTemplateIdAndStageCode(TEMPLATE, "QA")).thenReturn(Optional.of(stage("QA", "QA", true)));
        when(transitionService.nextStageAfter(any(), anyString())).thenReturn("DEPLOY");
        when(ribbon.assembleCurrentCycle(any(), anyBoolean())).thenReturn(
                new RibbonWire.Ribbon(1, 1, false, "DEPLOY", true, List.of()));
    }

    @Nested
    @DisplayName("the action code is always SKIP, and the stage skipped is the one the ticket is in")
    class ActionCode {

        @Test
        @DisplayName("advance is called with SKIP")
        void alwaysSkip() {
            service.skip(pm, TICKET_CODE, request("Client waived UAT for this release", null));

            assertThat(captured().actionCode()).isEqualTo("SKIP");
        }

        @Test
        @DisplayName("no assignee and no handoff note are sent — a skip reassigns nobody explicitly")
        void noAssigneeOrNote() {
            service.skip(pm, TICKET_CODE, request("Client waived UAT for this release", null));

            assertThat(captured().assigneeId()).isNull();
            assertThat(captured().handoffNote()).isNull();
        }
    }

    @Nested
    @DisplayName("the destination")
    class Destination {

        @Test
        @DisplayName("defaults to the template's next stage after the one being skipped")
        void defaultsToNextStage() {
            service.skip(pm, TICKET_CODE, request("Nothing to verify on a copy change", null));

            verify(transitionService).nextStageAfter(ticket, "QA");
            assertThat(captured().toStageCode()).isEqualTo("DEPLOY");
        }

        @Test
        @DisplayName("an explicit toStageCode wins and the template is never consulted for a default")
        void explicitStageWins() {
            service.skip(pm, TICKET_CODE, request("Straight to sign-off, client already accepted", "SIGNOFF"));

            verify(transitionService, never()).nextStageAfter(any(), anyString());
            assertThat(captured().toStageCode()).isEqualTo("SIGNOFF");
        }

        @Test
        @DisplayName("a blank toStageCode falls back to the default rather than reaching advance")
        void blankFallsBack() {
            service.skip(pm, TICKET_CODE, request("Nothing to verify", "   "));

            assertThat(captured().toStageCode()).isEqualTo("DEPLOY");
        }
    }

    @Nested
    @DisplayName("§4A.6 — only a stage the template marks optional may be skipped")
    class OptionalStageRule {

        @Test
        @DisplayName("a stage that is not optional is refused, before the ticket moves")
        void refusesMandatoryStage() {
            when(stages.findByTemplateIdAndStageCode(TEMPLATE, "QA"))
                    .thenReturn(Optional.of(stage("QA", "Quality Assurance", false)));

            assertThatThrownBy(() -> service.skip(pm, TICKET_CODE, request("we are behind schedule", null)))
                    .isInstanceOf(StageNotOptionalException.class)
                    .hasMessageContaining("Quality Assurance")
                    .hasMessageContaining("QA");

            verify(transitionService, never()).advance(any(), any(Long.class), any());
            verify(ribbon, never()).assembleCurrentCycle(any(), anyBoolean());
        }

        @Test
        @DisplayName("refused before the destination is resolved — 'cannot be skipped' beats 'no next stage'")
        void refusesBeforeResolvingDestination() {
            when(stages.findByTemplateIdAndStageCode(TEMPLATE, "QA"))
                    .thenReturn(Optional.of(stage("QA", "Quality Assurance", false)));

            assertThatThrownBy(() -> service.skip(pm, TICKET_CODE, request("we are behind schedule", null)))
                    .isInstanceOf(StageNotOptionalException.class);

            verify(transitionService, never()).nextStageAfter(any(), anyString());
        }

        @Test
        @DisplayName("a ticket with no workflow template is not checked — ReworkService's own precedent")
        void noTemplateIsNotChecked() {
            ticket.setWorkflowTemplateId(null);

            service.skip(pm, TICKET_CODE, request("legacy ticket, no template to consult", "DEPLOY"));

            assertThat(captured().actionCode()).isEqualTo("SKIP");
        }

        @Test
        @DisplayName("a current stage that is not on the ticket's own template is not diagnosed here")
        void unknownCurrentStageIsNotChecked() {
            when(stages.findByTemplateIdAndStageCode(TEMPLATE, "QA")).thenReturn(Optional.empty());

            service.skip(pm, TICKET_CODE, request("stage predates the template", "DEPLOY"));

            assertThat(captured().actionCode()).isEqualTo("SKIP");
        }
    }

    @Nested
    @DisplayName("§4A.3 — the hover shows the reason and who authorised it")
    class Authoriser {

        @Test
        @DisplayName("the authoriser's name is appended to the stored reason")
        void appendsAuthoriser() {
            when(people.resolve(List.of(ACTOR)))
                    .thenReturn(Map.of(ACTOR, new RibbonWire.UserRef(ACTOR, "Priya Nair")));

            service.skip(pm, TICKET_CODE, request("Client waived UAT for this release", null));

            assertThat(captured().reason())
                    .isEqualTo("Client waived UAT for this release\n\nSkipped by: Priya Nair");
        }

        @Test
        @DisplayName("the reason is trimmed before the authoriser is appended")
        void trimsReason() {
            when(people.resolve(List.of(ACTOR)))
                    .thenReturn(Map.of(ACTOR, new RibbonWire.UserRef(ACTOR, "Priya Nair")));

            service.skip(pm, TICKET_CODE, request("  Client waived UAT  ", null));

            assertThat(captured().reason()).isEqualTo("Client waived UAT\n\nSkipped by: Priya Nair");
        }

        @Test
        @DisplayName("a caller whose users row cannot be resolved leaves the reason exactly as sent")
        void unresolvableCallerAppendsNothing() {
            when(people.resolve(List.of(ACTOR))).thenReturn(Map.of());

            service.skip(pm, TICKET_CODE, request("Client waived UAT", null));

            assertThat(captured().reason()).isEqualTo("Client waived UAT");
        }
    }

    @Nested
    @DisplayName("a refusal inside advance propagates, unassembled")
    class AdvanceRefuses {

        @Test
        @DisplayName("advance's exception propagates and the ribbon is never assembled")
        void propagates() {
            when(transitionService.advance(any(), eq(TICKET), any()))
                    .thenThrow(new NotCurrentStageOwnerException(TICKET, ASSIGNEE));

            assertThatThrownBy(() -> service.skip(pm, TICKET_CODE, request("not my ticket to skip", null)))
                    .isInstanceOf(NotCurrentStageOwnerException.class);

            verify(ribbon, never()).assembleCurrentCycle(any(), anyBoolean());
        }
    }

    @Nested
    @DisplayName("the response wraps RibbonAssembler's current-cycle view")
    class ResponseShape {

        @Test
        @DisplayName("canAdvance is resolved for the calling PM, not read off the request")
        void assemblesWithResolvedCanAdvance() {
            SkipDtos.RibbonResponse response =
                    service.skip(pm, TICKET_CODE, request("Client waived UAT", null));

            verify(ribbon).assembleCurrentCycle(eq(ticket), eq(true));
            assertThat(response.data().currentStageCode()).isEqualTo("DEPLOY");
        }
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private TransitionDtos.TransitionRequest captured() {
        ArgumentCaptor<TransitionDtos.TransitionRequest> captor =
                ArgumentCaptor.forClass(TransitionDtos.TransitionRequest.class);
        verify(transitionService).advance(any(), eq(TICKET), captor.capture());
        return captor.getValue();
    }

    private static SkipDtos.SkipRequest request(String reason, String toStageCode) {
        return new SkipDtos.SkipRequest(reason, toStageCode);
    }

    private static WorkflowStage stage(String code, String displayName, boolean optional) {
        WorkflowStage stage = new WorkflowStage();
        stage.setStageCode(code);
        stage.setDisplayName(displayName);
        stage.setOptional(optional);
        return stage;
    }

    private Ticket ticket() {
        Ticket t = new Ticket();
        t.setId(TICKET);
        t.setTicketCode(TICKET_CODE);
        t.setProjectId(8L);
        t.setWorkflowTemplateId(TEMPLATE);
        t.setStatus("IN_PROGRESS");
        t.setAssignedTo(ASSIGNEE);
        t.setCurrentCycleNo((short) 1);
        t.setCurrentIteration((short) 1);
        t.setCurrentStage("QA");
        return t;
    }
}
