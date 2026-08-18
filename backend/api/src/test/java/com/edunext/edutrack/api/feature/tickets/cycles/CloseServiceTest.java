package com.edunext.edutrack.api.feature.tickets.cycles;

import com.edunext.edutrack.api.feature.tickets.TicketWire;
import com.edunext.edutrack.api.security.dev.DevPrincipal;
import com.edunext.edutrack.api.security.scope.ScopedTickets;
import com.edunext.edutrack.api.security.scope.TicketNotFoundException;
import com.edunext.edutrack.domain.journal.TicketJournal;
import com.edunext.edutrack.domain.tickets.Ticket;
import com.edunext.edutrack.domain.tickets.TicketCycle;
import com.edunext.edutrack.domain.tickets.TicketCycleRepository;
import com.edunext.edutrack.domain.tickets.TicketEffortLog;
import com.edunext.edutrack.domain.tickets.TicketHistory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * C-040 · what S-23's close transaction guarantees — {@code ReopenServiceTest}'s
 * mirror image, one route over.
 */
class CloseServiceTest {

    private static final long TICKET = 347L;
    private static final String TICKET_CODE = "CRM-26-00347";
    private static final long PROJECT = 8L;
    private static final long ACTOR = 12L;
    private static final long CYCLE_ASSIGNEE = 44L;

    /** No sub-microsecond digits: what the service stores is truncated to micros. */
    private static final Instant NOW = Instant.parse("2026-08-18T09:30:00Z");

    private static final String SUMMARY = "Root cause identified and the fix deployed to production.";

    private final ScopedTickets tickets = mock(ScopedTickets.class);
    private final TicketCycleRepository cycles = mock(TicketCycleRepository.class);
    private final TicketJournal journal = mock(TicketJournal.class);

    private final CloseService service = new CloseService(
            tickets, cycles, journal, Clock.fixed(NOW, ZoneOffset.UTC));

    /**
     * The three-argument constructor is load-bearing — {@code ReopenServiceTest}'s
     * own note on the same line: the two-argument {@code TestingAuthenticationToken}
     * leaves {@code isAuthenticated()} false, and {@code CallerIdentity.of} answers
     * empty for it, so the history entry would land with a null actor for the
     * wrong reason.
     */
    private final Authentication caller = new TestingAuthenticationToken(
            new DevPrincipal(ACTOR, "priya", "Priya Nair", "PM", List.of(PROJECT), List.of()),
            "n/a", "ticket.close");

    private Ticket ticket;
    private TicketCycle cycle;

    @BeforeEach
    void setUp() {
        ticket = resolvedTicket();
        cycle = cycle();

        when(tickets.requireByCode(any(), eq(TICKET_CODE))).thenReturn(ticket);
        when(cycles.findByTicketIdAndCycleNo(TICKET, (short) 1)).thenReturn(Optional.of(cycle));
    }

    // ── the ticket row ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("the ticket")
    class TicketRow {

        @Test
        @DisplayName("moves to CLOSED with actual_close_date stamped")
        void movesToClosed() {
            TicketWire.Ticket answered = service.close(caller, TICKET_CODE, request());

            assertThat(ticket.getStatus()).isEqualTo("CLOSED");
            assertThat(ticket.getActualCloseDate()).isEqualTo(NOW);
            assertThat(answered.status()).isEqualTo("CLOSED");
        }

