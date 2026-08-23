package com.edunext.edutrack.api.feature.tickets.assign;

import com.edunext.edutrack.api.feature.tickets.TicketWire;
import com.edunext.edutrack.api.security.dev.DevPrincipal;
import com.edunext.edutrack.api.security.scope.ScopedTickets;
import com.edunext.edutrack.api.security.scope.TicketNotFoundException;
import com.edunext.edutrack.domain.identity.User;
import com.edunext.edutrack.domain.identity.UserRepository;
import com.edunext.edutrack.domain.journal.TicketJournal;
import com.edunext.edutrack.domain.tickets.Ticket;
import com.edunext.edutrack.domain.tickets.TicketHistory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * C-049 · reassignment within a stage — blueprint §4A.6.
 *
 * <p>What matters here is entirely negative: no {@code ticket_stage_transitions}
 * row, no {@code TicketJournal.append(TicketStageTransition)} call — this class's
 * own javadoc explains why splitting effort attribution needed no write of its
 * own. So the assertions are about the one row this <em>does</em> write and the
 * two guards in front of it, on {@code PriorityChangeServiceTest}'s own shape for
 * a single-ticket lifecycle route.
 */
class AssignServiceTest {

    private static final long TICKET = 4711L;
    private static final String TICKET_CODE = "CRM-26-00347";
    private static final long PROJECT = 12L;
    private static final long ACTOR = 88L;
    private static final long PREVIOUS_ASSIGNEE = 44L;
    private static final long NEW_ASSIGNEE = 55L;

    private final ScopedTickets tickets = mock(ScopedTickets.class);
    private final UserRepository users = mock(UserRepository.class);
    private final TicketJournal journal = mock(TicketJournal.class);
    /** D-037 · §4B.6 row 3 fires from this service; nothing here asserts on it. */
    private final com.edunext.edutrack.api.feature.notifications.events.TicketEventNotifier notifier =
            mock(com.edunext.edutrack.api.feature.notifications.events.TicketEventNotifier.class);

    private final AssignService service = new AssignService(tickets, users, journal, notifier);

    /**
     * The three-argument constructor is load-bearing — {@code PriorityChangeServiceTest}'s
     * own note explains why: the two-argument form leaves {@code isAuthenticated()}
     * false, and an unauthenticated token reaches the service as nobody.
     */
    private final Authentication caller = new TestingAuthenticationToken(
            new DevPrincipal(ACTOR, "priya", "Priya Nair", "PM", List.of(PROJECT), List.of()),
            "n/a", "ticket.assign");

    private Ticket ticket;

    @BeforeEach
    void setUp() {
        ticket = assignedTicket();
        when(tickets.requireByCode(any(), eq(TICKET_CODE))).thenReturn(ticket);
        when(users.existsById(NEW_ASSIGNEE)).thenReturn(true);
        // TicketWire.of(ticket, users) now resolves assignedTo through the
        // repo on every non-exceptional return, C-0xx's UserRef fix — stubbed
        // here for both ids so every test that reaches a return gets a real
        // UserRef rather than null.
        when(users.findById(NEW_ASSIGNEE)).thenReturn(Optional.of(userNamed(NEW_ASSIGNEE, "New Assignee")));
        when(users.findById(PREVIOUS_ASSIGNEE)).thenReturn(Optional.of(userNamed(PREVIOUS_ASSIGNEE, "Previous Assignee")));
    }

    private static User userNamed(long id, String fullName) {
        User user = new User();
        user.setId(id);
        user.setFullName(fullName);
        return user;
    }

    // ── the STAGE_REASSIGNED row ──────────────────────────────────────────────

    @Nested
    @DisplayName("the history entry")
    class History {

