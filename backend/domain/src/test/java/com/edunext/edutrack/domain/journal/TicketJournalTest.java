package com.edunext.edutrack.domain.journal;

import com.edunext.edutrack.common.canonical.CanonicalJsonException;
import com.edunext.edutrack.domain.tickets.Ticket;
import com.edunext.edutrack.domain.tickets.TicketEffortLog;
import com.edunext.edutrack.domain.tickets.TicketEffortLogRepository;
import com.edunext.edutrack.domain.tickets.TicketHistory;
import com.edunext.edutrack.domain.tickets.TicketHistoryRepository;
import com.edunext.edutrack.domain.tickets.TicketRepository;
import com.edunext.edutrack.domain.workflow.TicketStageTransition;
import com.edunext.edutrack.domain.workflow.TicketStageTransitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A-040 · every rule {@link TicketJournal} adds on top of {@code insert()},
 * proved against mocks so the whole set runs on every build.
 *
 * <p>{@code TicketJournalIT} proves the two things mocks cannot — that the lock
 * is a real {@code SELECT … FOR UPDATE} against MySQL, and that
 * {@code MANDATORY} actually refuses a caller with no transaction. Everything
 * else is a decision this class makes before it touches the database, and
 * belongs here where it costs nothing to run.
 *
 * <p><b>Every rejection also asserts that nothing was inserted.</b> A guard that
 * throws after the append has already gone in is worse than no guard: the row is
 * in a table that cannot be corrected by deleting it.
 */
class TicketJournalTest {

    private static final Long TICKET = 42L;
    private static final Instant ENTERED = Instant.parse("2026-08-14T09:00:00Z");

    private TicketRepository tickets;
    private TicketHistoryRepository history;
    private TicketEffortLogRepository effortLogs;
    private TicketStageTransitionRepository transitions;
    private TicketJournal journal;

    @BeforeEach
    void setUp() {
        tickets = mock(TicketRepository.class);
        history = mock(TicketHistoryRepository.class);
        effortLogs = mock(TicketEffortLogRepository.class);
        transitions = mock(TicketStageTransitionRepository.class);
        journal = new TicketJournal(tickets, history, effortLogs, transitions);
    }

    private void ticketExists() {
        when(tickets.findByIdForUpdate(TICKET)).thenReturn(Optional.of(new Ticket()));
    }

    private TicketHistory historyEntry() {
        TicketHistory entry = new TicketHistory();
        entry.setTicketId(TICKET);
        entry.setEventType("STATUS_CHANGED");
        entry.setActorId(7L);
        entry.setActorType("USER");
        return entry;
    }

    private TicketEffortLog effortLog() {
        TicketEffortLog log = new TicketEffortLog();
        log.setTicketId(TICKET);
        log.setCycleNo((short) 1);
        log.setUserId(7L);
        log.setWorkDate(LocalDate.of(2026, 8, 14));
        log.setHours(BigDecimal.valueOf(3));
        return log;
    }

    private TicketStageTransition hop() {
        TicketStageTransition hop = new TicketStageTransition();
        hop.setTicketId(TICKET);
        hop.setCycleNo((short) 1);
        hop.setSeqNo(1);
        hop.setToStage("DEV");
        hop.setActionCode("FORWARD");
        hop.setEnteredAt(ENTERED);
        return hop;
    }

    // ------------------------------------------------------------------
    // The lock
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the per-ticket lock (PLAN.md §3.7)")
    class TheLock {

        @Test
        void isTakenBeforeEveryAppend() {
            ticketExists();
            when(transitions.findByCurrentTicketId(TICKET)).thenReturn(Optional.empty());

            journal.append(historyEntry());
            journal.append(effortLog());
            journal.append(hop());

            InOrder order = inOrder(tickets, history, effortLogs, transitions);
            order.verify(tickets).findByIdForUpdate(TICKET);
            order.verify(history).insert(any());
            order.verify(tickets).findByIdForUpdate(TICKET);
            order.verify(effortLogs).insert(any());
            order.verify(tickets).findByIdForUpdate(TICKET);
            order.verify(transitions).insert(any());
        }

