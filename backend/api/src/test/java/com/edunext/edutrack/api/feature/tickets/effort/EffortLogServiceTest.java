package com.edunext.edutrack.api.feature.tickets.effort;

import com.edunext.edutrack.api.security.dev.DevPrincipal;
import com.edunext.edutrack.api.security.scope.ScopedTickets;
import com.edunext.edutrack.domain.journal.TicketJournal;
import com.edunext.edutrack.domain.tickets.Ticket;
import com.edunext.edutrack.domain.tickets.TicketEffortLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C-035 · what §4A.4 asks of an effort log.
 *
 * <p>The assertions cluster on the two things that are invisible when they go
 * wrong:
 *
 * <ul>
 *   <li><b>the stamp</b> — stage, iteration and cycle have to come from the
 *       ticket's own row, never from the request, or a stale tab left open
 *       across a handoff attributes its hours to a stage the ticket already
 *       left;</li>
 *   <li><b>the correction guard</b> — {@link TicketJournal#reverseEffort} trusts
 *       whatever id it is given and copies {@code ticketId} from the row that id
 *       names, so without a same-ticket check here, {@code correctsEntryId}
 *       would be a way to reverse an entry on any ticket in the system from any
 *       other ticket's route.</li>
 * </ul>
 *
 * <p>A unit test with mocked collaborators: none of this is a database
 * behaviour, and the journal's own guarantees (the hash chain, the correction
 * arithmetic) are {@code TicketJournalIT}'s to prove. What is asserted here is
 * the entry handed to it.
 */
class EffortLogServiceTest {

    private static final long TICKET = 4711L;
    private static final String TICKET_CODE = "CRM-26-00347";
    private static final long ACTOR = 88L;

    private final ScopedTickets tickets = mock(ScopedTickets.class);
    private final TicketJournal journal = mock(TicketJournal.class);
    private final EffortLogUserRefs people = mock(EffortLogUserRefs.class);

    private final EffortLogService service = new EffortLogService(tickets, journal, people);

    /**
     * The third argument is load-bearing — {@code PriorityChangeServiceTest}'s
     * note on the identical line is why it is repeated here:
     * {@code TestingAuthenticationToken}'s two-argument constructor leaves
     * {@code isAuthenticated()} false, and {@code CallerIdentity.of} returns
     * empty for an unauthenticated token, which would make {@code actorId}
     * throw rather than resolve to {@code ACTOR}.
     */
    private final Authentication caller = new TestingAuthenticationToken(
            new DevPrincipal(ACTOR, "ravi", "Ravi Kumar", "DEVELOPER", List.of(), List.of()),
            "n/a", "ticket.update_progress");

    private Ticket ticket;

    @BeforeEach
    void setUp() {
        ticket = ticketInDevelopment();
        when(tickets.requireByCode(any(), eq(TICKET_CODE))).thenReturn(ticket);
        when(people.resolve(any())).thenReturn(Map.of());
    }

    // ── the stamp — the whole task ────────────────────────────────────────────

    @Nested
    @DisplayName("logging an ordinary entry")
    class OrdinaryEntry {

        @Test
        @DisplayName("stamps stage, cycle and iteration from the ticket, never from the request")
        void stampsFromTheTicketsCurrentPosition() {
            ticket.setCurrentStage("QA");
            ticket.setCurrentCycleNo((short) 2);
            ticket.setCurrentIteration((short) 3);
            when(journal.append(any(TicketEffortLog.class))).thenAnswer(EffortLogServiceTest::persisted);

            service.log(caller, TICKET_CODE, request(new BigDecimal("2.50"), "2026-08-15", "Retested the fix"));

            ArgumentCaptor<TicketEffortLog> captor = ArgumentCaptor.forClass(TicketEffortLog.class);
            verify(journal).append(captor.capture());
            TicketEffortLog entry = captor.getValue();

            assertThat(entry.getTicketId()).isEqualTo(TICKET);
            assertThat(entry.getStageCode()).isEqualTo("QA");
            assertThat(entry.getCycleNo()).isEqualTo((short) 2);
            assertThat(entry.getIterationNo()).isEqualTo((short) 3);
            assertThat(entry.getUserId()).isEqualTo(ACTOR);
            assertThat(entry.getHours()).isEqualByComparingTo("2.50");
            assertThat(entry.getWorkDate()).isEqualTo(LocalDate.of(2026, 8, 15));
            assertThat(entry.getNote()).isEqualTo("Retested the fix");
            assertThat(entry.isCorrection()).isFalse();
            assertThat(entry.getCorrectsEntryId()).isNull();
        }

