package com.edunext.edutrack.api.feature.tickets.cycles;

import com.edunext.edutrack.api.feature.notifications.events.TicketEventNotifier;
import com.edunext.edutrack.api.feature.tickets.PlannedCloseDateService;
import com.edunext.edutrack.api.feature.tickets.SlaResolution;
import com.edunext.edutrack.api.feature.tickets.TicketWire;
import com.edunext.edutrack.api.security.dev.DevPrincipal;
import com.edunext.edutrack.api.security.scope.ScopedTickets;
import com.edunext.edutrack.api.security.scope.TicketNotFoundException;
import com.edunext.edutrack.domain.journal.TicketJournal;
import com.edunext.edutrack.domain.masters.WorkingHoursService;
import com.edunext.edutrack.domain.tickets.Ticket;
import com.edunext.edutrack.domain.tickets.TicketCycle;
import com.edunext.edutrack.domain.tickets.TicketCycleRepository;
import com.edunext.edutrack.domain.tickets.TicketHistory;
import com.edunext.edutrack.domain.workflow.WorkflowStage;
import com.edunext.edutrack.domain.workflow.WorkflowStageRepository;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * C-038 · what §4.1 asks the reopen transaction to guarantee.
 *
 * <p>The assertions cluster on the four things that are invisible when they go
 * wrong:
 *
 * <ul>
 *   <li><b>the counters</b> — {@code current_cycle_no} and
 *       {@code current_iteration} are independent (§4A.2), and a reopen that
 *       moved the wrong one produces a ticket reading "Cycle 1 · Iteration 2"
 *       that nothing later contradicts;</li>
 *   <li><b>the effort</b> — cycle 1's hours must not be read, zeroed, copied or
 *       carried forward, and the grand total already includes them, so a helpful
 *       adjustment double-counts silently;</li>
 *   <li><b>the close date</b> — kept rather than cleared, the ticket vanishes
 *       from every "still open" query in the system while sitting in somebody's
 *       queue;</li>
 *   <li><b>the recomputed planned close date</b> — reused rather than recomputed,
 *       the ticket is born breached and Stream D alerts on it before anybody has
 *       read the reason.</li>
 * </ul>
 */
class ReopenServiceTest {

    private static final long TICKET = 347L;
    /** The service is addressed by CODE now; TICKET stays for the row id. */
    private static final String TICKET_CODE = "CRM-26-00347";
    private static final long PROJECT = 8L;
    private static final long TEMPLATE = 3L;
    private static final long ACTOR = 12L;
    private static final long CYCLE_1_ASSIGNEE = 44L;
    private static final long TICKET_ASSIGNEE = 55L;

    /** No sub-microsecond digits: what the service stores is truncated to micros. */
    private static final Instant NOW = Instant.parse("2026-08-17T09:30:00Z");

    private static final String REASON = "Client reports the defect has recurred in production.";

    private final ScopedTickets tickets = mock(ScopedTickets.class);
    private final TicketCycleRepository cycles = mock(TicketCycleRepository.class);
    private final TicketJournal journal = mock(TicketJournal.class);
    private final WorkflowStageRepository stages = mock(WorkflowStageRepository.class);
    private final PlannedCloseDateService slaLadder = mock(PlannedCloseDateService.class);
    private final WorkingHoursService workingHours = mock(WorkingHoursService.class);

    /**
     * D-037 · the §4B.6 producer this service now calls. Mocked and unasserted
     * here: what it sends is `TicketEventNotifierTest`'s subject, and this file
     * is about the write it must never interfere with.
     */
    private final TicketEventNotifier eventNotifier = mock(TicketEventNotifier.class);

    private final ReopenService service = new ReopenService(
            tickets, cycles, journal, stages, slaLadder, workingHours, eventNotifier,
            Clock.fixed(NOW, ZoneOffset.UTC));

    /**
     * The third argument is load-bearing, and CommentServiceTest's note on the
     * same line is why it is repeated here: {@code TestingAuthenticationToken}'s
     * two-argument constructor leaves {@code isAuthenticated()} false, and
     * {@code CallerIdentity.of} returns empty for an unauthenticated token. A
     * caller built the short way reaches the service as <em>nobody</em>, so the
     * history entry lands with a null actor and the failure is about the token
     * rather than about the reopen.
     */
    private final Authentication caller = new TestingAuthenticationToken(
            new DevPrincipal(ACTOR, "priya", "Priya Nair", "PM", List.of(PROJECT), List.of()),
            "n/a", "ticket.reopen");

