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
        @DisplayName("a closed ticket offers it to nobody, same as handoff/rework")
        void closedTicketOffersNothing() {
            ticket.setStatus("CLOSED");

            List<String> actions = detailFor(caller(CURRENT_ASSIGNEE + 1, "ADMIN")).availableActions();

            assertThat(actions).isEmpty();
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
}
