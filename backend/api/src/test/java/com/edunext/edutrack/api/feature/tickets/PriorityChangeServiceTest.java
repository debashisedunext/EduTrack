package com.edunext.edutrack.api.feature.tickets;

import com.edunext.edutrack.api.security.dev.DevPrincipal;
import com.edunext.edutrack.api.security.scope.ScopedTickets;
import com.edunext.edutrack.api.security.scope.TicketNotFoundException;
import com.edunext.edutrack.domain.journal.TicketJournal;
import com.edunext.edutrack.domain.tickets.Ticket;
import com.edunext.edutrack.domain.tickets.TicketCycle;
import com.edunext.edutrack.domain.tickets.TicketCycleRepository;
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
 * C-020 · what §4B.1 asks of a level change.
 *
 * <p>The assertions cluster on the four things that are invisible when they go
 * wrong:
 *
 * <ul>
 *   <li><b>{@code original_level}</b> — a single overwrite is unrecoverable and
 *       leaves the ticket, its history and every report agreeing on a falsehood.
 *       Asserted on every path that writes, not once;</li>
 *   <li><b>the mandatory reason</b> — §4B.1 conditions it on the ticket being
 *       assigned, which no Bean Validation annotation can see, so the rule
 *       exists only if this test says it does;</li>
 *   <li><b>the clock the date is recomputed from</b> — measuring from now
 *       instead of from the cycle's start reads as "comfortably on track" for a
 *       ticket that is three days late, which is the failure that hides a
 *       breach rather than causing one;</li>
 *   <li><b>the no-op</b> — a {@code HIGH → HIGH} history row cannot be deleted
 *       once written, and the History tab is the one place noise is expensive.</li>
 * </ul>
 *
 * <p>A unit test with mocked collaborators, deliberately: none of the four is a
 * database behaviour, and the journal's own guarantees (the hash chain, the
 * {@code SELECT … FOR UPDATE}) are {@code TicketJournalIT}'s to prove. What is
 * asserted here is the <em>entry handed to it</em>.
 */
class PriorityChangeServiceTest {

    private static final long TICKET = 4711L;

    /**
     * The contract's {@code TicketId} is a code, not a row id, so this is what
     * reaches the service — see {@code PriorityChangeController}. The numeric id
     * above is still the row's own, which is what the cycle lookup and the
     * history entry are keyed by.
     */
    private static final String TICKET_CODE = "CRM-26-00347";
    private static final long PROJECT = 12L;
    private static final long ACTOR = 88L;
    private static final long ASSIGNEE = 44L;

    /** Cycle 1's start, which is also this ticket's date reported. */
    private static final Instant CYCLE_1_START = Instant.parse("2026-08-03T09:00:00Z");

    private static final String REASON = "Client escalated — production is down for their whole finance team.";

    private final ScopedTickets tickets = mock(ScopedTickets.class);
    private final TicketCycleRepository cycles = mock(TicketCycleRepository.class);
    private final TicketJournal journal = mock(TicketJournal.class);
    private final PlannedCloseDateService slaLadder = mock(PlannedCloseDateService.class);

    private final PriorityChangeService service =
            new PriorityChangeService(tickets, cycles, journal, slaLadder);

    /**
     * The third argument is load-bearing, and {@code ReopenServiceTest}'s note on
     * the same line is why it is repeated: {@code TestingAuthenticationToken}'s
     * two-argument constructor leaves {@code isAuthenticated()} false, and
     * {@code CallerIdentity.of} returns empty for an unauthenticated token. A
     * caller built the short way reaches the service as <em>nobody</em>, the
     * history entry lands as SYSTEM, and the failure is about the token rather
     * than about the level change — which would be a particularly bad one to miss
     * here, since SYSTEM is exactly what the SLA scanner writes.
     */
    private final Authentication caller = new TestingAuthenticationToken(
            new DevPrincipal(ACTOR, "priya", "Priya Nair", "PM", List.of(PROJECT), List.of()),
            "n/a", "ticket.assign");

    private Ticket ticket;

    @BeforeEach
    void setUp() {
        ticket = mediumTicket();

        when(tickets.requireByCode(any(), eq(TICKET_CODE))).thenReturn(ticket);
        when(cycles.findByTicketIdAndCycleNo(TICKET, (short) 1)).thenReturn(Optional.of(cycle(1)));
        // The ladder answers with a target by default, so the recompute path is
        // the one under test unless a case says otherwise.
        when(slaLadder.preview(eq(PROJECT), eq(4), any(), any(), any()))
                .thenAnswer(call -> new PlannedCloseDateService.Preview(
                        call.getArgument(4), Instant.parse("2026-08-04T13:00:00Z"), null, null));
    }

    // ── the field the whole task exists to protect ───────────────────────────

    @Nested
    @DisplayName("original_level")
    class OriginalLevel {