        @Test
        @DisplayName("a blank note is stored as null, not as an empty string")
        void blankNoteBecomesNull() {
            when(journal.append(any(TicketEffortLog.class))).thenAnswer(EffortLogServiceTest::persisted);

            service.log(caller, TICKET_CODE, request(new BigDecimal("1.00"), "2026-08-15", "   "));

            ArgumentCaptor<TicketEffortLog> captor = ArgumentCaptor.forClass(TicketEffortLog.class);
            verify(journal).append(captor.capture());
            assertThat(captor.getValue().getNote()).isNull();
        }
    }

    // ── the correction path ───────────────────────────────────────────────────

    @Nested
    @DisplayName("logging a correction")
    class Correction {

        @Test
        @DisplayName("reverses through the journal rather than appending, and ignores hours/workDate")
        void usesReverseEffortNotAppend() {
            TicketEffortLog original = existingEntryOn(TICKET, 501L);
            when(journal.effortFor(TICKET, null)).thenReturn(List.of(original));
            TicketEffortLog reversal = entryWith(502L, (short) 1, "DEVELOPMENT", new BigDecimal("-4.00"));
            when(journal.reverseEffort(501L, "wrong stage, reversing")).thenReturn(reversal);

            // hours and workDate are populated because the schema requires them
            // regardless of isCorrection (EffortLogDtos.EffortLogRequest's own
            // javadoc explains why) — and the point of this test is that neither
            // reaches the journal.
            service.log(caller, TICKET_CODE,
                    new EffortLogDtos.EffortLogRequest(new BigDecimal("99.00"), LocalDate.of(2020, 1, 1),
                            "wrong stage, reversing", true, 501L));

            verify(journal).reverseEffort(501L, "wrong stage, reversing");
            verify(journal, never()).append(any(TicketEffortLog.class));
        }

        @Test
        @DisplayName("400s when isCorrection is true with no correctsEntryId")
        void requiresATarget() {
            assertThatThrownBy(() -> service.log(caller, TICKET_CODE,
                    new EffortLogDtos.EffortLogRequest(new BigDecimal("1.00"), LocalDate.of(2026, 8, 15),
                            null, true, null)))
                    .isInstanceOf(EffortCorrectionTargetRequiredException.class);

            verify(journal, never()).append(any(TicketEffortLog.class));
            verify(journal, never()).reverseEffort(any(), any());
        }

        /**
         * The guard {@link TicketJournal#reverseEffort} itself cannot provide —
         * see the class javadoc. A {@code correctsEntryId} naming a row that
         * exists but belongs to a different ticket must refuse exactly like one
         * that does not exist at all, or a caller could enumerate valid ids on
         * other tickets by the difference between the two responses.
         */
        @Test
        @DisplayName("404s on a correctsEntryId that belongs to a different ticket")
        void refusesACrossTicketTarget() {
            when(journal.effortFor(TICKET, null)).thenReturn(List.of());

            assertThatThrownBy(() -> service.log(caller, TICKET_CODE,
                    new EffortLogDtos.EffortLogRequest(new BigDecimal("1.00"), LocalDate.of(2026, 8, 15),
                            "reversing", true, 999L)))
                    .isInstanceOf(EffortLogNotFoundException.class);

            verify(journal, times(1)).effortFor(TICKET, null);
            verify(journal, never()).reverseEffort(any(), any());
        }
    }

