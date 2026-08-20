package com.edunext.edutrack.api.feature.transitions;

import com.edunext.edutrack.api.security.dev.DevPrincipal;
import com.edunext.edutrack.api.security.scope.ScopedTickets;
import com.edunext.edutrack.domain.journal.TicketJournal;
import com.edunext.edutrack.domain.masters.WorkingHoursService;
import com.edunext.edutrack.domain.tickets.Ticket;
import com.edunext.edutrack.domain.tickets.TicketHistory;
import com.edunext.edutrack.domain.workflow.TicketStageTransition;
import com.edunext.edutrack.domain.workflow.WorkflowStage;
import com.edunext.edutrack.domain.workflow.WorkflowStageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C-042 · what {@link TransitionService#advance} guarantees about numbering,
 * sealing and the accompanying history row — proved against mocks, on
 * {@code ReopenServiceTest}'s own precedent for the sibling lifecycle write.
 */
class TransitionServiceTest {

    private static final long TICKET = 347L;
    private static final long PROJECT = 8L;
    private static final long TEMPLATE = 3L;
    private static final long ACTOR = 12L;
    private static final long OPEN_HOP = 900L;
    private static final long CURRENT_ASSIGNEE = 55L;

    private static final Instant ENTERED = Instant.parse("2026-08-17T09:00:00Z");
    private static final Instant NOW = Instant.parse("2026-08-17T13:30:00Z");

    private final ScopedTickets tickets = mock(ScopedTickets.class);
    private final TicketJournal journal = mock(TicketJournal.class);
    private final WorkflowStageRepository stages = mock(WorkflowStageRepository.class);
    private final WorkingHoursService workingHours = mock(WorkingHoursService.class);
    private final ReceivingRoleRepository receivingRoles = mock(ReceivingRoleRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    private final TransitionService service = new TransitionService(
            tickets, journal, stages, workingHours, receivingRoles, events,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private final Authentication caller = new TestingAuthenticationToken(
            new DevPrincipal(ACTOR, "priya", "Priya Nair", "PM", List.of(PROJECT), List.of()),
            "n/a", "ticket.handoff");

    private Ticket ticket;
    private TicketStageTransition openHop;

    @BeforeEach
    void setUp() {
        ticket = ticket();
        openHop = openHop("DEV");

        when(tickets.require(any(), eq(TICKET))).thenReturn(ticket);
        when(journal.openHopFor(TICKET)).thenReturn(Optional.of(openHop));
        when(journal.append(any(TicketStageTransition.class)))
                .thenAnswer(call -> call.getArgument(0));
        when(journal.append(any(TicketHistory.class)))
                .thenAnswer(call -> call.getArgument(0));
        when(stages.findByTemplateIdAndStageCode(eq(TEMPLATE), any()))
                .thenAnswer(call -> Optional.of(stage(call.getArgument(1))));
        when(stages.findByTemplateIdOrderBySeqAsc(TEMPLATE))
                .thenReturn(List.of(stage("TRIAGE"), stage("DEV"), stage("QA"), stage("DEPLOY")));
        when(workingHours.workingHoursBetween(any(), any(), any(), any()))
                .thenReturn(new BigDecimal("4.50"));
    }

    // ── numbering ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("numbering")
    class Numbering {

        @Test
        @DisplayName("FORWARD keeps iterationNo and does not touch reworkCount")
        void forwardKeepsIteration() {
            openHop.setIterationNo((short) 2);
            openHop.setSeqNo(5);
            ticket.setReworkCount((short) 1);

            service.advance(caller, TICKET, request("FORWARD", "QA", null));

            TicketStageTransition inserted = capturedHop();
            assertThat(inserted.getIterationNo()).isEqualTo((short) 2);
            assertThat(inserted.getSeqNo()).isEqualTo(6);
            assertThat(inserted.getCycleNo()).isEqualTo(ticket.getCurrentCycleNo());
            assertThat(ticket.getReworkCount()).isEqualTo((short) 1);
            assertThat(ticket.getCurrentIteration()).isEqualTo((short) 2);
        }

        @Test
        @DisplayName("REWORK increments iterationNo and reworkCount; keeps cycleNo")
        void reworkIncrementsIteration() {
            openHop.setIterationNo((short) 2);
            ticket.setReworkCount((short) 1);
            ticket.setCurrentCycleNo((short) 3);
            openHop.setCycleNo((short) 3);

            service.advance(caller, TICKET, request("REWORK", "TRIAGE", "build failed on staging"));

            TicketStageTransition inserted = capturedHop();
            assertThat(inserted.getIterationNo()).isEqualTo((short) 3);
            assertThat(inserted.getCycleNo()).isEqualTo((short) 3);
            assertThat(ticket.getReworkCount()).isEqualTo((short) 2);
            assertThat(ticket.getCurrentIteration()).isEqualTo((short) 3);
        }

        @Test
        @DisplayName("DEPLOY_FAILED, VERIFY_FAILED and SIGNOFF_REJECTED are backward too")
        void everyBackwardCodeIncrements() {
            for (String code : List.of("DEPLOY_FAILED", "VERIFY_FAILED", "SIGNOFF_REJECTED")) {
                openHop = openHop("DEV");
                when(journal.openHopFor(TICKET)).thenReturn(Optional.of(openHop));
                ticket.setReworkCount((short) 0);
                ticket.setCurrentIteration((short) 1);

                service.advance(caller, TICKET, request(code, "TRIAGE", "reason: " + code));

                assertThat(ticket.getReworkCount())
                        .describedAs(code).isEqualTo((short) 1);
            }
        }
    }

    // ── sealing ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("sealing the hop being left")
    class Sealing {

        @Test
        @DisplayName("seals the open hop with working minutes, before the next is appended")
        void sealsBeforeAppending() {
            when(workingHours.workingHoursBetween(ENTERED, NOW, PROJECT, CURRENT_ASSIGNEE))
                    .thenReturn(new BigDecimal("4.50"));

            service.advance(caller, TICKET, request("FORWARD", "QA", null));

            verify(journal).seal(OPEN_HOP, NOW, 270);
        }

        @Test
        @DisplayName("clamps working minutes to the wall-clock ceiling")
        void clampsToWallClock() {
            // 4h30m elapsed = 270 wall-clock minutes; a working-hours figure that
            // rounds up past that (double rounding) must not reach seal() as 271.
            when(workingHours.workingHoursBetween(any(), any(), any(), any()))
                    .thenReturn(new BigDecimal("4.51"));

            service.advance(caller, TICKET, request("FORWARD", "QA", null));

            verify(journal).seal(eq(OPEN_HOP), eq(NOW), eq(270));
        }
    }

    // ── the new hop ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("the inserted hop")
    class InsertedHop {

        @Test
        @DisplayName("carries fromStage/fromUserId from the sealed hop")
        void carriesFromFields() {
            service.advance(caller, TICKET, request("FORWARD", "QA", null));

            TicketStageTransition inserted = capturedHop();
            assertThat(inserted.getFromStage()).isEqualTo("DEV");
            assertThat(inserted.getFromUserId()).isEqualTo(CURRENT_ASSIGNEE);
            assertThat(inserted.getToStage()).isEqualTo("QA");
            assertThat(inserted.getActionCode()).isEqualTo("FORWARD");
            assertThat(inserted.getEnteredAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("FORWARD with no toStageCode defaults to the template's next stage")
        void forwardDefaultsNextStage() {
            service.advance(caller, TICKET, request("FORWARD", null, null));

            assertThat(capturedHop().getToStage()).isEqualTo("QA");
        }

        @Test
        @DisplayName("an explicit assigneeId reassigns the ticket and becomes toUserId")
        void explicitAssignee() {
            service.advance(caller, TICKET, new TransitionDtos.TransitionRequest(
                    "FORWARD", "QA", 99L, null, null));

            assertThat(capturedHop().getToUserId()).isEqualTo(99L);
            assertThat(ticket.getAssignedTo()).isEqualTo(99L);
            assertThat(ticket.getAssignedBy()).isEqualTo(ACTOR);
        }

        @Test
        @DisplayName("no assigneeId keeps the ticket's current assignee as toUserId")
        void defaultAssignee() {
            service.advance(caller, TICKET, request("FORWARD", "QA", null));

            assertThat(capturedHop().getToUserId()).isEqualTo(CURRENT_ASSIGNEE);
            assertThat(ticket.getAssignedTo()).isEqualTo(CURRENT_ASSIGNEE);
        }

        @Test
        @DisplayName("also appends a STAGE_CHANGED history entry")
        void appendsHistory() {
            service.advance(caller, TICKET, request("REWORK", "TRIAGE", "failed QA"));

            ArgumentCaptor<TicketHistory> captor = ArgumentCaptor.forClass(TicketHistory.class);
            verify(journal).append(captor.capture());
            TicketHistory entry = captor.getValue();
            assertThat(entry.getTicketId()).isEqualTo(TICKET);
            assertThat(entry.getEventType()).isEqualTo("STAGE_CHANGED");
            assertThat(entry.getOldValue()).isEqualTo("DEV");
            assertThat(entry.getNewValue()).isEqualTo("TRIAGE");
            assertThat(entry.getActorId()).isEqualTo(ACTOR);
            assertThat(entry.getActorType()).isEqualTo("USER");
            assertThat(entry.getRemarks()).isEqualTo("failed QA");
        }
    }

    // ── the project queue — C-050 ───────────────────────────────────────────

    @Nested
    @DisplayName("the receiving role has nobody on the project — C-050")
    class ProjectQueue {

        // Stage code and role code are deliberately different strings here
        // ("DEPLOY" vs "DEPLOYMENT") — with C-011/C-051's fixtures they can
        // coincide (a "QA" stage owned by role "QA"), which would let a test
        // pass even if the production code passed the stage code where the
        // role code belongs. A mutation test caught exactly that gap.

        @Test
        @DisplayName("no assigneeId and no active member of the receiving role: falls unassigned")
        void fallsToTheQueue() {
            // assignedBy starts non-null so "left untouched" is a real
            // assertion below, not a coincidence of the fixture's default.
            ticket.setAssignedBy(ACTOR);
            when(stages.findByTemplateIdAndStageCode(TEMPLATE, "DEPLOY"))
                    .thenReturn(Optional.of(stage("DEPLOY", "DEPLOYMENT")));
            when(receivingRoles.hasActiveMember(PROJECT, "DEPLOYMENT")).thenReturn(false);

            service.advance(caller, TICKET, request("FORWARD", "DEPLOY", null));

            assertThat(capturedHop().getToUserId()).isNull();
            assertThat(ticket.getAssignedTo()).isNull();
            // Nobody chose this — the queue did. assignedBy still names the
            // last person who made a real assignment, not this transition's
            // caller, and not null either.
            assertThat(ticket.getAssignedBy()).isEqualTo(ACTOR);
        }

        @Test
        @DisplayName("no assigneeId but the receiving role has an active member: keeps the outgoing owner")
        void keepsOwnerWhenRoleIsStaffed() {
            when(stages.findByTemplateIdAndStageCode(TEMPLATE, "DEPLOY"))
                    .thenReturn(Optional.of(stage("DEPLOY", "DEPLOYMENT")));
            when(receivingRoles.hasActiveMember(PROJECT, "DEPLOYMENT")).thenReturn(true);

            service.advance(caller, TICKET, request("FORWARD", "DEPLOY", null));

            assertThat(capturedHop().getToUserId()).isEqualTo(CURRENT_ASSIGNEE);
            assertThat(ticket.getAssignedTo()).isEqualTo(CURRENT_ASSIGNEE);
        }

        @Test
        @DisplayName("an explicit assigneeId wins with no membership lookup at all")
        void explicitAssigneeSkipsTheCheck() {
            when(stages.findByTemplateIdAndStageCode(TEMPLATE, "DEPLOY"))
                    .thenReturn(Optional.of(stage("DEPLOY", "DEPLOYMENT")));

            service.advance(caller, TICKET, new TransitionDtos.TransitionRequest(
                    "FORWARD", "DEPLOY", 99L, null, null));

            assertThat(capturedHop().getToUserId()).isEqualTo(99L);
            assertThat(ticket.getAssignedTo()).isEqualTo(99L);
            verify(receivingRoles, never()).hasActiveMember(anyLong(), any());
        }

        @Test
        @DisplayName("a ticket with no workflow template cannot resolve a role, so the owner carries forward")
        void noTemplateKeepsOwner() {
            ReflectionTestUtils.setField(ticket, "workflowTemplateId", null);

            service.advance(caller, TICKET, request("FORWARD", "QA", null));

            assertThat(ticket.getAssignedTo()).isEqualTo(CURRENT_ASSIGNEE);
            verify(receivingRoles, never()).hasActiveMember(anyLong(), any());
    // ── C-045: the live-update event ────────────────────────────────────────

    @Nested
    @DisplayName("the live-update event")
    class LiveUpdateEvent {

        @Test
        @DisplayName("raises TicketStageAdvanced with the ticket, project and both stages")
        void raisesEvent() {
            service.advance(caller, TICKET, request("FORWARD", "QA", null));

            ArgumentCaptor<TicketStageAdvanced> captor = ArgumentCaptor.forClass(TicketStageAdvanced.class);
            verify(events).publishEvent(captor.capture());
            TicketStageAdvanced published = captor.getValue();
            assertThat(published.ticketId()).isEqualTo(TICKET);
            assertThat(published.projectId()).isEqualTo(PROJECT);
            assertThat(published.fromStage()).isEqualTo("DEV");
            assertThat(published.toStage()).isEqualTo("QA");
        }

        @Test
        @DisplayName("a refused advance raises no event")
        void refusalRaisesNoEvent() {
            assertThatThrownBy(() -> service.advance(caller, TICKET, request("EXPLODE", "QA", null)))
                    .isInstanceOf(UnknownActionCodeException.class);

            verify(events, never()).publishEvent(any());
        }
    }

    // ── the golden rule ──────────────────────────────────────────────────────

    /**
     * C-043 · {@code CURRENT_ASSIGNEE} is 55L throughout this fixture, so
     * these tests build their own caller rather than reusing the class-level
     * {@code caller} field (a PM, always allowed, which is why the 17 tests
     * above never had to know this rule existed).
     */
    @Nested
    @DisplayName("the golden rule — C-043")
    class GoldenRule {

        @Test
        @DisplayName("the current assignee, a Developer, may advance")
        void currentAssigneeMayAdvance() {
            Authentication assignee = devCaller(CURRENT_ASSIGNEE, "DEVELOPER");

            service.advance(assignee, TICKET, request("FORWARD", "QA", null));

            verify(journal).seal(any(), any(), any());
        }

        @Test
        @DisplayName("a Developer who is not the assignee is refused, even holding ticket.handoff")
        void otherDeveloperIsRefused() {
            Authentication otherDeveloper = devCaller(CURRENT_ASSIGNEE + 1, "DEVELOPER");

            assertThatThrownBy(() -> service.advance(otherDeveloper, TICKET, request("FORWARD", "QA", null)))
                    .isInstanceOf(NotCurrentStageOwnerException.class);

            verify(journal, never()).seal(any(), any(), any());
            verify(journal, never()).append(any(TicketStageTransition.class));
        }

        @Test
        @DisplayName("QA sitting with the ticket cannot push it into Deployment when Dev owns the stage")
        void qaCannotAdvanceADevTicket() {
            Authentication qa = devCaller(CURRENT_ASSIGNEE + 1, "QA");

            assertThatThrownBy(() -> service.advance(qa, TICKET, request("FORWARD", "DEPLOY", null)))
                    .isInstanceOf(NotCurrentStageOwnerException.class);
        }

        @Test
        @DisplayName("PM may advance a ticket assigned to someone else")
        void pmMayAdvanceRegardlessOfAssignment() {
            Authentication pm = devCaller(CURRENT_ASSIGNEE + 1, "PM");

            service.advance(pm, TICKET, request("FORWARD", "QA", null));

            verify(journal).seal(any(), any(), any());
        }

        @Test
        @DisplayName("Admin may advance a ticket assigned to someone else")
        void adminMayAdvanceRegardlessOfAssignment() {
            Authentication admin = devCaller(CURRENT_ASSIGNEE + 1, "ADMIN");

            service.advance(admin, TICKET, request("FORWARD", "QA", null));

            verify(journal).seal(any(), any(), any());
        }

        @Test
        @DisplayName("an unidentifiable caller is refused, not defaulted to allowed")
        void unidentifiableCallerIsRefused() {
            Authentication anonymous = new TestingAuthenticationToken(null, null);
            anonymous.setAuthenticated(false);

            assertThatThrownBy(() -> service.advance(anonymous, TICKET, request("FORWARD", "QA", null)))
                    .isInstanceOf(NotCurrentStageOwnerException.class);
        }

        @Test
        @DisplayName("refused before the action code or open hop is even read")
        void checkedBeforeActionCodeValidation() {
            Authentication otherDeveloper = devCaller(CURRENT_ASSIGNEE + 1, "DEVELOPER");

            // An action code the service would otherwise reject with
            // UnknownActionCodeException — the golden rule must win first.
            assertThatThrownBy(() -> service.advance(otherDeveloper, TICKET, request("EXPLODE", "QA", null)))
                    .isInstanceOf(NotCurrentStageOwnerException.class);
        }

        private Authentication devCaller(long userId, String role) {
            return new TestingAuthenticationToken(
                    new DevPrincipal(userId, "u" + userId, "User " + userId, role, List.of(PROJECT), List.of()),
                    "n/a", "ticket.handoff");
        }
    }

    // ── refusals ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("refusals")
    class Refusals {

        @Test
        @DisplayName("unknown action_code is refused before anything is read or written")
        void unknownActionCode() {
            assertThatThrownBy(() -> service.advance(caller, TICKET, request("EXPLODE", "QA", null)))
                    .isInstanceOf(UnknownActionCodeException.class);

            verify(journal, never()).seal(any(), any(), any());
            verify(journal, never()).append(any(TicketStageTransition.class));
        }

        @Test
        @DisplayName("no open hop on the ticket's current cycle is refused")
        void noOpenHop() {
            when(journal.openHopFor(TICKET)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.advance(caller, TICKET, request("FORWARD", "QA", null)))
                    .isInstanceOf(NoOpenStageException.class);
        }

        @Test
        @DisplayName("an open hop from an earlier, already-reopened cycle is refused, not sealed")
        void openHopFromWrongCycle() {
            openHop.setCycleNo((short) 1);
            ticket.setCurrentCycleNo((short) 2);

            assertThatThrownBy(() -> service.advance(caller, TICKET, request("FORWARD", "QA", null)))
                    .isInstanceOf(NoOpenStageException.class);
            verify(journal, never()).seal(any(), any(), any());
        }

        @Test
        @DisplayName("REWORK with no toStageCode cannot default one")
        void reworkNeedsExplicitStage() {
            assertThatThrownBy(() -> service.advance(caller, TICKET, request("REWORK", null, "reason")))
                    .isInstanceOf(ToStageRequiredException.class);
        }

        @Test
        @DisplayName("FORWARD past the template's last stage cannot default one")
        void forwardPastLastStage() {
            openHop.setToStage("DEPLOY");
            when(stages.findByTemplateIdOrderBySeqAsc(TEMPLATE))
                    .thenReturn(List.of(stage("TRIAGE"), stage("DEV"), stage("QA"), stage("DEPLOY")));

            assertThatThrownBy(() -> service.advance(caller, TICKET, request("FORWARD", null, null)))
                    .isInstanceOf(NoNextStageException.class);
        }

        @Test
        @DisplayName("FORWARD with no workflow template cannot default a next stage")
        void forwardWithNoTemplate() {
            ReflectionTestUtils.setField(ticket, "workflowTemplateId", null);

            assertThatThrownBy(() -> service.advance(caller, TICKET, request("FORWARD", null, null)))
                    .isInstanceOf(NoNextStageException.class);
        }

        @Test
        @DisplayName("a toStageCode outside the ticket's template is refused")
        void unknownStage() {
            when(stages.findByTemplateIdAndStageCode(TEMPLATE, "RELEASE")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.advance(caller, TICKET, request("FORWARD", "RELEASE", null)))
                    .isInstanceOf(UnknownTransitionStageException.class);
        }
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private TicketStageTransition capturedHop() {
        ArgumentCaptor<TicketStageTransition> captor = ArgumentCaptor.forClass(TicketStageTransition.class);
        verify(journal).append(captor.capture());
        return captor.getValue();
    }

    private static TransitionDtos.TransitionRequest request(String actionCode, String toStageCode, String reason) {
        return new TransitionDtos.TransitionRequest(actionCode, toStageCode, null, null, reason);
    }

    private Ticket ticket() {
        Ticket t = new Ticket();
        t.setId(TICKET);
        t.setTicketCode("CRM-26-00347");
        t.setWorkflowTemplateId(TEMPLATE);
        t.setProjectId(PROJECT);
        t.setTitle("Payment webhook retries exhaust before the gateway settles");
        t.setStatus("IN_PROGRESS");
        t.setLevel("HIGH");
        t.setOriginalLevel("HIGH");
        t.setAssignedTo(CURRENT_ASSIGNEE);
        t.setCurrentCycleNo((short) 1);
        t.setCurrentIteration((short) 1);
        t.setCurrentStage("DEV");
        return t;
    }

    private TicketStageTransition openHop(String toStage) {
        TicketStageTransition hop = new TicketStageTransition();
        hop.setId(OPEN_HOP);
        hop.setTicketId(TICKET);
        hop.setCycleNo((short) 1);
        hop.setIterationNo((short) 1);
        hop.setSeqNo(3);
        hop.setToStage(toStage);
        hop.setToUserId(CURRENT_ASSIGNEE);
        hop.setActionCode("FORWARD");
        hop.setEnteredAt(ENTERED);
        return hop;
    }

    private static WorkflowStage stage(String code) {
        return stage(code, null);
    }

    private static WorkflowStage stage(String code, String ownerRole) {
        WorkflowStage s = new WorkflowStage();
        ReflectionTestUtils.setField(s, "stageCode", code);
        ReflectionTestUtils.setField(s, "ownerRole", ownerRole);
        return s;
    }
}
