package com.edunext.edutrack.api.feature.tickets.quickupdate;

import com.edunext.edutrack.api.feature.tickets.StatusCode;
import com.edunext.edutrack.api.feature.tickets.TicketWire;
import com.edunext.edutrack.api.security.dev.DevPrincipal;
import com.edunext.edutrack.api.security.scope.ScopedTickets;
import com.edunext.edutrack.api.security.scope.TicketNotFoundException;
import com.edunext.edutrack.domain.journal.TicketJournal;
import com.edunext.edutrack.domain.tickets.Ticket;
import com.edunext.edutrack.domain.tickets.TicketAttachment;
import com.edunext.edutrack.domain.tickets.TicketAttachmentRepository;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

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
 * C-036 · what S-21 asks of a quick update.
 *
 * <p>The assertions cluster on the things that are invisible when they go
 * wrong:
 *
 * <ul>
 *   <li><b>every field is independent</b> — a request naming only {@code
 *       pctComplete} must not touch status, effort or the ETA, and vice versa;</li>
 *   <li><b>the effort stamp</b> — stage, cycle and iteration come from the
 *       ticket's own row, exactly as {@code EffortLogServiceTest} asserts for
 *       the ordinary effort route this one shares a journal with;</li>
 *   <li><b>{@code workNote} has exactly one destination per call</b> — the
 *       effort log's own note when effort is logged in the same call, a
 *       standalone history row when it is not, never both;</li>
 *   <li><b>{@code attachmentIds} is validated and never acted on</b> — nothing
 *       is written for it, because the files are on the ticket already.</li>
 * </ul>
 *
 * <p>A unit test with mocked collaborators: the journal's own guarantees are
 * {@code TicketJournalIT}'s to prove. What is asserted here is the entry
 * handed to it.
 */
class QuickUpdateServiceTest {

    private static final long TICKET = 4711L;
    private static final String TICKET_CODE = "CRM-26-00347";
    private static final long ACTOR = 88L;

    private final ScopedTickets tickets = mock(ScopedTickets.class);
    private final TicketJournal journal = mock(TicketJournal.class);
    private final TicketAttachmentRepository attachments = mock(TicketAttachmentRepository.class);

    private final QuickUpdateService service = new QuickUpdateService(tickets, journal, attachments);

    /**
     * The third argument is load-bearing — {@code EffortLogServiceTest}'s note
     * on the identical line is why it is repeated: {@code
     * TestingAuthenticationToken}'s two-argument constructor leaves {@code
     * isAuthenticated()} false, and {@code CallerIdentity.of} returns empty for
     * an unauthenticated token.
     */
    private final Authentication caller = new TestingAuthenticationToken(
            new DevPrincipal(ACTOR, "ravi", "Ravi Kumar", "DEVELOPER", List.of(), List.of()),
            "n/a", "ticket.update_progress");

    private Ticket ticket;

    @BeforeEach
    void setUp() {
        ticket = ticketInDevelopment();
        when(tickets.requireByCode(any(), eq(TICKET_CODE))).thenReturn(ticket);
        when(attachments.findByTicketIdAndIsDeletedFalseOrderByCreatedAtAsc(TICKET)).thenReturn(List.of());
    }

    // ── independence of the fields ────────────────────────────────────────────

    @Nested
    @DisplayName("field independence")
    class Independence {

        @Test
        @DisplayName("a status-only request touches nothing else")
        void statusOnlyTouchesNothingElse() {
            service.update(caller, TICKET_CODE, request(b -> b.status = StatusCode.ON_HOLD));

            assertThat(ticket.getStatus()).isEqualTo("ON_HOLD");
            assertThat(ticket.getPctComplete()).isZero();
            verify(journal, never()).append(any(TicketEffortLog.class));
        }

        @Test
        @DisplayName("a pctComplete-only request touches nothing else")
        void pctCompleteOnlyTouchesNothingElse() {
            service.update(caller, TICKET_CODE, request(b -> b.pctComplete = 60));

            assertThat(ticket.getPctComplete()).isEqualTo((short) 60);
            assertThat(ticket.getStatus()).isEqualTo("IN_PROGRESS");
            verify(journal, never()).append(any(TicketEffortLog.class));
        }