    private Ticket ticket;
    private TicketCycle cycle1;

    @BeforeEach
    void setUp() {
        ticket = closedTicket();
        cycle1 = cycle(1, CYCLE_1_ASSIGNEE);

        when(tickets.requireByCode(any(), eq(TICKET_CODE))).thenReturn(ticket);
        when(cycles.findByTicketIdAndCycleNo(TICKET, (short) 1)).thenReturn(Optional.of(cycle1));
        when(cycles.save(any())).thenAnswer(call -> call.getArgument(0));
        when(stages.findByTemplateIdAndStageCode(TEMPLATE, "TRIAGE"))
                .thenReturn(Optional.of(stage("TRIAGE")));
        // The ladder has a target by default, so the recompute path is the one
        // under test unless a case says otherwise. Built through the canonical
        // constructor rather than SlaResolution.fromDefault, which is
        // package-private to feature.tickets — the same 16 working hours an
        // ORG_DEFAULT rung would answer with.
        when(slaLadder.resolve(PROJECT, 4, "HIGH")).thenReturn(new SlaResolution(
                SlaResolution.Source.ORG_DEFAULT, null, null, new BigDecimal("16.00")));
        when(workingHours.addWorkingHours(any(), any(), any(), any()))
                .thenReturn(Instant.parse("2026-08-19T13:30:00Z"));
    }

    // ── the ticket row ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("the ticket")
    class TicketRow {

        @Test
        @DisplayName("moves to REOPENED at cycle N+1 with reopen_count incremented")
        void movesToTheNextCycle() {
            ticket.setCurrentCycleNo((short) 2);
            ticket.setReopenCount((short) 1);
            when(cycles.findByTicketIdAndCycleNo(TICKET, (short) 2))
                    .thenReturn(Optional.of(cycle(2, CYCLE_1_ASSIGNEE)));

            TicketWire.Ticket answered = service.reopen(caller, TICKET_CODE, request());

            assertThat(ticket.getStatus()).isEqualTo("REOPENED");
            assertThat(ticket.getCurrentCycleNo()).isEqualTo((short) 3);
            assertThat(ticket.getReopenCount()).isEqualTo((short) 2);
            assertThat(ticket.isReopened()).isTrue();
            // The answer is the ticket, so the wire cannot disagree with the row.
            assertThat(answered.status()).isEqualTo("REOPENED");
            assertThat(answered.currentCycleNo()).isEqualTo(3);
            assertThat(answered.reopenCount()).isEqualTo(2);
        }

        /**
         * The one that costs most quietly. {@code pcd_open} is
         * {@code IF(actual_close_date IS NULL, planned_close_date, NULL)}, so a
         * reopened ticket that kept its close date is absent from every open-ticket
         * index and Stream D's scanner never looks at it again.
         */
        @Test
        @DisplayName("clears actual_close_date, or it is invisible to every open-ticket query")
        void clearsTheCloseDate() {
            assertThat(ticket.getActualCloseDate()).isNotNull();

            service.reopen(caller, TICKET_CODE, request());

            assertThat(ticket.getActualCloseDate()).isNull();
        }

        /**
         * §4A.2's two counters, asserted together because the mistake is always a
         * swap. Iteration is <em>within</em> a cycle, so a new cycle starts at 1;
         * rework_count spans the ticket's whole life and A-070 reports on it.
         */
        @Test
        @DisplayName("resets current_iteration to 1 and leaves rework_count alone")
        void resetsIterationButNotReworkCount() {
            ticket.setCurrentIteration((short) 4);
            ticket.setReworkCount((short) 6);

            service.reopen(caller, TICKET_CODE, request());

            assertThat(ticket.getCurrentIteration()).isEqualTo((short) 1);
            assertThat(ticket.getReworkCount())
                    .as("rework_count counts backward moves over the ticket's whole life")
                    .isEqualTo((short) 6);
        }

