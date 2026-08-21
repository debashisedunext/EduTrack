package com.edunext.edutrack.api.feature.transitions;

import com.edunext.edutrack.api.feature.notifications.events.TicketEventNotifier;
import com.edunext.edutrack.api.security.dev.DevPrincipal;
import com.edunext.edutrack.api.security.scope.ScopedTickets;
import com.edunext.edutrack.domain.journal.TicketJournal;
import com.edunext.edutrack.domain.tickets.Ticket;
import com.edunext.edutrack.domain.tickets.TicketCycle;
import com.edunext.edutrack.domain.tickets.TicketCycleRepository;
import com.edunext.edutrack.domain.tickets.TicketEffortLog;
import com.edunext.edutrack.domain.workflow.WorkflowStage;
import com.edunext.edutrack.domain.workflow.WorkflowStageRepository;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C-046 · what {@link ReworkService#rework} guarantees beyond
 * {@link TransitionService#advance}, which {@code TransitionServiceTest}
 * already covers for the iteration counter, the seal-then-append order and the
 * rework count.
 *
 * <p>Four things are this route's own, and each is here because getting it
 * wrong writes a row into an append-only, hash-chained ledger that can never
 * be corrected — only compensated: the {@code can_return_to} rule, the action
 * being one of the four backward ones, the defect list surviving, and the
 * status moving to {@code REWORK}.
 */
class ReworkServiceTest {

    private static final long TICKET = 347L;
    private static final long PROJECT = 8L;
    private static final long ACTOR = 12L;
    private static final long DEVELOPER = 55L;
    private static final long TEMPLATE = 3L;
    private static final String TICKET_CODE = "CRM-26-00347";

    private final ScopedTickets tickets = mock(ScopedTickets.class);
    private final TransitionService transitionService = mock(TransitionService.class);
    private final WorkflowStageRepository stages = mock(WorkflowStageRepository.class);
    private final TicketJournal journal = mock(TicketJournal.class);
    private final TicketCycleRepository cycles = mock(TicketCycleRepository.class);
    private final RibbonAssembler ribbon = mock(RibbonAssembler.class);
    private final TicketEventNotifier notifier = mock(TicketEventNotifier.class);

    private final ReworkService service = new ReworkService(
            tickets, transitionService, stages, journal, cycles, ribbon, notifier);

    private final Authentication qa = new TestingAuthenticationToken(
            new DevPrincipal(ACTOR, "anil", "Anil Shah", "QA", List.of(PROJECT), List.of()),
            "n/a", "ticket.rework");

    private Ticket ticket;

    @BeforeEach
    void setUp() {
        ticket = ticket();
        when(tickets.requireByCode(any(), eq(TICKET_CODE))).thenReturn(ticket);
        when(stages.findByTemplateIdAndStageCode(TEMPLATE, "QA"))
                .thenReturn(Optional.of(stage("QA", "QA / Testing", List.of("DEV"))));
        when(journal.append(any(TicketEffortLog.class))).thenAnswer(call -> {
            TicketEffortLog entry = call.getArgument(0);
            entry.setId(9001L);
            return entry;
        });
        when(cycles.findByTicketIdAndCycleNo(eq(TICKET), anyShort())).thenReturn(Optional.of(cycle()));
        when(ribbon.assembleCurrentCycle(any(), anyBoolean())).thenReturn(
                new RibbonWire.Ribbon(1, 2, false, "DEV", true, List.of()));
    }

    @Nested
    @DisplayName("can_return_to — this route's rule, not advance's")
    class ReturnTargets {

        @Test
        @DisplayName("an allowed target goes through")
        void allowedTargetAdvances() {
            service.rework(qa, TICKET_CODE, request("DEV", "Payment retry still fails"));

            verify(transitionService).advance(any(), eq(TICKET), any());
        }

        @Test
        @DisplayName("a real stage that is not a return target is refused, and nothing is written")
        void disallowedTargetIsRefused() {
            // 422, not 400: TRIAGE exists, and from a different stage the same
            // request would be valid — StageMayNotReturnToException's own doc.
            assertThatThrownBy(() -> service.rework(qa, TICKET_CODE, request("TRIAGE", "Wrong way")))
                    .isInstanceOf(StageMayNotReturnToException.class)
                    .hasMessageContaining("QA / Testing")
                    .hasMessageContaining("DEV");

            verify(transitionService, never()).advance(any(), anyLong(), any());
            verify(journal, never()).append(any(TicketEffortLog.class));
        }

        @Test
        @DisplayName("a stage with no return targets at all says so, rather than printing an empty list")
        void noReturnTargetsReadsProperly() {
            when(stages.findByTemplateIdAndStageCode(TEMPLATE, "QA"))
                    .thenReturn(Optional.of(stage("QA", "QA / Testing", List.of())));

            assertThatThrownBy(() -> service.rework(qa, TICKET_CODE, request("DEV", "Back to dev")))
                    .isInstanceOf(StageMayNotReturnToException.class)
                    .hasMessageContaining("no return targets at all");
        }

        @Test
        @DisplayName("a lower-case entry in the template's JSON still permits the move")
        void canReturnToIsCaseInsensitive() {
            // can_return_to is authored JSON, not a foreign key — see
            // UnknownTransitionStageException. A lower-case entry silently
            // forbidding a legal move is the failure this guards.
            when(stages.findByTemplateIdAndStageCode(TEMPLATE, "QA"))
                    .thenReturn(Optional.of(stage("QA", "QA / Testing", List.of("dev"))));

            service.rework(qa, TICKET_CODE, request("dev", "Back to dev"));

            verify(transitionService).advance(any(), eq(TICKET), any());
        }

        @Test
        @DisplayName("a ticket on no workflow template is not checked")
        void noTemplateIsNotChecked() {
            // resolveToStage's own precedent: there is nothing to validate
            // against, and plenty of seeded tickets predate B's designer.
            ticket.setWorkflowTemplateId(null);

            service.rework(qa, TICKET_CODE, request("DEV", "Back to dev"));

            verify(transitionService).advance(any(), eq(TICKET), any());
        }
    }

    @Nested
    @DisplayName("the action is one of §4A.1's four backward moves")
    class Action {

        @Test
        @DisplayName("defaults to REWORK when the caller does not choose")
        void defaultsToRework() {
            service.rework(qa, TICKET_CODE, request("DEV", "Failed QA"));

            assertThat(transitionRequest().actionCode()).isEqualTo("REWORK");
        }

        @Test
        @DisplayName("carries the chosen backward action, upper-cased")
        void carriesTheChosenAction() {
            service.rework(qa, TICKET_CODE, new ReworkDtos.ReworkRequest(
                    "DEV", "Deploy failed", "deploy_failed", null, null, null));

            assertThat(transitionRequest().actionCode()).isEqualTo("DEPLOY_FAILED");
        }

        @Test
        @DisplayName("FORWARD is refused here, and the message says where it belongs")
        void forwardIsRefused() {
            // advance() would accept it and write a forward move into the
            // ledger as a rework, with iterationNo left alone. The ledger is
            // append-only, so that row could never be corrected.
            assertThatThrownBy(() -> service.rework(qa, TICKET_CODE, new ReworkDtos.ReworkRequest(
                    "DEV", "Onward", "FORWARD", null, null, null)))
                    .isInstanceOf(NotABackwardActionException.class)
                    .hasMessageContaining("/handoff");

            verify(transitionService, never()).advance(any(), anyLong(), any());
        }

        @Test
        @DisplayName("OVERRIDE is refused too, and points at force-move")
        void overrideIsRefused() {
            assertThatThrownBy(() -> service.rework(qa, TICKET_CODE, new ReworkDtos.ReworkRequest(
                    "DEV", "Just move it", "OVERRIDE", null, null, null)))
                    .isInstanceOf(NotABackwardActionException.class)
                    .hasMessageContaining("/force-move");
        }

        @Test
        @DisplayName("the four mirrored here are exactly the four the engine treats as backward")
        void backwardActionsHaveNotDrifted() {
            // TransitionService.BACKWARD_ACTIONS is package-private to that
            // class; this is the same arrangement it has with
            // TicketJournal.BACKWARD_ACTIONS, and the same reason.
            assertThat(ReworkService.BACKWARD_ACTIONS)
                    .containsExactlyInAnyOrder("REWORK", "DEPLOY_FAILED", "VERIFY_FAILED", "SIGNOFF_REJECTED");
        }
    }

    @Nested
    @DisplayName("the defect list survives")
    class Defects {

        @Test
        @DisplayName("is appended to the reason, where a developer will read it")
        void defectsAreAppendedToTheReason() {
            service.rework(qa, TICKET_CODE, new ReworkDtos.ReworkRequest(
                    "DEV", "QA failed", null,
                    List.of("Retry count resets on 500", "No idempotency key on the second attempt"),
                    null, null));

            assertThat(transitionRequest().reason())
                    .startsWith("QA failed")
                    .contains("Defects:")
                    .contains("• Retry count resets on 500")
                    .contains("• No idempotency key on the second attempt");
        }

        @Test
        @DisplayName("a rework with no defects stores the reason and nothing else")
        void noDefectsLeavesTheReasonAlone() {
            service.rework(qa, TICKET_CODE, request("DEV", "Needs another look"));

            assertThat(transitionRequest().reason()).isEqualTo("Needs another look");
        }

        @Test
        @DisplayName("blank rows are dropped rather than stored as empty bullets")
        void blankDefectsAreDropped() {
            service.rework(qa, TICKET_CODE, new ReworkDtos.ReworkRequest(
                    "DEV", "QA failed", null, java.util.Arrays.asList("Real one", "  ", null), null, null));

            assertThat(transitionRequest().reason()).containsOnlyOnce("•");
        }
    }

    @Nested
    @DisplayName("what the route does after advance succeeds")
    class AfterAdvance {

        @Test
        @DisplayName("status moves to REWORK — advance deliberately does not touch it")
        void statusBecomesRework() {
            service.rework(qa, TICKET_CODE, request("DEV", "Failed QA"));

            assertThat(ticket.getStatus()).isEqualTo("REWORK");
        }

        @Test
        @DisplayName("effort is optional, unlike a handoff")
        void effortIsOptional() {
            service.rework(qa, TICKET_CODE, request("DEV", "Failed QA"));

            verify(journal, never()).append(any(TicketEffortLog.class));
            verify(transitionService).advance(any(), eq(TICKET), any());
        }

        @Test
        @DisplayName("effort is stamped with the stage being left, not the one it lands in")
        void effortIsStampedWithTheLeavingStage() {
            // Simulate what the real advance does: move the ticket on. If the
            // service read the stage after this, the hours would be attributed
            // to Development, which is where the work has not happened yet.
            when(transitionService.advance(any(), eq(TICKET), any())).thenAnswer(call -> {
                ticket.setCurrentStage("DEV");
                ticket.setCurrentIteration((short) 2);
                return null;
            });

            service.rework(qa, TICKET_CODE, new ReworkDtos.ReworkRequest(
                    "DEV", "Failed QA", null, null, null, new BigDecimal("1.50")));

            ArgumentCaptor<TicketEffortLog> captor = ArgumentCaptor.forClass(TicketEffortLog.class);
            verify(journal).append(captor.capture());
            assertThat(captor.getValue().getStageCode()).isEqualTo("QA");
            assertThat(captor.getValue().getIterationNo()).isEqualTo((short) 1);
        }

        @Test
        @DisplayName("D-037 · the developer who picks it back up is told, with the defect count")
        void notifiesTheReceivingDeveloper() {
            when(transitionService.advance(any(), eq(TICKET), any())).thenAnswer(call -> {
                // advance resolves the receiving owner; the notifier must read
                // the assignee it left behind, not the QA engineer who sent it.
                ticket.setAssignedTo(DEVELOPER);
                return null;
            });

            service.rework(qa, TICKET_CODE, new ReworkDtos.ReworkRequest(
                    "DEV", "QA failed", null, List.of("One", "Two", "Three"), null, null));

            verify(notifier).sentBackForRework(ticket, ACTOR, DEVELOPER, 3);
        }

        @Test
        @DisplayName("a refused rework tells nobody and writes no effort")
        void refusalNotifiesNobody() {
            when(transitionService.advance(any(), eq(TICKET), any()))
                    .thenThrow(new NotCurrentStageOwnerException(TICKET, DEVELOPER));

            assertThatThrownBy(() -> service.rework(qa, TICKET_CODE, new ReworkDtos.ReworkRequest(
                    "DEV", "QA failed", null, null, null, new BigDecimal("2.00"))))
                    .isInstanceOf(NotCurrentStageOwnerException.class);

            verify(notifier, never()).sentBackForRework(any(), anyLong(), any(), anyInt());
            verify(journal, never()).append(any(TicketEffortLog.class));
            assertThat(ticket.getStatus()).isEqualTo("IN_PROGRESS");
        }
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private TransitionDtos.TransitionRequest transitionRequest() {
        ArgumentCaptor<TransitionDtos.TransitionRequest> captor =
                ArgumentCaptor.forClass(TransitionDtos.TransitionRequest.class);
        verify(transitionService).advance(any(), eq(TICKET), captor.capture());
        return captor.getValue();
    }

    private static ReworkDtos.ReworkRequest request(String toStageCode, String reason) {
        return new ReworkDtos.ReworkRequest(toStageCode, reason, null, null, null, null);
    }

    private static WorkflowStage stage(String code, String displayName, List<String> canReturnTo) {
        WorkflowStage stage = new WorkflowStage();
        stage.setStageCode(code);
        stage.setDisplayName(displayName);
        stage.setCanReturnTo(canReturnTo);
        return stage;
    }

    private Ticket ticket() {
        Ticket t = new Ticket();
        t.setId(TICKET);
        t.setTicketCode(TICKET_CODE);
        t.setProjectId(PROJECT);
        t.setWorkflowTemplateId(TEMPLATE);
        t.setStatus("IN_PROGRESS");
        t.setAssignedTo(ACTOR);
        t.setCurrentCycleNo((short) 1);
        t.setCurrentIteration((short) 1);
        t.setCurrentStage("QA");
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