        @Test
        @DisplayName("an empty request writes nothing at all")
        void emptyRequestWritesNothing() {
            TicketWire.Ticket answered = service.update(caller, TICKET_CODE, request(b -> { }));

            assertThat(answered.status()).isEqualTo("IN_PROGRESS");
            verifyNoInteractions(journal);
        }
    }

    // ── status ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("status")
    class Status {

        @Test
        @DisplayName("moves the ticket and writes STATUS_CHANGED old -> new")
        void movesAndRecords() {
            service.update(caller, TICKET_CODE, request(b -> b.status = StatusCode.RESOLVED));

            assertThat(ticket.getStatus()).isEqualTo("RESOLVED");
            TicketHistory entry = onlyHistoryEntry();
            assertThat(entry.getEventType()).isEqualTo("STATUS_CHANGED");
            assertThat(entry.getFieldName()).isEqualTo("status");
            assertThat(entry.getOldValue()).isEqualTo("IN_PROGRESS");
            assertThat(entry.getNewValue()).isEqualTo("RESOLVED");
            assertThat(entry.getActorId()).isEqualTo(ACTOR);
            assertThat(entry.getActorType()).isEqualTo("USER");
        }

        @Test
        @DisplayName("re-picking the current status is a no-op — nothing is written")
        void sameStatusIsANoOp() {
            service.update(caller, TICKET_CODE, request(b -> b.status = StatusCode.IN_PROGRESS));

            verifyNoInteractions(journal);
        }
    }

    // ── pctComplete ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("pctComplete")
    class PctComplete {

        @Test
        @DisplayName("moves the ticket and writes FIELD_CHANGED old -> new")
        void movesAndRecords() {
            ticket.setPctComplete((short) 20);

            service.update(caller, TICKET_CODE, request(b -> b.pctComplete = 75));

            assertThat(ticket.getPctComplete()).isEqualTo((short) 75);
            TicketHistory entry = onlyHistoryEntry();
            assertThat(entry.getEventType()).isEqualTo("FIELD_CHANGED");
            assertThat(entry.getFieldName()).isEqualTo("pctComplete");
            assertThat(entry.getOldValue()).isEqualTo("20");
            assertThat(entry.getNewValue()).isEqualTo("75");
        }

        @Test
        @DisplayName("an unmoved value is a no-op, so a slider rendered at the current value writes nothing")
        void sameValueIsANoOp() {
            ticket.setPctComplete((short) 40);

            service.update(caller, TICKET_CODE, request(b -> b.pctComplete = 40));

            verifyNoInteractions(journal);
        }
    }

    // ── effort + work note ────────────────────────────────────────────────────

    @Nested
    @DisplayName("logging effort")
    class Effort {

        @Test
        @DisplayName("stamps stage, cycle and iteration from the ticket, never from the request")
        void stampsFromTheTicketsCurrentPosition() {
            ticket.setCurrentStage("QA");
            ticket.setCurrentCycleNo((short) 2);
            ticket.setCurrentIteration((short) 3);

            service.update(caller, TICKET_CODE, request(b -> {
                b.effortHours = new BigDecimal("2.50");
                b.effortDate = LocalDate.of(2026, 8, 15);
                b.workNote = "Fixed the retry loop";
            }));

            TicketEffortLog entry = onlyEffortEntry();
            assertThat(entry.getTicketId()).isEqualTo(TICKET);
            assertThat(entry.getStageCode()).isEqualTo("QA");
            assertThat(entry.getCycleNo()).isEqualTo((short) 2);
            assertThat(entry.getIterationNo()).isEqualTo((short) 3);
            assertThat(entry.getUserId()).isEqualTo(ACTOR);
            assertThat(entry.getHours()).isEqualByComparingTo("2.50");
            assertThat(entry.getWorkDate()).isEqualTo(LocalDate.of(2026, 8, 15));
            assertThat(entry.getNote()).isEqualTo("Fixed the retry loop");
            assertThat(entry.isCorrection()).isFalse();

            // The note rode on the effort entry — nothing duplicated into history.
            verify(journal, never()).append(any(TicketHistory.class));
        }