        @Test
        @DisplayName("stamps actual_close_date from now when the caller omits it")
        void defaultsCloseDateToNow() {
            service.close(caller, TICKET_CODE, new CloseDtos.CloseRequest(SUMMARY, null, null, null, null));

            assertThat(ticket.getActualCloseDate()).isEqualTo(NOW);
            assertThat(cycle.getActualCloseDate()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("uses the caller's actual_close_date when one is supplied")
        void usesTheCallersCloseDate() {
            Instant chosen = Instant.parse("2026-08-17T18:00:00Z");

            service.close(caller, TICKET_CODE, new CloseDtos.CloseRequest(SUMMARY, null, chosen, null, null));

            assertThat(ticket.getActualCloseDate()).isEqualTo(chosen);
            assertThat(cycle.getActualCloseDate()).isEqualTo(chosen);
        }

        @Test
        @DisplayName("keeps level and original_level — G-4 says a close does not revert them")
        void keepsBothLevels() {
            service.close(caller, TICKET_CODE, request());

            assertThat(ticket.getLevel()).isEqualTo("HIGH");
            assertThat(ticket.getOriginalLevel()).isEqualTo("MEDIUM");
        }
    }

    // ── the sealed cycle ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("the current cycle")
    class SealedCycle {

        @Test
        @DisplayName("is sealed and carries the resolution summary")
        void isSealedWithTheSummary() {
            assertThat(cycle.isSealed()).isFalse();

            service.close(caller, TICKET_CODE, request());

            assertThat(cycle.isSealed()).isTrue();
            assertThat(cycle.getResolutionSummary()).isEqualTo(SUMMARY);
        }

        @Test
        @DisplayName("carries the root cause category when supplied")
        void carriesRootCauseCategory() {
            service.close(caller, TICKET_CODE, new CloseDtos.CloseRequest(
                    SUMMARY, "Configuration drift", null, null, null));

            assertThat(cycle.getRootCauseCategory()).isEqualTo("Configuration drift");
        }

        @Test
        @DisplayName("blanks a whitespace-only root cause category to null")
        void blanksWhitespaceRootCauseCategory() {
            service.close(caller, TICKET_CODE, new CloseDtos.CloseRequest(SUMMARY, "   ", null, null, null));

            assertThat(cycle.getRootCauseCategory()).isNull();
        }

        @Test
        @DisplayName("records client_verification_requested when true")
        void recordsClientVerificationWhenTrue() {
            service.close(caller, TICKET_CODE, new CloseDtos.CloseRequest(SUMMARY, null, null, null, true));

            assertThat(cycle.isClientVerificationRequested()).isTrue();
        }

        @Test
        @DisplayName("leaves client_verification_requested false when omitted")
        void leavesClientVerificationFalseWhenOmitted() {
            service.close(caller, TICKET_CODE, request());

            assertThat(cycle.isClientVerificationRequested()).isFalse();
        }
    }

    // ── 🔴 effort is confirmed, never re-logged ──────────────────────────────

    @Nested
    @DisplayName("finalEffortHours")
    class FinalEffort {

        /**
         * Appending it as a fresh {@code ticket_effort_logs} entry would double the
         * cell it is meant to confirm — {@code ReopenService}'s own warning, in the
         * opposite direction.
         */
        @Test
        @DisplayName("is never appended as a new effort log")
        void neverAppendedAsAnEffortLog() {
            service.close(caller, TICKET_CODE, new CloseDtos.CloseRequest(
                    SUMMARY, null, null, new BigDecimal("18.50"), null));

            verify(journal, never()).append(any(TicketEffortLog.class));
            verify(journal, never()).effortFor(org.mockito.ArgumentMatchers.anyLong(), any());
        }

        @Test
        @DisplayName("is recorded as a FIELD_CHANGED history row when supplied")
        void isRecordedAsFieldChanged() {
            service.close(caller, TICKET_CODE, new CloseDtos.CloseRequest(
                    SUMMARY, null, null, new BigDecimal("18.50"), null));

            ArgumentCaptor<TicketHistory> captor = ArgumentCaptor.forClass(TicketHistory.class);
            verify(journal, times(2)).append(captor.capture());
            TicketHistory fieldChanged = captor.getAllValues().get(0);
            assertThat(fieldChanged.getEventType()).isEqualTo("FIELD_CHANGED");
            assertThat(fieldChanged.getFieldName()).isEqualTo("finalEffortHours");
            assertThat(fieldChanged.getNewValue()).isEqualTo("18.50");
        }

        @Test
        @DisplayName("writes no extra row at all when omitted")
        void noExtraRowWhenOmitted() {
            service.close(caller, TICKET_CODE, request());

            verify(journal, times(1)).append(any(TicketHistory.class));
        }
    }

    // ── client verification history ──────────────────────────────────────────

    @Nested
    @DisplayName("requestClientVerification")
    class ClientVerificationHistory {

        @Test
        @DisplayName("writes a CLIENT_VERIFICATION_REQUESTED row when true")
        void writesARowWhenRequested() {
            service.close(caller, TICKET_CODE, new CloseDtos.CloseRequest(SUMMARY, null, null, null, true));

            ArgumentCaptor<TicketHistory> captor = ArgumentCaptor.forClass(TicketHistory.class);
            verify(journal, times(2)).append(captor.capture());
            assertThat(captor.getAllValues())
                    .anySatisfy(entry -> assertThat(entry.getEventType())
                            .isEqualTo("CLIENT_VERIFICATION_REQUESTED"));
        }

        @Test
        @DisplayName("writes nothing extra when false or omitted")
        void writesNothingWhenNotRequested() {
            service.close(caller, TICKET_CODE, request());

            verify(journal, times(1)).append(any(TicketHistory.class));
        }
    }

    // ── the CLOSED history entry ─────────────────────────────────────────────

    @Nested
    @DisplayName("the CLOSED history entry")
    class HistoryEntry {

        @Test
        @DisplayName("is a RESOLVED → CLOSED status change stamped with the current cycle")
        void isAStatusChangeOnTheCurrentCycle() {
            service.close(caller, TICKET_CODE, request());

            TicketHistory entry = lastAppendedHistory();
            assertThat(entry.getTicketId()).isEqualTo(TICKET);
            assertThat(entry.getCycleNo()).isEqualTo((short) 1);
            assertThat(entry.getEventType()).isEqualTo("CLOSED");
            assertThat(entry.getFieldName()).isEqualTo("status");
            assertThat(entry.getOldValue()).isEqualTo("RESOLVED");
            assertThat(entry.getNewValue()).isEqualTo("CLOSED");
            assertThat(entry.getRemarks()).isEqualTo(SUMMARY);
        }

        @Test
        @DisplayName("names the actor, with actor_type agreeing")
        void namesTheActor() {
            service.close(caller, TICKET_CODE, request());

            TicketHistory entry = lastAppendedHistory();
            assertThat(entry.getActorId()).isEqualTo(ACTOR);
            assertThat(entry.getActorType()).isEqualTo("USER");
        }

        @Test
        @DisplayName("carries no hash of its own — the journal computes the chain")
        void carriesNoHash() {
            service.close(caller, TICKET_CODE, request());

            TicketHistory entry = lastAppendedHistory();
            assertThat(entry.getPrevHash()).isNull();
            assertThat(entry.getRowHash()).isNull();
            assertThat(entry.isCorrection()).isFalse();
        }
    }

    // ── refusals ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("refusals")
    class Refusals {

        @Test
        @DisplayName("an out-of-scope or absent ticket is 404, and nothing is written")
        void outOfScopeIs404() {
            when(tickets.requireByCode(any(), eq(TICKET_CODE))).thenThrow(new TicketNotFoundException());

            assertThatThrownBy(() -> service.close(caller, TICKET_CODE, request()))
                    .isInstanceOf(TicketNotFoundException.class);

            verifyNoInteractions(cycles, journal);
        }

        @Test
        @DisplayName("IN_PROGRESS is 422 — nothing has been claimed complete yet")
        void inProgressIs422() {
            ticket.setStatus("IN_PROGRESS");

            assertThatThrownBy(() -> service.close(caller, TICKET_CODE, request()))
                    .isInstanceOf(TicketNotResolvedException.class)
                    .hasMessageContaining("IN_PROGRESS");

            verifyNoInteractions(journal);
            assertThat(cycle.isSealed()).isFalse();
        }

        @Test
        @DisplayName("an already-closed ticket is 422 too, named specifically")
        void alreadyClosedIs422() {
            ticket.setStatus("CLOSED");

            assertThatThrownBy(() -> service.close(caller, TICKET_CODE, request()))
                    .isInstanceOf(TicketNotResolvedException.class)
                    .hasMessageContaining("already CLOSED");

            verifyNoInteractions(journal);
        }

        /**
         * Cycle 1 is created with the ticket (§4.1), so a RESOLVED ticket with no
         * row for its current cycle is a data defect — {@code ReopenServiceTest}'s
         * own reasoning, applied to the cycle being sealed rather than the one
         * being inserted.
         */
        @Test
        @DisplayName("a missing cycle row fails loudly rather than closing over the hole")
        void missingCycleRowFailsLoudly() {
            when(cycles.findByTicketIdAndCycleNo(TICKET, (short) 1)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.close(caller, TICKET_CODE, request()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("nothing to seal");

            verifyNoInteractions(journal);
        }
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private static CloseDtos.CloseRequest request() {
        return new CloseDtos.CloseRequest(SUMMARY, null, null, null, null);
    }

    private Ticket resolvedTicket() {
        Ticket t = new Ticket();
        ReflectionTestUtils.setField(t, "id", TICKET);
        ReflectionTestUtils.setField(t, "ticketCode", "CRM-26-00347");
        t.setProjectId(PROJECT);
        t.setTitle("Timesheet totals double-count a reopened cycle");
        t.setTaskTypeId(4);
        t.setLevel("HIGH");
        t.setOriginalLevel("MEDIUM");
        t.setStatus("RESOLVED");
        t.setAssignedTo(CYCLE_ASSIGNEE);
        t.setCurrentCycleNo((short) 1);
        t.setPlannedCloseDate(Instant.parse("2026-08-20T12:00:00Z"));
        return t;
    }

    private TicketCycle cycle() {
        TicketCycle c = new TicketCycle();
        ReflectionTestUtils.setField(c, "id", 901L);
        c.setTicketId(TICKET);
        c.setCycleNo((short) 1);
        c.setStartDate(Instant.parse("2026-08-03T09:00:00Z"));
        c.setAssignedTo(CYCLE_ASSIGNEE);
        c.setLevel("HIGH");
        c.setPlannedCloseDate(Instant.parse("2026-08-20T12:00:00Z"));
        return c;
    }

    /** The last row handed to {@code journal.append} — the CLOSED status row. */
    private TicketHistory lastAppendedHistory() {
        ArgumentCaptor<TicketHistory> captor = ArgumentCaptor.forClass(TicketHistory.class);
        verify(journal, org.mockito.Mockito.atLeastOnce()).append(captor.capture());
        List<TicketHistory> all = captor.getAllValues();
        return all.get(all.size() - 1);
    }
}