        @Test
        @DisplayName("enters the restart stage and stamps stage_entered_at")
        void entersTheRestartStage() {
            when(stages.findByTemplateIdAndStageCode(TEMPLATE, "DEV"))
                    .thenReturn(Optional.of(stage("DEV")));

            service.reopen(caller, TICKET_CODE, new ReopenDtos.ReopenRequest(
                    REASON, "DEV", null, null, null));

            assertThat(ticket.getCurrentStage()).isEqualTo("DEV");
            assertThat(ticket.getStageEnteredAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("keeps level and original_level — G-4 says a close does not revert them")
        void keepsBothLevels() {
            service.reopen(caller, TICKET_CODE, request());

            assertThat(ticket.getLevel()).isEqualTo("HIGH");
            assertThat(ticket.getOriginalLevel()).isEqualTo("MEDIUM");
        }
    }

    // ── 🔴 cycle 1's effort is never touched ─────────────────────────────────

    @Nested
    @DisplayName("cycle N's effort")
    class EffortIsUntouched {

        /**
         * <b>Never touched</b>, not "preserved". {@code total_effort_hrs} is
         * already Σ across all cycles, so anything added here double-counts on the
         * spot; the sealed cycle's own {@code effort_hrs} is its final figure and
         * the §4A.4 grid reports it as "this cycle" for ever.
         */
        @Test
        @DisplayName("is neither read nor written — not the grand total, not the sealed cycle's")
        void neitherReadNorWritten() {
            ticket.setTotalEffortHrs(new BigDecimal("38.00"));
            cycle1.setEffortHrs(new BigDecimal("24.50"));

            service.reopen(caller, TICKET_CODE, request());

            assertThat(ticket.getTotalEffortHrs())
                    .as("Σ across all cycles already includes cycle 1")
                    .isEqualByComparingTo("38.00");
            assertThat(cycle1.getEffortHrs())
                    .as("the sealed cycle's final figure stops moving because the cycle stopped")
                    .isEqualByComparingTo("24.50");
        }

        /**
         * The strongest form of the claim: the journal's effort methods are not
         * called at all. An assertion on the numbers could pass while an effort log
         * was appended and netted to zero; this cannot.
         */
        @Test
        @DisplayName("is not appended to, corrected, or even read through the journal")
        void theJournalSeesOnlyTheHistoryAppend() {
            service.reopen(caller, TICKET_CODE, request());

            verify(journal).append(any(TicketHistory.class));
            verify(journal, never()).effortFor(anyLong(), any());
            verify(journal, never()).reverseEffort(anyLong(), any());
        }

        @Test
        @DisplayName("starts the new cycle at zero rather than carrying anything forward")
        void theNewCycleStartsAtZero() {
            cycle1.setEffortHrs(new BigDecimal("24.50"));

            service.reopen(caller, TICKET_CODE, request());

            assertThat(savedCycle().getEffortHrs()).isEqualByComparingTo("0.00");
        }
    }

    // ── the new cycle ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("the new cycle")
    class NewCycle {

        @Test
        @DisplayName("is inserted at N+1 with the reason, the actor and a fresh start date")
        void isInsertedWithItsOwnFacts() {
            service.reopen(caller, TICKET_CODE, request());

            TicketCycle saved = savedCycle();
            assertThat(saved.getTicketId()).isEqualTo(TICKET);
            assertThat(saved.getCycleNo()).isEqualTo((short) 2);
            assertThat(saved.getStartDate()).isEqualTo(NOW);
            assertThat(saved.getReopenReason()).isEqualTo(REASON);
            assertThat(saved.getAssignedBy()).isEqualTo(ACTOR);
            assertThat(saved.getLevel()).isEqualTo("HIGH");
            assertThat(saved.isSealed()).isFalse();
            assertThat(saved.getActualCloseDate()).isNull();
        }

        @Test
        @DisplayName("seals cycle N, which is what makes C-053 render it read-only")
        void sealsTheClosingCycle() {
            assertThat(cycle1.isSealed()).isFalse();

            service.reopen(caller, TICKET_CODE, request());

            assertThat(cycle1.isSealed()).isTrue();
        }

        /**
         * S-22's "defaults to previous", where previous is the cycle's holder and
         * not the ticket's — the two differ whenever a ticket is reassigned after
         * closure, and the cycle's is who actually did the work being complained
         * about.
         */
        @Test
        @DisplayName("defaults the assignee to the closing cycle's, not the ticket's")
        void defaultsTheAssigneeToTheClosingCycles() {
            service.reopen(caller, TICKET_CODE, request());

            assertThat(savedCycle().getAssignedTo()).isEqualTo(CYCLE_1_ASSIGNEE);
            assertThat(ticket.getAssignedTo()).isEqualTo(CYCLE_1_ASSIGNEE);
        }

        @Test
        @DisplayName("falls back to the ticket's assignee when the closing cycle names nobody")
        void fallsBackToTheTicketsAssignee() {
            cycle1.setAssignedTo(null);

            service.reopen(caller, TICKET_CODE, request());

            assertThat(savedCycle().getAssignedTo()).isEqualTo(TICKET_ASSIGNEE);
        }

        @Test
        @DisplayName("takes the requested assignee when one is given")
        void takesTheRequestedAssignee() {
            service.reopen(caller, TICKET_CODE, new ReopenDtos.ReopenRequest(
                    REASON, null, 99L, null, null));

            assertThat(savedCycle().getAssignedTo()).isEqualTo(99L);
            assertThat(ticket.getAssignedTo()).isEqualTo(99L);
        }
    }

    // ── the planned close date ───────────────────────────────────────────────

    @Nested
    @DisplayName("the planned close date")
    class PlannedCloseDate {

        /**
         * Recomputed from now, never cycle N's — that one is in the past, and a
         * reopened ticket carrying it is born breached.
         */
        @Test
        @DisplayName("is recomputed from the SLA ladder when the caller omits it")
        void isRecomputedFromTheLadder() {
            Instant recomputed = Instant.parse("2026-08-19T13:30:00Z");
            assertThat(cycle1.getPlannedCloseDate()).isBefore(NOW);

            service.reopen(caller, TICKET_CODE, request());

            assertThat(savedCycle().getPlannedCloseDate()).isEqualTo(recomputed);
            assertThat(ticket.getPlannedCloseDate()).isEqualTo(recomputed);
            // Measured from the reopen instant, with the new assignee's leave
            // stopping the clock — not from the ticket's date reported.
            verify(workingHours).addWorkingHours(
                    eq(NOW), eq(new BigDecimal("16.00")), eq(PROJECT), eq(CYCLE_1_ASSIGNEE));
        }

        @Test
        @DisplayName("is the caller's when one is supplied, with no ladder lookup at all")
        void prefersTheCallersDate() {
            Instant chosen = Instant.parse("2026-08-25T12:00:00Z");

            service.reopen(caller, TICKET_CODE, new ReopenDtos.ReopenRequest(
                    REASON, null, null, chosen, null));

            assertThat(ticket.getPlannedCloseDate()).isEqualTo(chosen);
            verifyNoInteractions(workingHours);
        }

        /**
         * Null is the honest answer, not a refusal: the ticket genuinely has no
         * resolution target, and S-22's own field is where the date comes from in
         * practice.
         */
        @Test
        @DisplayName("is null when the ladder has nothing to say, and the reopen still succeeds")
        void isNullWhenTheLadderHasNoTarget() {
            when(slaLadder.resolve(PROJECT, 4, "HIGH")).thenReturn(
                    new SlaResolution(SlaResolution.Source.NONE, null, null, null));

            service.reopen(caller, TICKET_CODE, request());

            assertThat(ticket.getStatus()).isEqualTo("REOPENED");
            assertThat(ticket.getPlannedCloseDate()).isNull();
            verifyNoInteractions(workingHours);
        }
    }

    // ── the history entry ────────────────────────────────────────────────────

    @Nested
    @DisplayName("the history entry")
    class HistoryEntry {

        /**
         * Stamped with the <b>new</b> cycle: the History tab filtered to cycle 2
         * must open with the reason cycle 2 exists. B-007's fixture writes this
         * exact shape, and C-034 interleaves both into one stream — a real reopen
         * that rendered differently from a seeded one would read as two events.
         */
        @Test
        @DisplayName("is a CLOSED → REOPENED status change stamped with the new cycle")
        void isAStatusChangeOnTheNewCycle() {
            service.reopen(caller, TICKET_CODE, request());

            TicketHistory entry = appendedHistory();
            assertThat(entry.getTicketId()).isEqualTo(TICKET);
            assertThat(entry.getCycleNo()).isEqualTo((short) 2);
            assertThat(entry.getEventType()).isEqualTo("REOPENED");
            assertThat(entry.getFieldName()).isEqualTo("status");
            assertThat(entry.getOldValue()).isEqualTo("CLOSED");
            assertThat(entry.getNewValue()).isEqualTo("REOPENED");
            assertThat(entry.getRemarks()).isEqualTo(REASON);
        }

        /**
         * The journal refuses a row whose {@code actor_id} and {@code actor_type}
         * disagree — no actor means SYSTEM and SYSTEM means no actor — so the pair
         * is asserted rather than assumed.
         */
        @Test
        @DisplayName("names the actor, with actor_type agreeing")
        void namesTheActor() {
            service.reopen(caller, TICKET_CODE, request());

            TicketHistory entry = appendedHistory();
            assertThat(entry.getActorId()).isEqualTo(ACTOR);
            assertThat(entry.getActorType()).isEqualTo("USER");
        }

        /**
         * The chain columns belong to the journal, computed under the per-ticket
         * lock. A caller arriving with either set is refused outright (A-042), so
         * this asserts the service leaves them alone.
         */
        @Test
        @DisplayName("carries no hash of its own — the journal computes the chain")
        void carriesNoHash() {
            service.reopen(caller, TICKET_CODE, request());

            TicketHistory entry = appendedHistory();
            assertThat(entry.getPrevHash()).isNull();
            assertThat(entry.getRowHash()).isNull();
            assertThat(entry.isCorrection()).isFalse();
            assertThat(entry.getCorrectsEntryId()).isNull();
        }
    }

    // ── refusals ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("refusals")
    class Refusals {

        /**
         * The scope check is first, and nothing else runs. A caller who got past it
         * would reopen another project's ticket and no later write would notice.
         */
        @Test
        @DisplayName("an out-of-scope or absent ticket is 404, and nothing is written")
        void outOfScopeIs404() {
            when(tickets.requireByCode(any(), eq(TICKET_CODE))).thenThrow(new TicketNotFoundException());

            assertThatThrownBy(() -> service.reopen(caller, TICKET_CODE, request()))
                    .isInstanceOf(TicketNotFoundException.class);

            verifyNoInteractions(cycles, journal);
        }

        @Test
        @DisplayName("RESOLVED is 422 — it has not been signed off, so there is no cycle to seal")
        void resolvedIs422() {
            ticket.setStatus("RESOLVED");

            assertThatThrownBy(() -> service.reopen(caller, TICKET_CODE, request()))
                    .isInstanceOf(TicketNotClosedException.class)
                    .hasMessageContaining("RESOLVED rather than CLOSED");

            verifyNoInteractions(journal);
        }

        @Test
        @DisplayName("an already-reopened ticket is 422 too, and its counters do not move")
        void alreadyReopenedIs422() {
            ticket.setStatus("REOPENED");

            assertThatThrownBy(() -> service.reopen(caller, TICKET_CODE, request()))
                    .isInstanceOf(TicketNotClosedException.class);

            assertThat(ticket.getCurrentCycleNo()).isEqualTo((short) 1);
            assertThat(ticket.getReopenCount()).isZero();
            verifyNoInteractions(journal);
        }

        /**
         * A stage the template does not define would leave the ticket with a
         * {@code current_stage} its ribbon cannot draw — no current segment, on a
         * page that shows nothing else wrong.
         */
        @Test
        @DisplayName("a restart stage outside the ticket's template is 400")
        void unknownRestartStageIs400() {
            when(stages.findByTemplateIdAndStageCode(TEMPLATE, "SIGNOFF"))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.reopen(caller, TICKET_CODE, new ReopenDtos.ReopenRequest(
                    REASON, "SIGNOFF", null, null, null)))
                    .isInstanceOf(UnknownRestartStageException.class)
                    .hasMessageContaining("SIGNOFF");

            verifyNoInteractions(journal);
        }

        /**
         * {@code workflow_template_id} is nullable and tickets predating B's
         * designer have none. A reopen is not the moment to start refusing those,
         * and there is nothing to validate against.
         */
        @Test
        @DisplayName("a ticket with no workflow template is not refused")
        void noTemplateIsNotRefused() {
            ReflectionTestUtils.setField(ticket, "workflowTemplateId", null);

            service.reopen(caller, TICKET_CODE, new ReopenDtos.ReopenRequest(
                    REASON, "ANYTHING", null, null, null));

            assertThat(ticket.getCurrentStage()).isEqualTo("ANYTHING");
            verifyNoInteractions(stages);
        }

        /**
         * Cycle 1 is created with the ticket (§4.1), so a closed ticket with no row
         * for its current cycle is a data defect. Inserting N+1 over the hole would
         * make the ticket permanently claim a history it has no record of.
         */
        @Test
        @DisplayName("a missing cycle row fails loudly rather than inserting over the hole")
        void missingCycleRowFailsLoudly() {
            when(cycles.findByTicketIdAndCycleNo(TICKET, (short) 1)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.reopen(caller, TICKET_CODE, request()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("nothing to seal");

            verify(cycles, never()).save(any());
            verifyNoInteractions(journal);
        }
    }

    // ── the revised estimate ─────────────────────────────────────────────────

    @Nested
    @DisplayName("the revised estimate")
    class RevisedEstimate {

        @Test
        @DisplayName("replaces the ticket's estimate when supplied")
        void replacesTheEstimate() {
            service.reopen(caller, TICKET_CODE, new ReopenDtos.ReopenRequest(
                    REASON, null, null, null, new BigDecimal("6.50")));

            assertThat(ticket.getEstimatedEffortHrs()).isEqualByComparingTo("6.50");
        }

        /**
         * Null means "I have no revised figure", which is not zero. Overwriting a
         * good estimate with nothing is the one default here that would destroy
         * information rather than merely guess.
         */
        @Test
        @DisplayName("leaves a good estimate alone when omitted")
        void leavesTheEstimateAloneWhenOmitted() {
            service.reopen(caller, TICKET_CODE, request());

            assertThat(ticket.getEstimatedEffortHrs()).isEqualByComparingTo("12.00");
        }
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private static ReopenDtos.ReopenRequest request() {
        return new ReopenDtos.ReopenRequest(REASON, null, null, null, null);
    }

    private Ticket closedTicket() {
        Ticket t = new Ticket();
        ReflectionTestUtils.setField(t, "id", TICKET);
        ReflectionTestUtils.setField(t, "ticketCode", "CRM-26-00347");
        ReflectionTestUtils.setField(t, "workflowTemplateId", TEMPLATE);
        t.setProjectId(PROJECT);
        t.setTitle("Timesheet totals double-count a reopened cycle");
        t.setTaskTypeId(4);
        // level raised to HIGH after the fact; original_level never moves (A-070).
        t.setLevel("HIGH");
        t.setOriginalLevel("MEDIUM");
        t.setStatus("CLOSED");
        t.setAssignedTo(TICKET_ASSIGNEE);
        t.setEstimatedEffortHrs(new BigDecimal("12.00"));
        t.setCurrentCycleNo((short) 1);
        t.setCurrentIteration((short) 1);
        t.setCurrentStage("CLOSED");
        t.setPlannedCloseDate(Instant.parse("2026-08-10T12:00:00Z"));
        t.setActualCloseDate(Instant.parse("2026-08-11T16:20:00Z"));
        return t;
    }

    private static TicketCycle cycle(int cycleNo, Long assignee) {
        TicketCycle c = new TicketCycle();
        ReflectionTestUtils.setField(c, "id", 900L + cycleNo);
        c.setTicketId(TICKET);
        c.setCycleNo((short) cycleNo);
        c.setStartDate(Instant.parse("2026-08-03T09:00:00Z"));
        c.setAssignedTo(assignee);
        c.setLevel("HIGH");
        c.setPlannedCloseDate(Instant.parse("2026-08-10T12:00:00Z"));
        c.setActualCloseDate(Instant.parse("2026-08-11T16:20:00Z"));
        return c;
    }

    private static WorkflowStage stage(String code) {
        WorkflowStage s = new WorkflowStage();
        ReflectionTestUtils.setField(s, "stageCode", code);
        return s;
    }

    /** The row handed to {@code cycles.save} — the insert, never the seal. */
    private TicketCycle savedCycle() {
        ArgumentCaptor<TicketCycle> captor = ArgumentCaptor.forClass(TicketCycle.class);
        verify(cycles).save(captor.capture());
        return captor.getValue();
    }

    private TicketHistory appendedHistory() {
        ArgumentCaptor<TicketHistory> captor = ArgumentCaptor.forClass(TicketHistory.class);
        verify(journal).append(captor.capture());
        return captor.getValue();
    }
}
