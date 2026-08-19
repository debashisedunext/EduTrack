package com.edunext.edutrack.api.feature.tickets.bulkreassign;

import com.edunext.edutrack.api.security.dev.DevPrincipal;
import com.edunext.edutrack.api.security.scope.ScopedTickets;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * C-063 · what {@code POST /tickets/bulk-reassign} owes S-17's grid selection
 * and S-24's wizard, which share this one service.
 *
 * <p>A unit test with mocked collaborators, on {@link PriorityChangeServiceTest}'s
 * argument: none of what is asserted here is a database behaviour, and
 * {@code TicketJournal}'s own guarantees are proved elsewhere. What matters is
 * the entry handed to it, per ticket, and that one ticket's outcome cannot
 * affect another's.
 */
class BulkReassignServiceTest {

    private static final long TICKET_A_ROW = 4711L;
    private static final long TICKET_B_ROW = 4712L;
    private static final String TICKET_A = "CRM-26-00347";
    private static final String TICKET_B = "CRM-26-00348";
    private static final long ACTOR = 88L;
    private static final long PREVIOUS_ASSIGNEE = 44L;
    private static final long TARGET = 51L;
    private static final String REASON = "Priya is on extended leave from Monday.";

    private final ScopedTickets tickets = mock(ScopedTickets.class);
    private final UserRepository users = mock(UserRepository.class);
    private final TicketJournal journal = mock(TicketJournal.class);

    /**
     * A real {@link org.springframework.transaction.support.TransactionTemplate}
     * wraps this, exactly as production does — {@code AttachmentScanTaskTest}'s
     * own note on the identical setup: what matters is that the template's
     * {@code execute} actually runs the callback under a (fake) transaction,
     * which {@link SimpleTransactionStatus} is enough to satisfy.
     */
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

    private final BulkReassignService service =
            new BulkReassignService(tickets, users, journal, transactionManager);

