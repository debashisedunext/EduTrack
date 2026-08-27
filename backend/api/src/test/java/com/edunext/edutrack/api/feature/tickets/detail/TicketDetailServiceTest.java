package com.edunext.edutrack.api.feature.tickets.detail;

import com.edunext.edutrack.api.feature.tickets.links.TicketLinkService;
import com.edunext.edutrack.api.feature.transitions.RibbonAssembler;
import com.edunext.edutrack.api.security.dev.DevPrincipal;
import com.edunext.edutrack.api.security.scope.ScopedTickets;
import com.edunext.edutrack.domain.clients.ClientRepository;
import com.edunext.edutrack.domain.identity.ProjectRepository;
import com.edunext.edutrack.domain.journal.TicketJournal;
import com.edunext.edutrack.domain.tickets.Ticket;
import com.edunext.edutrack.domain.tickets.TicketAttachmentRepository;
import com.edunext.edutrack.domain.tickets.TicketCommentRepository;
import com.edunext.edutrack.domain.tickets.TicketCycleRepository;
import com.edunext.edutrack.domain.tickets.TicketWatcherRepository;
import com.edunext.edutrack.domain.identity.UserRepository;
import com.edunext.edutrack.domain.workflow.WorkflowStage;
import com.edunext.edutrack.domain.workflow.WorkflowStageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * C-043 · {@code availableActions} on {@code GET /tickets/{id}/full} — the
 * golden rule's other consumer, per {@link TicketDetailService}'s own class
 * javadoc. Everything but the golden rule question is stubbed to its
 * Mockito default (empty collections), since this class exists to pin one
 * thing: {@code handoff}/{@code rework} appear if and only if
 * {@code StageOwnership.mayAdvance} would say yes, on a live ticket.
 */
class TicketDetailServiceTest {

    private static final long TICKET_ID = 347L;
    private static final String TICKET_CODE = "CRM-26-00347";
    private static final long PROJECT = 8L;
    private static final long CURRENT_ASSIGNEE = 55L;