        @Test
        @DisplayName("defaults the work date to today when effortHours is sent without one")
        void defaultsTheWorkDate() {
            service.update(caller, TICKET_CODE, request(b -> b.effortHours = new BigDecimal("1.00")));

            assertThat(onlyEffortEntry().getWorkDate()).isEqualTo(LocalDate.now(java.time.ZoneOffset.UTC));
        }
    }

    @Nested
    @DisplayName("a work note with no effort logged")
    class WorkNoteAlone {

        @Test
        @DisplayName("writes its own WORK_NOTE_ADDED history row rather than being dropped")
        void writesAStandaloneRow() {
            service.update(caller, TICKET_CODE, request(b -> b.workNote = "Waiting on the client for a repro"));

            verify(journal, never()).append(any(TicketEffortLog.class));
            TicketHistory entry = onlyHistoryEntry();
            assertThat(entry.getEventType()).isEqualTo("WORK_NOTE_ADDED");
            assertThat(entry.getFieldName()).isEqualTo("workNote");
            assertThat(entry.getNewValue()).isEqualTo("Waiting on the client for a repro");
        }

        @Test
        @DisplayName("blank is the same as absent — nothing is written")
        void blankIsAbsent() {
            service.update(caller, TICKET_CODE, request(b -> b.workNote = "   "));

            verifyNoInteractions(journal);
        }
    }

    // ── revised ETA ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("revised ETA")
    class RevisedEta {

        @Test
        @DisplayName("requires a reason, and nothing moves without one")
        void requiresAReason() {
            Instant before = ticket.getPlannedCloseDate();

            assertThatThrownBy(() -> service.update(caller, TICKET_CODE,
                    request(b -> b.revisedEta = Instant.parse("2026-08-20T00:00:00Z"))))
                    .isInstanceOf(RevisedEtaReasonRequiredException.class);

            assertThat(ticket.getPlannedCloseDate()).isEqualTo(before);
            verifyNoInteractions(journal);
        }

        @Test
        @DisplayName("blank whitespace does not satisfy the reason requirement")
        void blankIsNotAReason() {
            assertThatThrownBy(() -> service.update(caller, TICKET_CODE, request(b -> {
                b.revisedEta = Instant.parse("2026-08-20T00:00:00Z");
                b.revisedEtaReason = "   ";
            }))).isInstanceOf(RevisedEtaReasonRequiredException.class);
        }

        @Test
        @DisplayName("moves the date and writes FIELD_CHANGED with the reason in remarks")
        void movesAndRecords() {
            service.update(caller, TICKET_CODE, request(b -> {
                b.revisedEta = Instant.parse("2026-08-20T00:00:00Z");
                b.revisedEtaReason = "Client asked for two more days of UAT";
            }));

            assertThat(ticket.getPlannedCloseDate()).isEqualTo(Instant.parse("2026-08-20T00:00:00Z"));
            TicketHistory entry = onlyHistoryEntry();
            assertThat(entry.getEventType()).isEqualTo("FIELD_CHANGED");
            assertThat(entry.getFieldName()).isEqualTo("plannedCloseDate");
            assertThat(entry.getRemarks()).isEqualTo("Client asked for two more days of UAT");
        }
    }

    // ── attachmentIds ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("attachmentIds")
    class AttachmentIds {

        @Test
        @DisplayName("an id already on the ticket is accepted and nothing is written for it")
        void ownAttachmentIsAccepted() {
            when(attachments.findByTicketIdAndIsDeletedFalseOrderByCreatedAtAsc(TICKET))
                    .thenReturn(List.of(attachment(501L)));

            service.update(caller, TICKET_CODE, request(b -> b.attachmentIds = List.of(501L)));

            verifyNoInteractions(journal);
        }