        /**
         * The single assertion this task cannot ship without. §4B.1: "`original_level`
         * on the ticket is never overwritten" — it is the only column that can answer
         * A-070's "born critical versus became critical", and once it has been moved
         * nothing in the system knows it was moved: the ticket claims it was always
         * Critical and the `LEVEL_CHANGED` row agrees.
         */
        @Test
        @DisplayName("is not touched by a change that moves level")
        void survivesTheChange() {
            assertThat(ticket.getOriginalLevel()).isEqualTo("MEDIUM");

            service.change(caller, TICKET_CODE, request("CRITICAL", REASON));

            assertThat(ticket.getLevel()).isEqualTo("CRITICAL");
            assertThat(ticket.getOriginalLevel()).isEqualTo("MEDIUM");
        }

        /**
         * The same, downwards. A de-escalation is the direction somebody is most
         * likely to think of as "putting it back", and putting `original_level` back
         * with it would erase the escalation that happened in between.
         */
        @Test
        @DisplayName("is not touched by a de-escalation either")
        void survivesADeEscalation() {
            ticket.setLevel("CRITICAL");

            service.change(caller, TICKET_CODE, request("LOW", REASON));

            assertThat(ticket.getLevel()).isEqualTo("LOW");
            assertThat(ticket.getOriginalLevel()).isEqualTo("MEDIUM");
        }

        /** And it reaches the wire unchanged, so the client cannot disagree with the row. */
        @Test
        @DisplayName("is answered as it was stored")
        void isAnsweredUnchanged() {
            TicketWire.Ticket answered = service.change(caller, TICKET_CODE, request("HIGH", REASON));

            assertThat(answered.level()).isEqualTo("HIGH");
            assertThat(answered.originalLevel()).isEqualTo("MEDIUM");
        }
    }

    // ── §4B.1's conditional reason ───────────────────────────────────────────

    @Nested
    @DisplayName("the mandatory reason")
    class Reason {

        @Test
        @DisplayName("is required once the ticket is assigned, and nothing is written without it")
        void requiredWhenAssigned() {
            assertThat(ticket.getAssignedTo()).isNotNull();

            assertThatThrownBy(() -> service.change(caller, TICKET_CODE, request("HIGH", null)))
                    .isInstanceOf(LevelReasonRequiredException.class);

            // The refusal has to come before anything moves. A ticket whose level
            // changed and whose history did not is the one state the append-only
            // design has no repair for.
            assertThat(ticket.getLevel()).isEqualTo("MEDIUM");
            verifyNoInteractions(journal);
        }

        /** Whitespace is not a reason. Trimmed to nothing is the same as absent. */
        @Test
        @DisplayName("is not satisfied by whitespace")
        void blankIsNotAReason() {
            assertThatThrownBy(() -> service.change(caller, TICKET_CODE, request("HIGH", "   ")))
                    .isInstanceOf(LevelReasonRequiredException.class);
        }

        /**
         * §4B.1's other half, and the reason the rule could not be a
         * {@code @NotBlank}: a ticket still in triage is having its level *set*,
         * and "why did you pick High" has no answer beyond the triage itself.
         */
        @Test
        @DisplayName("is optional while the ticket is unassigned, and stored as null rather than blank")
        void optionalWhenUnassigned() {
            ticket.setAssignedTo(null);

            service.change(caller, TICKET_CODE, request("HIGH", null));

            assertThat(ticket.getLevel()).isEqualTo("HIGH");
            assertThat(appendedEntry().getRemarks()).isNull();
        }

        @Test
        @DisplayName("reaches the history entry trimmed")
        void isStoredTrimmed() {
            service.change(caller, TICKET_CODE, request("HIGH", "  " + REASON + "  "));

            assertThat(appendedEntry().getRemarks()).isEqualTo(REASON);
        }
    }

    // ── the LEVEL_CHANGED row ────────────────────────────────────────────────

    @Nested
    @DisplayName("the history entry")
    class History {

        /**
         * The shape {@code SlaEscalation} already writes for the automatic half of
         * §4B.1 and {@code SingleTicketFixture} seeds. C-034 interleaves both into
         * one chronological stream, so a manual escalation shaped differently from
         * an automatic one would read as a different kind of event when the only
         * real difference is the actor.
         */
        @Test
        @DisplayName("is LEVEL_CHANGED on field 'level', old → new, with the actor and reason")
        void carriesTheChange() {
            service.change(caller, TICKET_CODE, request("CRITICAL", REASON));

            TicketHistory entry = appendedEntry();
            assertThat(entry.getTicketId()).isEqualTo(TICKET);
            assertThat(entry.getEventType()).isEqualTo("LEVEL_CHANGED");
            assertThat(entry.getFieldName()).isEqualTo("level");
            assertThat(entry.getOldValue()).isEqualTo("MEDIUM");
            assertThat(entry.getNewValue()).isEqualTo("CRITICAL");
            assertThat(entry.getRemarks()).isEqualTo(REASON);
        }