    private final Authentication caller = new TestingAuthenticationToken(
            new DevPrincipal(ACTOR, "priya", "Priya Nair", "PM", List.of(12L), List.of()),
            "n/a", "ticket.assign");

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(users.existsById(TARGET)).thenReturn(true);
        when(tickets.byCode(any(), eq(TICKET_A))).thenReturn(Optional.of(ticket(TICKET_A_ROW, TICKET_A, PREVIOUS_ASSIGNEE)));
    }

    // ── the target ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("toUserId")
    class Target {

        /**
         * A whole-request refusal, not a per-ticket one — see the service javadoc.
         * Nothing about any named ticket is consulted first.
         */
        @Test
        @DisplayName("a target that names nobody refuses the whole request before any ticket is touched")
        void unknownTargetRefusesEverything() {
            when(users.existsById(999L)).thenReturn(false);

            assertThatThrownBy(() -> service.reassign(caller, request(List.of(TICKET_A), 999L, REASON)))
                    .isInstanceOf(UnknownUserException.class);

            verifyNoInteractions(tickets);
            verifyNoInteractions(journal);
        }
    }

    // ── the ordinary reassignment ────────────────────────────────────────────

    @Nested
    @DisplayName("an ordinary reassignment")
    class Ordinary {

        @Test
        @DisplayName("moves assignedTo and reports success")
        void reassigns() {
            var result = service.reassign(caller, request(List.of(TICKET_A), TARGET, REASON));

            assertThat(result.succeeded()).isEqualTo(1);
            assertThat(result.failed()).isEqualTo(0);
            assertThat(result.results()).containsExactly(
                    new BulkReassignDtos.TicketOutcome(TICKET_A, true, null));
        }

        @Test
        @DisplayName("writes one REASSIGNED history row on field assignedTo, old to new")
        void writesHistory() {
            service.reassign(caller, request(List.of(TICKET_A), TARGET, REASON));

            TicketHistory entry = appendedEntry();
            assertThat(entry.getTicketId()).isEqualTo(TICKET_A_ROW);
            assertThat(entry.getEventType()).isEqualTo("REASSIGNED");
            assertThat(entry.getFieldName()).isEqualTo("assignedTo");
            assertThat(entry.getOldValue()).isEqualTo(String.valueOf(PREVIOUS_ASSIGNEE));
            assertThat(entry.getNewValue()).isEqualTo(String.valueOf(TARGET));
            assertThat(entry.getRemarks()).isEqualTo(REASON);
            assertThat(entry.getActorId()).isEqualTo(ACTOR);
            assertThat(entry.getActorType()).isEqualTo("USER");
        }

        @Test
        @DisplayName("reaches the history entry with the reason trimmed")
        void trimsTheReason() {
            service.reassign(caller, request(List.of(TICKET_A), TARGET, "  " + REASON + "  "));

            assertThat(appendedEntry().getRemarks()).isEqualTo(REASON);
        }

        @Test
        @DisplayName("stamps the ticket's current cycle, not cycle 1 unconditionally")
        void stampsTheCurrentCycle() {
            Ticket ticket = ticket(TICKET_A_ROW, TICKET_A, PREVIOUS_ASSIGNEE);
            ticket.setCurrentCycleNo((short) 2);
            when(tickets.byCode(any(), eq(TICKET_A))).thenReturn(Optional.of(ticket));

            service.reassign(caller, request(List.of(TICKET_A), TARGET, REASON));

            assertThat(appendedEntry().getCycleNo()).isEqualTo((short) 2);
        }

        @Test
        @DisplayName("an unassigned ticket's old value is null, not the string \"null\"")
        void unassignedTicketHasNullOldValue() {
            Ticket ticket = ticket(TICKET_A_ROW, TICKET_A, null);
            when(tickets.byCode(any(), eq(TICKET_A))).thenReturn(Optional.of(ticket));

            service.reassign(caller, request(List.of(TICKET_A), TARGET, REASON));

            assertThat(appendedEntry().getOldValue()).isNull();
        }
    }

    // ── the no-op ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("a ticket already assigned to the target")
    class AlreadyThere {

        /**
         * Success, not a refusal — a source resource's ticket list can go stale
         * between the wizard's step 2 and its confirm, and a ticket somebody else
         * already moved to the same target is not something to surface as failed.
         * No history row either: nothing happened, and a REASSIGNED row saying
         * "51 → 51" cannot be deleted once written.
         */
        @Test
        @DisplayName("is reported as succeeded and writes no history row")
        void isANoOp() {
            Ticket ticket = ticket(TICKET_A_ROW, TICKET_A, TARGET);
            when(tickets.byCode(any(), eq(TICKET_A))).thenReturn(Optional.of(ticket));

            var result = service.reassign(caller, request(List.of(TICKET_A), TARGET, REASON));

            assertThat(result.succeeded()).isEqualTo(1);
            assertThat(result.results()).containsExactly(
                    new BulkReassignDtos.TicketOutcome(TICKET_A, true, null));
            verifyNoInteractions(journal);
        }
    }

    // ── row scope ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("a ticket outside the caller's scope, or that does not exist")
    class OutOfScope {

        /**
         * A-035: the two are indistinguishable by design, and this route's own
         * contract text names the exact string a PM sees for either.
         */
        @Test
        @DisplayName("is reported as refused with 'Not found or out of scope', not thrown")
        void isRefusedNotThrown() {
            when(tickets.byCode(any(), eq(TICKET_A))).thenReturn(Optional.empty());

            var result = service.reassign(caller, request(List.of(TICKET_A), TARGET, REASON));

            assertThat(result.succeeded()).isEqualTo(0);
            assertThat(result.failed()).isEqualTo(1);
            assertThat(result.results()).containsExactly(
                    new BulkReassignDtos.TicketOutcome(TICKET_A, false, "Not found or out of scope"));
            verifyNoInteractions(journal);
        }
    }

    // ── the batch ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("a batch of several tickets")
    class Batch {

        @Test
        @DisplayName("one ticket refusing does not affect another ticket's outcome")
        void oneRefusalDoesNotAffectAnother() {
            when(tickets.byCode(any(), eq(TICKET_B)))
                    .thenReturn(Optional.of(ticket(TICKET_B_ROW, TICKET_B, PREVIOUS_ASSIGNEE)));
            when(tickets.byCode(any(), eq(TICKET_A))).thenReturn(Optional.empty());

            var result = service.reassign(caller, request(List.of(TICKET_A, TICKET_B), TARGET, REASON));

            assertThat(result.succeeded()).isEqualTo(1);
            assertThat(result.failed()).isEqualTo(1);
            assertThat(result.results()).extracting(BulkReassignDtos.TicketOutcome::ticketId)
                    .containsExactly(TICKET_A, TICKET_B);
        }

        /**
         * Each ticket's write is its own transaction — {@code TicketJournal.append}
         * is {@code Propagation.MANDATORY} and takes a per-ticket lock, so a
         * request-long transaction sharing all of them would hold every lock at
         * once and roll every ticket back the moment one refused. Asserted the
         * only way it is observable from here: one call to the transaction
         * manager per ticket, not one for the whole batch.
         */
        @Test
        @DisplayName("opens one transaction per ticket, not one for the whole request")
        void oneTransactionPerTicket() {
            when(tickets.byCode(any(), eq(TICKET_B)))
                    .thenReturn(Optional.of(ticket(TICKET_B_ROW, TICKET_B, PREVIOUS_ASSIGNEE)));

            service.reassign(caller, request(List.of(TICKET_A, TICKET_B), TARGET, REASON));

            verify(transactionManager, times(2)).getTransaction(any());
        }

        /**
         * Duplicate ids collapse before anything runs, {@code ResourceService.setStatus}'s
         * rule for its own selection — a grid or a wizard step that ticked the same
         * row twice gets one outcome for it, not two contradicting ones.
         */
        @Test
        @DisplayName("collapses duplicate ticket ids to one outcome")
        void collapsesDuplicates() {
            var result = service.reassign(caller, request(List.of(TICKET_A, TICKET_A), TARGET, REASON));

            assertThat(result.succeeded()).isEqualTo(1);
            assertThat(result.results()).hasSize(1);
            verify(journal, times(1)).append(any(TicketHistory.class));
        }
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private TicketHistory appendedEntry() {
        ArgumentCaptor<TicketHistory> captor = ArgumentCaptor.forClass(TicketHistory.class);
        verify(journal).append(captor.capture());
        return captor.getValue();
    }

    private static BulkReassignDtos.BulkReassignRequest request(List<String> ticketIds, long toUserId, String reason) {
        return new BulkReassignDtos.BulkReassignRequest(ticketIds, toUserId, reason);
    }

    private static Ticket ticket(long rowId, String code, Long assignedTo) {
        Ticket t = new Ticket();
        ReflectionTestUtils.setField(t, "id", rowId);
        ReflectionTestUtils.setField(t, "ticketCode", code);
        t.setProjectId(12L);
        t.setTitle("A ticket somebody needs to hand off");
        t.setLevel("MEDIUM");
        t.setOriginalLevel("MEDIUM");
        t.setStatus("IN_PROGRESS");
        t.setAssignedTo(assignedTo);
        t.setCurrentCycleNo((short) 1);
        t.setCurrentIteration((short) 1);
        t.setCurrentStage("DEVELOPMENT");
        return t;
    }
}