        @Test
        @DisplayName("is STAGE_REASSIGNED on field 'assignedTo', old -> new, with the actor")
        void carriesTheChange() {
            service.assign(caller, TICKET_CODE, request(NEW_ASSIGNEE, null));

            TicketHistory entry = appendedEntry();
            assertThat(entry.getTicketId()).isEqualTo(TICKET);
            assertThat(entry.getEventType()).isEqualTo("STAGE_REASSIGNED");
            assertThat(entry.getFieldName()).isEqualTo("assignedTo");
            assertThat(entry.getOldValue()).isEqualTo(String.valueOf(PREVIOUS_ASSIGNEE));
            assertThat(entry.getNewValue()).isEqualTo(String.valueOf(NEW_ASSIGNEE));
            assertThat(entry.getActorId()).isEqualTo(ACTOR);
            assertThat(entry.getActorType()).isEqualTo("USER");
        }

        @Test
        @DisplayName("carries a null old value for a ticket's first assignment")
        void firstAssignmentHasNoOldValue() {
            ticket.setAssignedTo(null);

            service.assign(caller, TICKET_CODE, request(NEW_ASSIGNEE, null));

            assertThat(appendedEntry().getOldValue()).isNull();
        }

        @Test
        @DisplayName("carries the optional note, trimmed, as remarks")
        void carriesTheNoteTrimmed() {
            service.assign(caller, TICKET_CODE, request(NEW_ASSIGNEE, "  Covering while Ravi is on leave.  "));

            assertThat(appendedEntry().getRemarks()).isEqualTo("Covering while Ravi is on leave.");
        }

        @Test
        @DisplayName("stores a blank note as null rather than as whitespace")
        void blankNoteIsNull() {
            service.assign(caller, TICKET_CODE, request(NEW_ASSIGNEE, "   "));

            assertThat(appendedEntry().getRemarks()).isNull();
        }

        @Test
        @DisplayName("is stamped with the ticket's current cycle")
        void carriesTheCycle() {
            ticket.setCurrentCycleNo((short) 2);

            service.assign(caller, TICKET_CODE, request(NEW_ASSIGNEE, null));

            assertThat(appendedEntry().getCycleNo()).isEqualTo((short) 2);
        }
    }

    // ── what the ticket itself ends up carrying ───────────────────────────────

    @Nested
    @DisplayName("the ticket")
    class TheTicket {

        @Test
        @DisplayName("moves assignedTo and stamps assignedBy with the actor")
        void movesAssignment() {
            service.assign(caller, TICKET_CODE, request(NEW_ASSIGNEE, null));

            assertThat(ticket.getAssignedTo()).isEqualTo(NEW_ASSIGNEE);
            assertThat(ticket.getAssignedBy()).isEqualTo(ACTOR);
        }

        /**
         * The whole point of the task: nothing about the ribbon moves. There is no
         * {@code TicketStageTransition} in this test's dependency graph at all —
         * {@code TicketJournal} is mocked as one interface, and the only append this
         * class ever calls is the {@code TicketHistory} one asserted above.
         */
        @Test
        @DisplayName("reaches the wire with the new assignee and no other field disturbed")
        void isAnsweredWithTheNewAssignee() {
            TicketWire.Ticket answered = service.assign(caller, TICKET_CODE, request(NEW_ASSIGNEE, null));

            assertThat(answered.assignee().id()).isEqualTo(NEW_ASSIGNEE);
            assertThat(answered.currentStage()).isEqualTo("DEVELOPMENT");
        }
    }

    // ── the edges ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("edges")
    class Edges {

        @Test
        @DisplayName("assigning to whoever already holds it writes nothing at all")
        void sameAssigneeIsANoOp() {
            TicketWire.Ticket answered = service.assign(caller, TICKET_CODE, request(PREVIOUS_ASSIGNEE, null));

            assertThat(answered.assignee().id()).isEqualTo(PREVIOUS_ASSIGNEE);
            assertThat(ticket.getAssignedBy()).isNull();
            verifyNoInteractions(journal);
        }