        /**
         * USER and the actor id, never SYSTEM. The scanner's rows on this same event
         * type are SYSTEM with a null actor, and the two must stay distinguishable —
         * a human name against an automated escalation is the one shape of history
         * that reads plausibly and is false.
         */
        @Test
        @DisplayName("is attributed to the caller as a USER, not to SYSTEM")
        void namesTheActor() {
            service.change(caller, TICKET_CODE, request("HIGH", REASON));

            TicketHistory entry = appendedEntry();
            assertThat(entry.getActorId()).isEqualTo(ACTOR);
            assertThat(entry.getActorType()).isEqualTo("USER");
        }

        /**
         * Stamped with the cycle the ticket is living in, so the History tab
         * filtered to cycle 2 shows the changes made during cycle 2 — not cycle 1's.
         */
        @Test
        @DisplayName("is stamped with the ticket's current cycle")
        void carriesTheCycle() {
            ticket.setCurrentCycleNo((short) 2);
            when(cycles.findByTicketIdAndCycleNo(TICKET, (short) 2)).thenReturn(Optional.of(cycle(2)));

            service.change(caller, TICKET_CODE, request("HIGH", REASON));

            assertThat(appendedEntry().getCycleNo()).isEqualTo((short) 2);
        }
    }

    // ── the recomputed planned close date ────────────────────────────────────

    @Nested
    @DisplayName("the planned close date")
    class PlannedCloseDate {

        /**
         * Measured from the cycle's start, not from now — see the service javadoc.
         * The consequence asserted here is the one that matters: an old ticket
         * escalated to a short level gets a date in the past and is breached, which
         * is what lets Stream D's scanner see it. Measuring from now would answer
         * "due tomorrow" for a ticket that is already three days late.
         */
        @Test
        @DisplayName("is recomputed from the cycle's start date, so an escalated old ticket lands in the past")
        void measuresFromTheCycleStart() {
            service.change(caller, TICKET_CODE, request("CRITICAL", REASON));

            verify(slaLadder).preview(PROJECT, 4, "CRITICAL", ASSIGNEE, CYCLE_1_START);
            assertThat(ticket.getPlannedCloseDate()).isEqualTo(Instant.parse("2026-08-04T13:00:00Z"));
        }

        /**
         * After a reopen the two diverge, and the cycle's is right: cycle 2's SLA
         * has never had anything to do with when the ticket was first raised.
         */
        @Test
        @DisplayName("uses the current cycle's start, not the ticket's date reported, after a reopen")
        void prefersTheCurrentCycle() {
            Instant cycle2Start = Instant.parse("2026-08-14T11:00:00Z");
            ticket.setCurrentCycleNo((short) 2);
            TicketCycle cycle2 = cycle(2);
            cycle2.setStartDate(cycle2Start);
            when(cycles.findByTicketIdAndCycleNo(TICKET, (short) 2)).thenReturn(Optional.of(cycle2));

            service.change(caller, TICKET_CODE, request("HIGH", REASON));

            verify(slaLadder).preview(PROJECT, 4, "HIGH", ASSIGNEE, cycle2Start);
        }

        /** No cycle row: fall back to date reported rather than refusing an urgent change. */
        @Test
        @DisplayName("falls back to date reported when the cycle row is missing")
        void fallsBackToDateReported() {
            when(cycles.findByTicketIdAndCycleNo(TICKET, (short) 1)).thenReturn(Optional.empty());

            service.change(caller, TICKET_CODE, request("HIGH", REASON));

            verify(slaLadder).preview(PROJECT, 4, "HIGH", ASSIGNEE, CYCLE_1_START);
        }

        /**
         * A ladder with nothing to say writes null rather than leaving the old
         * level's date in place — otherwise the ticket goes on claiming a
         * commitment nothing now backs.
         */
        @Test
        @DisplayName("is cleared when the new level resolves to no target")
        void clearedWhenTheLadderHasNoTarget() {
            when(slaLadder.preview(eq(PROJECT), eq(4), eq("LOW"), any(), any()))
                    .thenAnswer(call -> new PlannedCloseDateService.Preview(
                            call.getArgument(4), null, null, null));

            service.change(caller, TICKET_CODE, request("LOW", REASON));

            assertThat(ticket.getPlannedCloseDate()).isNull();
        }
    }

    // ── the edges ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("edges")
    class Edges {