    private final ScopedTickets tickets = mock(ScopedTickets.class);
    private final TicketCycleRepository cycles = mock(TicketCycleRepository.class);
    private final TicketJournal journal = mock(TicketJournal.class);
    private final TicketCommentRepository comments = mock(TicketCommentRepository.class);
    private final TicketAttachmentRepository attachments = mock(TicketAttachmentRepository.class);
    private final TicketWatcherRepository watchers = mock(TicketWatcherRepository.class);
    private final TicketLinkService links = mock(TicketLinkService.class);
    private final WorkflowStageRepository stages = mock(WorkflowStageRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final ClientRepository clients = mock(ClientRepository.class);
    private final RibbonAssembler ribbon = mock(RibbonAssembler.class);

    private final TicketDetailService service =
            new TicketDetailService(tickets, cycles, journal, comments, attachments, watchers, links, stages, users,
                    projects, clients, ribbon);

    private Ticket ticket;

    @BeforeEach
    void setUp() {
        ticket = new Ticket();
        ticket.setId(TICKET_ID);
        ticket.setTicketCode("CRM-26-00347");
        ticket.setProjectId(PROJECT);
        ticket.setStatus("IN_PROGRESS");
        ticket.setCurrentStage("DEV");
        ticket.setCurrentCycleNo((short) 1);
        ticket.setAssignedTo(CURRENT_ASSIGNEE);

        when(tickets.requireByCode(any(), eq(TICKET_CODE))).thenReturn(ticket);
    }

    @Nested
    @DisplayName("handoff/rework — gated on the golden rule")
    class GoldenRuleGate {

        @Test
        @DisplayName("the current assignee sees handoff and rework")
        void assigneeSeesBothActions() {
            List<String> actions = detailFor(caller(CURRENT_ASSIGNEE, "DEVELOPER")).availableActions();

            assertThat(actions).containsExactlyInAnyOrder("handoff", "rework");
        }

        @Test
        @DisplayName("a Developer who is not the assignee sees neither")
        void otherDeveloperSeesNothing() {
            List<String> actions = detailFor(caller(CURRENT_ASSIGNEE + 1, "DEVELOPER")).availableActions();

            assertThat(actions).isEmpty();
        }

        @Test
        @DisplayName("PM sees both, plus skip-stage — the ticket carries no template, so C-047's own "
                + "unchecked-precedent applies")
        void pmSeesBothRegardlessOfAssignment() {
            List<String> actions = detailFor(caller(CURRENT_ASSIGNEE + 1, "PM")).availableActions();

            assertThat(actions).containsExactlyInAnyOrder("handoff", "rework", "skip-stage");
        }

        @Test
        @DisplayName("Admin sees both, plus skip-stage, regardless of assignment")
        void adminSeesBothRegardlessOfAssignment() {
            List<String> actions = detailFor(caller(CURRENT_ASSIGNEE + 1, "ADMIN")).availableActions();

            assertThat(actions).containsExactlyInAnyOrder("handoff", "rework", "skip-stage");
        }

        @Test
        @DisplayName("a closed ticket offers neither, even to its own assignee")
        void closedTicketOffersNothing() {
            ticket.setStatus("CLOSED");

            List<String> actions = detailFor(caller(CURRENT_ASSIGNEE, "DEVELOPER")).availableActions();

            assertThat(actions).isEmpty();
        }

        @Test
        @DisplayName("an unidentifiable caller sees nothing, not everything")
        void unidentifiableCallerSeesNothing() {
            Authentication anonymous = new TestingAuthenticationToken(null, null);

            List<String> actions = service.detail(anonymous, TICKET_CODE, null).availableActions();

            assertThat(actions).isEmpty();
        }

        private TicketDetailDtos.Detail detailFor(Authentication caller) {
            return service.detail(caller, TICKET_CODE, null);
        }

        private Authentication caller(long userId, String role) {
            return new TestingAuthenticationToken(
                    new DevPrincipal(userId, "u" + userId, "User " + userId, role, List.of(PROJECT), List.of()),
                    "n/a", "ticket.handoff");
        }
    }

    /**
     * C-047 · {@code skip-stage} is offered only to Admin/PM, and only when
     * the ticket's current stage is one its template actually marks
     * {@code is_optional} — the same rule {@code SkipService.requireSkippable}
     * enforces server-side; this class exists so the button and the route
     * never disagree about it.
     */
    @Nested
    @DisplayName("skip-stage — Admin/PM only, and only on a stage the template marks optional")
    class SkipStageGate {

        private static final long TEMPLATE = 3L;

        @Test
        @DisplayName("a Developer never sees it, even on an optional stage")
        void developerNeverSeesIt() {
            ticket.setWorkflowTemplateId(TEMPLATE);
            when(stages.findByTemplateIdAndStageCode(TEMPLATE, "DEV"))
                    .thenReturn(Optional.of(stage("DEV", true)));

            List<String> actions = detailFor(caller(CURRENT_ASSIGNEE, "DEVELOPER")).availableActions();

            assertThat(actions).doesNotContain("skip-stage");
        }

        @Test
        @DisplayName("PM sees it when the current stage is marked optional")
        void pmSeesItOnAnOptionalStage() {
            ticket.setWorkflowTemplateId(TEMPLATE);
            when(stages.findByTemplateIdAndStageCode(TEMPLATE, "DEV"))
                    .thenReturn(Optional.of(stage("DEV", true)));

            List<String> actions = detailFor(caller(CURRENT_ASSIGNEE + 1, "PM")).availableActions();

            assertThat(actions).contains("skip-stage");
        }

        @Test
        @DisplayName("Admin does not see it when the current stage is not marked optional")
        void adminDoesNotSeeItOnAMandatoryStage() {
            ticket.setWorkflowTemplateId(TEMPLATE);
            when(stages.findByTemplateIdAndStageCode(TEMPLATE, "DEV"))
                    .thenReturn(Optional.of(stage("DEV", false)));

            List<String> actions = detailFor(caller(CURRENT_ASSIGNEE + 1, "ADMIN")).availableActions();

            assertThat(actions).containsExactlyInAnyOrder("handoff", "rework").doesNotContain("skip-stage");
        }

        @Test
        @DisplayName("a ticket with no template is treated as skippable-unchecked, ReworkService's own precedent")
        void noTemplateIsUnchecked() {
            List<String> actions = detailFor(caller(CURRENT_ASSIGNEE + 1, "PM")).availableActions();

            assertThat(actions).contains("skip-stage");
        }

        @Test
        @DisplayName("a current stage that is not on the ticket's own template is unchecked, the same precedent")
        void unknownCurrentStageIsUnchecked() {
            ticket.setWorkflowTemplateId(TEMPLATE);
            when(stages.findByTemplateIdAndStageCode(TEMPLATE, "DEV")).thenReturn(Optional.empty());

            List<String> actions = detailFor(caller(CURRENT_ASSIGNEE + 1, "ADMIN")).availableActions();

            assertThat(actions).contains("skip-stage");
        }

        @Test
        @DisplayName("a closed ticket offers it to nobody, same as handoff/rework — reopen is all that survives")
        void closedTicketOffersNoStageAction() {
            ticket.setStatus("CLOSED");

            List<String> actions = detailFor(caller(CURRENT_ASSIGNEE + 1, "ADMIN")).availableActions();

            assertThat(actions).containsExactly("reopen");
        }

        private TicketDetailDtos.Detail detailFor(Authentication caller) {
            return service.detail(caller, TICKET_CODE, null);
        }

        private Authentication caller(long userId, String role) {
            return new TestingAuthenticationToken(
                    new DevPrincipal(userId, "u" + userId, "User " + userId, role, List.of(PROJECT), List.of()),
                    "n/a", "ticket.skip_stage");
        }

        private WorkflowStage stage(String code, boolean optional) {
            WorkflowStage stage = new WorkflowStage();
            stage.setStageCode(code);
            stage.setDisplayName(code);
            stage.setOptional(optional);
            return stage;
        }
    }

    /**
     * The closing hop — §4A.1's last segment, owned by SUPPORT since
     * {@code V20260826_1520}.
     *
     * <p>The rule these pin is one sentence: <b>the PM signs off by handing
     * the ticket to the desk, and the desk closes it.</b> Everything below is
     * a consequence of that. {@code handoff} leaves the terminal stage because
     * there is nowhere left to hand to; {@code close} appears in its place,
     * for the owner rather than for whoever signed off; and once the ticket is
     * CLOSED nothing on the ribbon applies to it at all, which is the "no Hand
     * off button on a closed ticket" half of the same rule.
     */
    @Nested
    @DisplayName("the closing hop — Support closes the terminal stage, not the PM who signed off")
    class ClosingHop {

        private static final long TEMPLATE = 3L;
        private static final long SIGNING_PM = CURRENT_ASSIGNEE + 900L;

        @BeforeEach
        void standOnTheTerminalStage() {
            ticket.setWorkflowTemplateId(TEMPLATE);
            ticket.setCurrentStage("CLOSED");
            ticket.setStatus("RESOLVED");
            when(stages.findByTemplateIdOrderBySeqAsc(TEMPLATE))
                    .thenReturn(List.of(stage("SIGNOFF"), stage("CLOSED")));
        }

        @Test
        @DisplayName("the Support owner sees close, and no handoff — the stage has nowhere to hand to")
        void supportOwnerSeesClose() {
            List<String> actions = detailFor(caller(CURRENT_ASSIGNEE, "SUPPORT")).availableActions();

            assertThat(actions).contains("close").doesNotContain("handoff");
        }

        @Test
        @DisplayName("the PM sees close and reopen too — §2 grants all three roles, and the button must agree "
                + "with the route")
        void pmSeesBothOptions() {
            List<String> actions = detailFor(caller(SIGNING_PM, "PM")).availableActions();

            assertThat(actions).contains("close", "reopen").doesNotContain("handoff");
        }

        @Test
        @DisplayName("Admin still sees close regardless of who holds it — the override every rule here admits")
        void adminSeesCloseRegardlessOfAssignment() {
            List<String> actions = detailFor(caller(SIGNING_PM, "ADMIN")).availableActions();

            assertThat(actions).contains("close");
        }

        @Test
        @DisplayName("close is withheld until the sign-off has made the ticket RESOLVED — CloseService would 422 it")
        void closeWithheldUntilResolved() {
            ticket.setStatus("IN_PROGRESS");

            List<String> actions = detailFor(caller(CURRENT_ASSIGNEE, "SUPPORT")).availableActions();

            assertThat(actions).doesNotContain("close", "handoff");
        }

        @Test
        @DisplayName("a Developer holding the terminal stage sees neither close nor reopen — §2 ticks three roles")
        void developerNeverSeesClose() {
            List<String> actions = detailFor(caller(CURRENT_ASSIGNEE, "DEVELOPER")).availableActions();

            assertThat(actions).doesNotContain("close", "reopen", "handoff");
        }

        @Test
        @DisplayName("close and reopen are offered together — a decision with one button is not one")
        void bothOptionsAreOfferedTogether() {
            List<String> actions = detailFor(caller(CURRENT_ASSIGNEE, "SUPPORT")).availableActions();

            assertThat(actions).contains("close", "reopen");
        }

        @Test
        @DisplayName("an Admin who holds nothing on this ticket sees both — assignment is not the rule")
        void unassignedAdminSeesBothOptions() {
            List<String> actions = detailFor(caller(SIGNING_PM, "ADMIN")).availableActions();

            assertThat(actions).contains("close", "reopen");
        }

        @Test
        @DisplayName("reopen is withheld alongside close until the sign-off resolves the ticket")
        void reopenWithheldUntilResolved() {
            ticket.setStatus("IN_PROGRESS");

            List<String> actions = detailFor(caller(CURRENT_ASSIGNEE, "SUPPORT")).availableActions();

            assertThat(actions).doesNotContain("reopen");
        }

        @Test
        @DisplayName("once closed the Support owner sees reopen and nothing else — no Hand off survives a close")
        void closedOffersReopenOnly() {
            ticket.setStatus("CLOSED");

            List<String> actions = detailFor(caller(CURRENT_ASSIGNEE, "SUPPORT")).availableActions();

            assertThat(actions).containsExactly("reopen");
        }

        @Test
        @DisplayName("a closed ticket stays reopenable by a PM who never held it — the case §2 grants it for")
        void closedTicketReopenableByAnyGrantedRole() {
            ticket.setStatus("CLOSED");

            assertThat(detailFor(caller(SIGNING_PM, "PM")).availableActions()).containsExactly("reopen");
            assertThat(detailFor(caller(SIGNING_PM, "SUPPORT")).availableActions()).containsExactly("reopen");
            assertThat(detailFor(caller(SIGNING_PM, "ADMIN")).availableActions()).containsExactly("reopen");
        }

        @Test
        @DisplayName("a stage before the terminal one is untouched — handoff and rework, as C-043 left them")
        void earlierStageStillHandsOff() {
            ticket.setCurrentStage("SIGNOFF");
            ticket.setStatus("IN_PROGRESS");

            List<String> actions = detailFor(caller(CURRENT_ASSIGNEE, "DEVELOPER")).availableActions();

            assertThat(actions).containsExactlyInAnyOrder("handoff", "rework");
        }

        private TicketDetailDtos.Detail detailFor(Authentication caller) {
            return service.detail(caller, TICKET_CODE, null);
        }

        private Authentication caller(long userId, String role) {
            return new TestingAuthenticationToken(
                    new DevPrincipal(userId, "u" + userId, "User " + userId, role, List.of(PROJECT), List.of()),
                    "n/a", "ticket.close");
        }

        private WorkflowStage stage(String code) {
            WorkflowStage stage = new WorkflowStage();
            stage.setStageCode(code);
            stage.setDisplayName(code);
            return stage;
        }
    }
}