        @Test
        @DisplayName("an id from another ticket is refused before anything else is touched")
        void foreignAttachmentIsRefused() {
            when(attachments.findByTicketIdAndIsDeletedFalseOrderByCreatedAtAsc(TICKET))
                    .thenReturn(List.of(attachment(501L)));

            assertThatThrownBy(() -> service.update(caller, TICKET_CODE, request(b -> {
                b.status = StatusCode.RESOLVED;
                b.attachmentIds = List.of(999L);
            }))).isInstanceOf(AttachmentNotOnTicketException.class);

            assertThat(ticket.getStatus()).isEqualTo("IN_PROGRESS");
            verifyNoInteractions(journal);
        }
    }

    // ── scope ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an out-of-scope ticket is 404 and nothing else is consulted")
    void outOfScopeIs404() {
        when(tickets.requireByCode(any(), eq(TICKET_CODE))).thenThrow(new TicketNotFoundException());

        assertThatThrownBy(() -> service.update(caller, TICKET_CODE, request(b -> b.status = StatusCode.RESOLVED)))
                .isInstanceOf(TicketNotFoundException.class);

        verifyNoInteractions(journal);
        verifyNoInteractions(attachments);
    }

    // ── multiple fields in one call ───────────────────────────────────────

    @Test
    @DisplayName("every touched field writes its own entry in one call")
    void multipleFieldsWriteIndependently() {
        service.update(caller, TICKET_CODE, request(b -> {
            b.status = StatusCode.RESOLVED;
            b.pctComplete = 100;
            b.effortHours = new BigDecimal("1.00");
            b.revisedEta = Instant.parse("2026-08-20T00:00:00Z");
            b.revisedEtaReason = "closing out";
        }));

        verify(journal).append(any(TicketEffortLog.class));
        ArgumentCaptor<TicketHistory> captor = ArgumentCaptor.forClass(TicketHistory.class);
        verify(journal, org.mockito.Mockito.times(3)).append(captor.capture());
        List<String> fieldNames = captor.getAllValues().stream().map(TicketHistory::getFieldName).toList();
        assertThat(fieldNames).containsExactlyInAnyOrder("status", "pctComplete", "plannedCloseDate");
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private TicketHistory onlyHistoryEntry() {
        ArgumentCaptor<TicketHistory> captor = ArgumentCaptor.forClass(TicketHistory.class);
        verify(journal).append(captor.capture());
        return captor.getValue();
    }

    private TicketEffortLog onlyEffortEntry() {
        ArgumentCaptor<TicketEffortLog> captor = ArgumentCaptor.forClass(TicketEffortLog.class);
        verify(journal).append(captor.capture());
        return captor.getValue();
    }

    private static QuickUpdateDtos.QuickUpdateRequest request(java.util.function.Consumer<Builder> customize) {
        Builder b = new Builder();
        customize.accept(b);
        return new QuickUpdateDtos.QuickUpdateRequest(
                b.status, b.effortHours, b.effortDate, b.workNote, b.pctComplete,
                b.revisedEta, b.revisedEtaReason, b.attachmentIds);
    }

    /** A plain mutable builder — the record's eight-argument constructor is unreadable at a call site otherwise. */
    private static final class Builder {
        StatusCode status;
        BigDecimal effortHours;
        LocalDate effortDate;
        String workNote;
        Integer pctComplete;
        Instant revisedEta;
        String revisedEtaReason;
        List<Long> attachmentIds;
    }

    private static Ticket ticketInDevelopment() {
        Ticket t = new Ticket();
        ReflectionTestUtils.setField(t, "id", TICKET);
        ReflectionTestUtils.setField(t, "ticketCode", TICKET_CODE);
        t.setProjectId(12L);
        t.setTitle("Timesheet totals double-count a reopened cycle");
        t.setStatus("IN_PROGRESS");
        t.setPctComplete((short) 0);
        t.setCurrentCycleNo((short) 1);
        t.setCurrentIteration((short) 1);
        t.setCurrentStage("DEVELOPMENT");
        return t;
    }

    private static TicketAttachment attachment(long id) {
        TicketAttachment a = new TicketAttachment();
        ReflectionTestUtils.setField(a, "id", id);
        a.setTicketId(TICKET);
        return a;
    }
}