        /**
         * The foreign key would catch this eventually, but as an opaque
         * constraint violation on flush — possibly several appends later, and
         * naming the constraint rather than the mistake.
         */
        @Test
        void refusesAnAppendToATicketThatDoesNotExist() {
            when(tickets.findByIdForUpdate(TICKET)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> journal.append(historyEntry()))
                    .isInstanceOf(AppendRejectedException.class)
                    .hasMessageContaining("no ticket 42");
            verifyNoInteractions(history);
        }

        @Test
        void refusesAnAppendWithNoTicketAtAll() {
            TicketHistory orphan = historyEntry();
            orphan.setTicketId(null);

            assertThatThrownBy(() -> journal.append(orphan))
                    .isInstanceOf(AppendRejectedException.class)
                    .hasMessageContaining("ticket_id");
            verifyNoInteractions(tickets, history);
        }
    }

    // ------------------------------------------------------------------
    // What the caller may not bring
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the chain columns and the id belong to the journal")
    class NotTheCallersToWrite {

        @Test
        void aRowCarryingAnIdIsARewriteAttempt() {
            TicketHistory resave = historyEntry();
            resave.setId(99L);

            assertThatThrownBy(() -> journal.append(resave))
                    .isInstanceOf(AppendRejectedException.class)
                    .hasMessageContaining("already carries id 99");
            verifyNoInteractions(tickets, history);
        }

        /**
         * A-042 computes the chain under the lock. A caller that arrives with a
         * {@code prevHash} has read a tail without one, which is the fork this
         * whole design exists to prevent — and would be the one call site A-042
         * has to unwind rather than build on.
         */
        @Test
        void aCallerSuppliedPrevHashIsRefused() {
            TicketHistory preHashed = historyEntry();
            preHashed.setPrevHash("a".repeat(64));

            assertThatThrownBy(() -> journal.append(preHashed))
                    .isInstanceOf(AppendRejectedException.class)
                    .hasMessageContaining("written by the journal");
            verifyNoInteractions(tickets, history);
        }

        @Test
        void aCallerSuppliedRowHashIsRefused() {
            TicketEffortLog preHashed = effortLog();
            preHashed.setRowHash("b".repeat(64));

            assertThatThrownBy(() -> journal.append(preHashed))
                    .isInstanceOf(AppendRejectedException.class)
                    .hasMessageContaining("written by the journal");
            verifyNoInteractions(tickets, effortLogs);
        }
    }

    // ------------------------------------------------------------------
    // History
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("history")
    class History {

        @Test
        void appendsAWellFormedEntry() {
            ticketExists();
            TicketHistory entry = historyEntry();

            journal.append(entry);

            verify(history).insert(entry);
        }

        @Test
        void refusesAnEntryWithNoEventType() {
            TicketHistory blank = historyEntry();
            blank.setEventType("  ");

            assertThatThrownBy(() -> journal.append(blank))
                    .isInstanceOf(AppendRejectedException.class)
                    .hasMessageContaining("event_type");
            verifyNoInteractions(history);
        }

        /**
         * The row that is in the audit log and cannot be attributed to anyone:
         * it claims a human actor and names nobody.
         */
        @Test
        void refusesAUserEntryWithNoActor() {
            TicketHistory unattributable = historyEntry();
            unattributable.setActorId(null);

            assertThatThrownBy(() -> journal.append(unattributable))
                    .isInstanceOf(AppendRejectedException.class)
                    .hasMessageContaining("actor_id and actor_type must agree");
            verifyNoInteractions(history);
        }

        @Test
        void refusesASystemEntryThatNamesAnActor() {
            TicketHistory confused = historyEntry();
            confused.setActorType("SYSTEM");

            assertThatThrownBy(() -> journal.append(confused))
                    .isInstanceOf(AppendRejectedException.class)
                    .hasMessageContaining("actor_id and actor_type must agree");
            verifyNoInteractions(history);
        }