    // ── list totals ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("listing")
    class Listing {

        /**
         * §4A.4's headline figures are unaffected by which slice of the log the
         * caller happens to be filtering on — see {@code EffortLogService.list}'s
         * javadoc for why they are computed from the unfiltered set.
         */
        @Test
        @DisplayName("cycleTotalHrs and grandTotalHrs are unaffected by a stage filter")
        void totalsIgnoreTheRequestedFilter() {
            ticket.setCurrentCycleNo((short) 2);
            TicketEffortLog cycle2Dev = entryWith(1L, (short) 2, "DEV", new BigDecimal("3.00"));
            TicketEffortLog cycle2Qa = entryWith(2L, (short) 2, "QA", new BigDecimal("1.50"));
            TicketEffortLog cycle1Dev = entryWith(3L, (short) 1, "OPS", new BigDecimal("5.00"));
            when(journal.effortFor(TICKET, null)).thenReturn(List.of(cycle2Dev, cycle2Qa, cycle1Dev));

            EffortLogDtos.EffortLogListResponse response =
                    service.list(caller, TICKET_CODE, null, "DEV", null, null, null);

            // Only cycle2Dev matches the stage filter …
            assertThat(response.data()).hasSize(1);
            // … but the totals still cover the whole ticket.
            assertThat(response.meta().cycleTotalHrs()).isEqualByComparingTo("4.50");
            assertThat(response.meta().grandTotalHrs()).isEqualByComparingTo("9.50");
        }
    }

    // ── fixtures ───────────────────────────────────────────────────────────────

    private static EffortLogDtos.EffortLogRequest request(BigDecimal hours, String workDate, String note) {
        return new EffortLogDtos.EffortLogRequest(hours, LocalDate.parse(workDate), note, false, null);
    }

    private static Ticket ticketInDevelopment() {
        Ticket t = new Ticket();
        ReflectionTestUtils.setField(t, "id", TICKET);
        ReflectionTestUtils.setField(t, "ticketCode", TICKET_CODE);
        t.setProjectId(12L);
        t.setTitle("Timesheet totals double-count a reopened cycle");
        t.setCurrentCycleNo((short) 1);
        t.setCurrentIteration((short) 1);
        t.setCurrentStage("DEVELOPMENT");
        return t;
    }

    private static TicketEffortLog existingEntryOn(long ticketId, long id) {
        TicketEffortLog e = entryWith(id, (short) 1, "DEVELOPMENT", new BigDecimal("4.00"));
        ReflectionTestUtils.setField(e, "ticketId", ticketId);
        return e;
    }

    /**
     * {@code journal.append}'s real contract is "the managed instance, its
     * generated id populated after the flush" ({@code AppendOnly.insert}'s
     * javadoc) — a bare {@code thenAnswer(call -> call.getArgument(0))} returns
     * the caller's own not-yet-persisted entry, id still null, and
     * {@code EffortLogDto.of}'s primitive {@code long id} then NPEs on unboxing.
     * This is what a real {@code @Id @GeneratedValue} column would have supplied.
     */
    private static TicketEffortLog persisted(InvocationOnMock call) {
        TicketEffortLog entry = call.getArgument(0);
        ReflectionTestUtils.setField(entry, "id", 900L);
        return entry;
    }

    private static TicketEffortLog entryWith(long id, short cycleNo, String stage, BigDecimal hours) {
        TicketEffortLog e = new TicketEffortLog();
        ReflectionTestUtils.setField(e, "id", id);
        e.setTicketId(TICKET);
        e.setCycleNo(cycleNo);
        e.setStageCode(stage);
        e.setIterationNo((short) 1);
        e.setUserId(ACTOR);
        e.setWorkDate(LocalDate.of(2026, 8, 15));
        e.setHours(hours);
        return e;
    }
}