        /**
         * Re-picking the selected chip is an ordinary misclick, not an error — and
         * must not append a row saying {@code MEDIUM → MEDIUM}. Nothing can delete
         * that row afterwards.
         */
        @Test
        @DisplayName("a change to the level it already holds writes nothing at all")
        void sameLevelIsANoOp() {
            Instant before = ticket.getPlannedCloseDate();

            TicketWire.Ticket answered = service.change(caller, TICKET_CODE, request("MEDIUM", null));

            assertThat(answered.level()).isEqualTo("MEDIUM");
            assertThat(ticket.getPlannedCloseDate()).isEqualTo(before);
            verifyNoInteractions(journal);
            verifyNoInteractions(slaLadder);
        }

        /**
         * The master's codes are upper-case and the lookup is exact, so a client
         * sending {@code "high"} should get High rather than a 400 reading "no such
         * level" for a level that plainly exists.
         */
        @Test
        @DisplayName("normalises the level before the lookup, so 'high' is HIGH")
        void normalisesTheLevel() {
            service.change(caller, TICKET_CODE, request(" high ", REASON));

            assertThat(ticket.getLevel()).isEqualTo("HIGH");
            assertThat(appendedEntry().getNewValue()).isEqualTo("HIGH");
        }

        /**
         * And the no-op is decided on the <em>normalised</em> value, so re-picking
         * through a client that lower-cases does not become a self-referential
         * history row that nothing can delete.
         */
        @Test
        @DisplayName("decides the no-op on the normalised value, so 'medium' on a MEDIUM ticket writes nothing")
        void normalisesBeforeTheNoOpCheck() {
            service.change(caller, TICKET_CODE, request("medium", null));

            assertThat(ticket.getLevel()).isEqualTo("MEDIUM");
            verify(journal, never()).append(any(TicketHistory.class));
        }

        /**
         * A level the priority master does not carry is the ladder's 400, and it
         * arrives <em>before</em> the ticket is written. S-12 lets an administrator
         * add a level without a release, so this is a lookup rather than a parse and
         * the generated client's four-value enum cannot stand in for it.
         */
        @Test
        @DisplayName("an unknown level is refused before anything moves")
        void unknownLevelIsRefused() {
            when(slaLadder.preview(eq(PROJECT), eq(4), eq("URGENT"), any(), any()))
                    .thenThrow(new UnknownLevelException("URGENT"));

            assertThatThrownBy(() -> service.change(caller, TICKET_CODE, request("URGENT", REASON)))
                    .isInstanceOf(UnknownLevelException.class);

            assertThat(ticket.getLevel()).isEqualTo("MEDIUM");
            verifyNoInteractions(journal);
        }

        /**
         * Scope first, and only once. A-035: out of scope and does not exist are the
         * same answer, and nothing below the guard runs.
         */
        @Test
        @DisplayName("an out-of-scope ticket is 404 and nothing else is consulted")
        void outOfScopeIs404() {
            when(tickets.requireByCode(any(), eq(TICKET_CODE))).thenThrow(new TicketNotFoundException());

            assertThatThrownBy(() -> service.change(caller, TICKET_CODE, request("HIGH", REASON)))
                    .isInstanceOf(TicketNotFoundException.class);

            verifyNoInteractions(journal);
            verifyNoInteractions(slaLadder);
        }
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private TicketHistory appendedEntry() {
        ArgumentCaptor<TicketHistory> captor = ArgumentCaptor.forClass(TicketHistory.class);
        verify(journal).append(captor.capture());
        return captor.getValue();
    }

    private static PriorityChangeDtos.ChangePriorityRequest request(String level, String reason) {
        return new PriorityChangeDtos.ChangePriorityRequest(level, reason);
    }

    private static Ticket mediumTicket() {
        Ticket t = new Ticket();
        ReflectionTestUtils.setField(t, "id", TICKET);
        ReflectionTestUtils.setField(t, "ticketCode", "CRM-26-00347");
        t.setProjectId(PROJECT);
        t.setTitle("Timesheet totals double-count a reopened cycle");
        t.setTaskTypeId(4);
        t.setLevel("MEDIUM");
        t.setOriginalLevel("MEDIUM");
        t.setStatus("IN_PROGRESS");
        t.setAssignedTo(ASSIGNEE);
        t.setEstimatedEffortHrs(new BigDecimal("12.00"));
        t.setCurrentCycleNo((short) 1);
        t.setCurrentIteration((short) 1);
        t.setCurrentStage("DEVELOPMENT");
        t.setDateReported(CYCLE_1_START);
        t.setPlannedCloseDate(Instant.parse("2026-08-10T12:00:00Z"));
        return t;
    }

    private static TicketCycle cycle(int cycleNo) {
        TicketCycle c = new TicketCycle();
        ReflectionTestUtils.setField(c, "id", 900L + cycleNo);
        c.setTicketId(TICKET);
        c.setCycleNo((short) cycleNo);
        c.setStartDate(CYCLE_1_START);
        c.setAssignedTo(ASSIGNEE);
        c.setLevel("MEDIUM");
        return c;
    }
}