        /** D-028's auto-escalation row: SYSTEM, no actor. */
        @Test
        void acceptsASystemEntryWithNoActor() {
            ticketExists();
            TicketHistory escalation = historyEntry();
            escalation.setActorType("SYSTEM");
            escalation.setActorId(null);

            journal.append(escalation);

            verify(history).insert(escalation);
        }
    }

    // ------------------------------------------------------------------
    // The compensating-entry pair
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the correction pair (A-043's storage invariant)")
    class Corrections {

        @Test
        void refusesACorrectionThatPointsAtNothing() {
            TicketEffortLog reversal = effortLog();
            reversal.setHours(BigDecimal.valueOf(-3));
            reversal.setCorrection(true);

            assertThatThrownBy(() -> journal.append(reversal))
                    .isInstanceOf(AppendRejectedException.class)
                    .hasMessageContaining("corrects_entry_id");
            verifyNoInteractions(effortLogs);
        }

        /**
         * The other direction, and the more dangerous one: a row naming its
         * target without the flag is invisible to every query filtering on
         * {@code is_correction}, so the original is counted and so is the
         * reversal.
         */
        @Test
        void refusesATargetedRowThatIsNotFlagged() {
            TicketHistory reversal = historyEntry();
            reversal.setCorrectsEntryId(11L);

            assertThatThrownBy(() -> journal.append(reversal))
                    .isInstanceOf(AppendRejectedException.class)
                    .hasMessageContaining("is_correction");
            verifyNoInteractions(history);
        }

        @Test
        void acceptsAWellFormedReversal() {
            ticketExists();
            TicketEffortLog reversal = effortLog();
            reversal.setHours(BigDecimal.valueOf(-3));
            reversal.setCorrection(true);
            reversal.setCorrectsEntryId(11L);

            journal.append(reversal);

            verify(effortLogs).insert(reversal);
        }
    }

    // ------------------------------------------------------------------
    // Stage transitions
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("stage transitions")
    class Ribbon {

        @Test
        void appendsAnOpenHopWhenNoOtherIsOpen() {
            ticketExists();
            when(transitions.findByCurrentTicketId(TICKET)).thenReturn(Optional.empty());
            TicketStageTransition hop = hop();

            journal.append(hop);

            verify(transitions).insert(hop);
        }

        /**
         * The invariant behind A-009's {@code current_ticket_id}. Without this
         * the second open hop is accepted and the failure surfaces later, as
         * {@code findByCurrentTicketId} throwing on an unrelated detail-page
         * load.
         */
        @Test
        void refusesASecondOpenHop() {
            ticketExists();
            TicketStageTransition stillOpen = hop();
            stillOpen.setId(5L);
            stillOpen.setToStage("QA");
            when(transitions.findByCurrentTicketId(TICKET)).thenReturn(Optional.of(stillOpen));

            assertThatThrownBy(() -> journal.append(hop()))
                    .isInstanceOf(AppendRejectedException.class)
                    .hasMessageContaining("still in stage QA")
                    .hasMessageContaining("Seal it before appending");
            verify(transitions, never()).insert(any());
        }

        @Test
        void refusesAHopThatArrivesAlreadyClosed() {
            TicketStageTransition closed = hop();
            closed.setExitedAt(ENTERED.plus(1, ChronoUnit.HOURS));

            assertThatThrownBy(() -> journal.append(closed))
                    .isInstanceOf(AppendRejectedException.class)
                    .hasMessageContaining("appended open and closed by seal()");
            verifyNoInteractions(transitions);
        }

        @Test
        void refusesABackwardMoveWithNoReason() {
            TicketStageTransition rework = hop();
            rework.setActionCode("REWORK");

            assertThatThrownBy(() -> journal.append(rework))
                    .isInstanceOf(AppendRejectedException.class)
                    .hasMessageContaining("backward move")
                    .hasMessageContaining("4A.6");
            verifyNoInteractions(transitions);
        }

        @Test
        void acceptsABackwardMoveThatCarriesOne() {
            ticketExists();
            when(transitions.findByCurrentTicketId(TICKET)).thenReturn(Optional.empty());
            TicketStageTransition rework = hop();
            rework.setActionCode("VERIFY_FAILED");
            rework.setReason("Client could not reproduce the fix on UAT.");

            journal.append(rework);

            verify(transitions).insert(rework);
        }

        /** FORWARD, SKIP and OVERRIDE are not backward moves and need no reason. */
        @Test
        void doesNotDemandAReasonForAForwardMove() {
            ticketExists();
            when(transitions.findByCurrentTicketId(TICKET)).thenReturn(Optional.empty());
            TicketStageTransition skip = hop();
            skip.setActionCode("SKIP");

            journal.append(skip);

            verify(transitions).insert(skip);
        }
    }

    // ------------------------------------------------------------------
    // Sealing
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("seal — the one permitted mutation")
    class Sealing {

        private TicketStageTransition open() {
            TicketStageTransition open = hop();
            open.setId(5L);
            return open;
        }

        @Test
        void sealsAnOpenHopAndReportsThatItDid() {
            when(transitions.findById(5L)).thenReturn(Optional.of(open()));
            ticketExists();
            Instant exited = ENTERED.plus(4, ChronoUnit.HOURS);
            when(transitions.seal(5L, exited, 90)).thenReturn(1);

            assertThat(journal.seal(5L, exited, 90)).isTrue();
            verify(tickets).findByIdForUpdate(TICKET);
        }

        /**
         * A double-seal is a no-op rather than an error. The repository's
         * {@code exited_at is null} predicate is what decides, so the answer
         * comes from the database rather than from a read-then-write race here.
         */
        @Test
        void reportsFalseWhenSomebodyElseSealedItFirst() {
            when(transitions.findById(5L)).thenReturn(Optional.of(open()));
            ticketExists();
            Instant exited = ENTERED.plus(4, ChronoUnit.HOURS);
            when(transitions.seal(5L, exited, 90)).thenReturn(0);

            assertThat(journal.seal(5L, exited, 90)).isFalse();
        }

        @Test
        void refusesToSealAHopThatDoesNotExist() {
            when(transitions.findById(5L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> journal.seal(5L, ENTERED, 0))
                    .isInstanceOf(AppendRejectedException.class)
                    .hasMessageContaining("no stage transition 5");
            verify(transitions, never()).seal(anyLong(), any(), any());
        }

        @Test
        void refusesAnExitBeforeTheEntry() {
            when(transitions.findById(5L)).thenReturn(Optional.of(open()));
            ticketExists();

            assertThatThrownBy(() -> journal.seal(5L, ENTERED.minus(1, ChronoUnit.HOURS), 0))
                    .isInstanceOf(AppendRejectedException.class)
                    .hasMessageContaining("before it was entered");
            verify(transitions, never()).seal(anyLong(), any(), any());
        }

        /**
         * The shape this mistake actually takes: 4 hours elapsed, and the caller
         * passes 14400 because it reached for seconds, or the whole calendar gap
         * because it reached for {@code Duration.between}. Both are plausible
         * numbers for a stage duration and nothing else would ever notice.
         */
        @Test
        void refusesADurationLongerThanTheWallClockGap() {
            when(transitions.findById(5L)).thenReturn(Optional.of(open()));
            ticketExists();
            Instant exited = ENTERED.plus(4, ChronoUnit.HOURS);

            assertThatThrownBy(() -> journal.seal(5L, exited, 14_400))
                    .isInstanceOf(AppendRejectedException.class)
                    .hasMessageContaining("exceeds the 240 wall-clock minutes");
            verify(transitions, never()).seal(anyLong(), any(), any());
        }

        /**
         * A stage entirely inside working hours has working minutes equal to
         * elapsed minutes, so the boundary is a real case rather than a corner
         * one — the allowance rounds up for exactly this reason.
         */
        @Test
        void acceptsADurationEqualToTheWallClockGap() {
            when(transitions.findById(5L)).thenReturn(Optional.of(open()));
            ticketExists();
            Instant exited = ENTERED.plus(4, ChronoUnit.HOURS);
            when(transitions.seal(5L, exited, 240)).thenReturn(1);

            assertThat(journal.seal(5L, exited, 240)).isTrue();
        }

        /**
         * Null is legitimate — the reopen path seals cycle 1's CLOSED hop, and
         * "how long it sat closed" is not a stage duration anybody reports.
         */
        @Test
        void acceptsANullDuration() {
            when(transitions.findById(5L)).thenReturn(Optional.of(open()));
            ticketExists();
            Instant exited = ENTERED.plus(4, ChronoUnit.HOURS);
            when(transitions.seal(5L, exited, null)).thenReturn(1);

            assertThat(journal.seal(5L, exited, null)).isTrue();
        }
    }

    // ------------------------------------------------------------------
    // The chain
    // ------------------------------------------------------------------

    /**
     * A-042 · the link itself. What mocks can prove is the <i>shape</i> — that
     * the tail is read after the lock, that the first row is a genesis row, that
     * the second points at the first, and that an unchained tail is refused.
     * That the lock actually serialises two concurrent appends is A-045's, and
     * needs a real database.
     */
    @Nested
    @DisplayName("the hash chain (A-042)")
    class TheChain {

        private static final String SOME_HASH =
                "4f3c2b1a09e8d7c6b5a4938271605f4e3d2c1b0a9f8e7d6c5b4a39281706f5e4";

        @Test
        @DisplayName("the first row of a chain carries no prev_hash and is still hashed")
        void theGenesisRow() {
            ticketExists();
            when(history.findFirstByTicketIdOrderByIdDesc(TICKET)).thenReturn(Optional.empty());

            TicketHistory entry = historyEntry();
            journal.append(entry);

            assertThat(entry.getPrevHash())
                    .as("nothing precedes it, and NULL says so — see ChainDigest on why not a sentinel")
                    .isNull();
            assertThat(entry.getRowHash())
                    .as("a genesis row is still a link; only an unchained row has both columns null")
                    .hasSize(ChainDigest.HASH_LENGTH)
                    .matches("[0-9a-f]+");
            verify(history).insert(entry);
        }

        @Test
        @DisplayName("the next row points at the tail's row_hash")
        void linksToThePredecessor() {
            ticketExists();
            TicketHistory tail = historyEntry();
            tail.setRowHash(SOME_HASH);
            when(history.findFirstByTicketIdOrderByIdDesc(TICKET)).thenReturn(Optional.of(tail));

            TicketHistory entry = historyEntry();
            journal.append(entry);

            assertThat(entry.getPrevHash()).isEqualTo(SOME_HASH);
            assertThat(entry.getRowHash()).isNotEqualTo(SOME_HASH);
        }

        /**
         * The load-bearing ordering of §3.7. Reading the tail before the lock is
         * what lets two appends see the same predecessor and fork the chain —
         * and the resulting fork is reported by A-044 as tampering, months after
         * the code that caused it shipped.
         */
        @Test
        @DisplayName("the tail is read after the lock, never before it")
        void readsTheTailUnderTheLock() {
            ticketExists();

            journal.append(historyEntry());

            InOrder order = inOrder(tickets, history);
            order.verify(tickets).findByIdForUpdate(TICKET);
            order.verify(history).findFirstByTicketIdOrderByIdDesc(TICKET);
            order.verify(history).insert(any());
        }

        /**
         * The one case that would otherwise pass silently. A tail with no
         * {@code row_hash} is a legacy or {@code @DirectAppend} row; treating it
         * as absent would make this append a <em>second</em> genesis row for the
         * same ticket, which is a fork that verifies perfectly from that point
         * forward and hides everything before it.
         */
        @Test
        @DisplayName("an unchained predecessor is refused, not treated as no predecessor")
        void refusesToChainOntoAnUnhashedTail() {
            ticketExists();
            TicketHistory unchained = historyEntry();     // row_hash left null
            when(history.findFirstByTicketIdOrderByIdDesc(TICKET))
                    .thenReturn(Optional.of(unchained));

            assertThatThrownBy(() -> journal.append(historyEntry()))
                    .isInstanceOf(AppendRejectedException.class)
                    .hasMessageContaining("carries no row_hash")
                    .hasMessageContaining("fork");

            verify(history, never()).insert(any());
        }

        @Test
        @DisplayName("each table is its own chain, so one table's tail is not another's")
        void thereAreThreeChainsPerTicket() {
            ticketExists();
            when(transitions.findByCurrentTicketId(TICKET)).thenReturn(Optional.empty());
            TicketHistory tail = historyEntry();
            tail.setRowHash(SOME_HASH);
            when(history.findFirstByTicketIdOrderByIdDesc(TICKET)).thenReturn(Optional.of(tail));

            TicketEffortLog log = effortLog();
            TicketStageTransition hop = hop();
            journal.append(log);
            journal.append(hop);

            assertThat(log.getPrevHash())
                    .as("ticket_effort_logs has its own tail; ticket_history's is not it")
                    .isNull();
            assertThat(hop.getPrevHash()).isNull();
            verify(effortLogs).findFirstByTicketIdOrderByIdDesc(TICKET);
            verify(transitions).findFirstByTicketIdOrderByIdDesc(TICKET);
        }

        /**
         * Two rows differing in one column must not hash alike — otherwise the
         * chain would carry the row's position and nothing about its contents.
         */
        @Test
        @DisplayName("the hash covers the row, not just its place in the chain")
        void thePayloadReachesTheDigest() {
            ticketExists();

            TicketHistory one = historyEntry();
            TicketHistory two = historyEntry();
            two.setNewValue("IN_QA");
            journal.append(one);
            journal.append(two);

            assertThat(one.getRowHash()).isNotEqualTo(two.getRowHash());
        }

        /**
         * A-041 refuses sub-microsecond precision rather than truncating it,
         * because MySQL rounds to {@code DATETIME(6)} and the stored value would
         * then differ from the hashed one for ever. The journal must surface that
         * as a rejected append, keeping the message that names the fix, rather
         * than letting a {@code CanonicalJsonException} escape from an insert.
         */
        @Test
        @DisplayName("a timestamp DATETIME(6) cannot store is rejected, with the fix named")
        void refusesAPayloadWithNoCanonicalForm() {
            ticketExists();
            when(transitions.findByCurrentTicketId(TICKET)).thenReturn(Optional.empty());

            TicketStageTransition hop = hop();
            hop.setEnteredAt(Instant.parse("2026-08-14T09:00:00.1234565Z"));

            assertThatThrownBy(() -> journal.append(hop))
                    .isInstanceOf(AppendRejectedException.class)
                    .hasMessageContaining("truncatedTo")
                    .hasCauseInstanceOf(CanonicalJsonException.class);

            verify(transitions, never()).insert(any());
        }

        /**
         * The seal changes three columns the payload deliberately excludes, so
         * the hop's stored {@code row_hash} stays correct. Asserted here as well
         * as in {@code ChainPayloadsGoldenFileTest} because this is where the
         * two halves meet — a future author adding {@code duration_mins} to the
         * payload would break the ribbon rather than a golden file.
         */
        @Test
        @DisplayName("sealing does not invalidate the hop's hash")
        void sealingLeavesTheLinkIntact() {
            ticketExists();
            when(transitions.findByCurrentTicketId(TICKET)).thenReturn(Optional.empty());

            TicketStageTransition hop = hop();
            journal.append(hop);
            String hashedWhenAppended = hop.getRowHash();

            when(transitions.findById(5L)).thenReturn(Optional.of(hop));
            when(transitions.seal(anyLong(), any(), any())).thenReturn(1);
            journal.seal(5L, ENTERED.plus(4, ChronoUnit.HOURS), 240);

            assertThat(ChainDigest.rowHash(hop.getPrevHash(), ChainPayloads.of(hop)))
                    .as("recomputing a sealed hop must give back what was stored, or every sealed "
                            + "row in the ribbon fails the first night A-044 runs")
                    .isEqualTo(hashedWhenAppended);
        }
    }
}
