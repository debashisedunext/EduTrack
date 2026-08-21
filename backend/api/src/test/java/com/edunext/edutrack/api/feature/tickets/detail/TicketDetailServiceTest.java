package com.edunext.edutrack.api.feature.tickets.detail;

import com.edunext.edutrack.api.feature.tickets.links.TicketLinkService;
import com.edunext.edutrack.api.security.dev.DevPrincipal;
import com.edunext.edutrack.api.security.scope.ScopedTickets;
import com.edunext.edutrack.domain.journal.TicketJournal;
import com.edunext.edutrack.domain.tickets.Ticket;
import com.edunext.edutrack.domain.tickets.TicketAttachmentRepository;
import com.edunext.edutrack.domain.tickets.TicketCommentRepository;
import com.edunext.edutrack.domain.tickets.TicketCycleRepository;
import com.edunext.edutrack.domain.tickets.TicketWatcherRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.List;

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
    /** The route is addressed by code, not by row id — see TicketDetailIT.Binding. */
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

    private final TicketDetailService service =
            new TicketDetailService(tickets, cycles, journal, comments, attachments, watchers, links);

    private Ticket ticket;

    @BeforeEach
    void setUp() {
        ticket = new Ticket();
        ticket.setId(TICKET_ID);
        ticket.setTicketCode(TICKET_CODE);
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
        @DisplayName("PM sees both even when not the assignee")
        void pmSeesBothRegardlessOfAssignment() {
            List<String> actions = detailFor(caller(CURRENT_ASSIGNEE + 1, "PM")).availableActions();

            assertThat(actions).containsExactlyInAnyOrder("handoff", "rework");
        }

        @Test
        @DisplayName("Admin sees both even when not the assignee")
        void adminSeesBothRegardlessOfAssignment() {
            List<String> actions = detailFor(caller(CURRENT_ASSIGNEE + 1, "ADMIN")).availableActions();

            assertThat(actions).containsExactlyInAnyOrder("handoff", "rework");
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
}