        /**
         * Decided before the assignee lookup, on {@code PriorityChangeService}'s
         * own ordering for its no-op — so a re-confirm of an existing assignment
         * costs nothing even if that resource has since been deactivated.
         */
        @Test
        @DisplayName("the no-op is decided before the assignee is looked up")
        void noOpSkipsTheAssigneeLookup() {
            service.assign(caller, TICKET_CODE, request(PREVIOUS_ASSIGNEE, null));

            // TicketWire.of(ticket, users) still resolves the wire's assignee
            // through users.findById on the way out — that's the response
            // shape, not the validation this test is about. What must not
            // happen is the existsById check the assignee-lookup guard below
            // performs on a genuine reassignment.
            verify(users, never()).existsById(any());
        }

        @Test
        @DisplayName("an assignee that names nobody is refused before anything moves")
        void unknownAssigneeIsRefused() {
            when(users.existsById(999L)).thenReturn(false);

            assertThatThrownBy(() -> service.assign(caller, TICKET_CODE, request(999L, null)))
                    .isInstanceOf(UnknownAssigneeException.class);

            assertThat(ticket.getAssignedTo()).isEqualTo(PREVIOUS_ASSIGNEE);
            verifyNoInteractions(journal);
        }

        @Test
        @DisplayName("an out-of-scope ticket is 404 and nothing else is consulted")
        void outOfScopeIs404() {
            when(tickets.requireByCode(any(), eq(TICKET_CODE))).thenThrow(new TicketNotFoundException());

            assertThatThrownBy(() -> service.assign(caller, TICKET_CODE, request(NEW_ASSIGNEE, null)))
                    .isInstanceOf(TicketNotFoundException.class);

            verifyNoInteractions(users);
            verifyNoInteractions(journal);
        }
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private TicketHistory appendedEntry() {
        ArgumentCaptor<TicketHistory> captor = ArgumentCaptor.forClass(TicketHistory.class);
        verify(journal).append(captor.capture());
        return captor.getValue();
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("D-037 · §4B.6 row 3, reassigned within a stage")
    class Notification {

        @Test
        @DisplayName("tells the new assignee and the previous one, after the history row")
        void notifiesBothSides() {
            service.assign(caller, TICKET_CODE, request(NEW_ASSIGNEE, null));

            // Both ids are passed rather than read back: this runs inside the
            // service's own @Transactional, so a re-read would be querying a
            // row its own transaction has not committed.
            verify(notifier).reassignedWithinStage(ticket, ACTOR, PREVIOUS_ASSIGNEE, NEW_ASSIGNEE);
        }

        @Test
        @DisplayName("a re-confirm of the existing assignment tells nobody")
        void noOpNotifiesNobody() {
            service.assign(caller, TICKET_CODE, request(PREVIOUS_ASSIGNEE, null));

            verifyNoInteractions(notifier);
        }

        @Test
        @DisplayName("a refused assignee never reaches the notifier")
        void refusalNotifiesNobody() {
            when(users.existsById(NEW_ASSIGNEE)).thenReturn(false);

            org.assertj.core.api.Assertions
                    .assertThatThrownBy(() -> service.assign(caller, TICKET_CODE, request(NEW_ASSIGNEE, null)))
                    .isInstanceOf(UnknownAssigneeException.class);

            verifyNoInteractions(notifier);
        }
    }

    private static AssignDtos.AssignRequest request(long assigneeId, String note) {
        return new AssignDtos.AssignRequest(assigneeId, note);
    }

    private static Ticket assignedTicket() {
        Ticket t = new Ticket();
        ReflectionTestUtils.setField(t, "id", TICKET);
        ReflectionTestUtils.setField(t, "ticketCode", TICKET_CODE);
        t.setProjectId(PROJECT);
        t.setTitle("Payment gateway timeout on checkout");
        t.setTaskTypeId(4);
        t.setLevel("HIGH");
        t.setOriginalLevel("HIGH");
        t.setStatus("IN_PROGRESS");
        t.setAssignedTo(PREVIOUS_ASSIGNEE);
        t.setCurrentCycleNo((short) 1);
        t.setCurrentIteration((short) 1);
        t.setCurrentStage("DEVELOPMENT");
        return t;
    }
}
