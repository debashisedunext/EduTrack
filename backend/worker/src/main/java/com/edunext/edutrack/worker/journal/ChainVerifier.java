package com.edunext.edutrack.worker.journal;

import com.edunext.edutrack.domain.journal.ChainDigest;
import com.edunext.edutrack.domain.journal.ChainPayloads;
import com.edunext.edutrack.domain.tickets.TicketEffortLog;
import com.edunext.edutrack.domain.tickets.TicketEffortLogRepository;
import com.edunext.edutrack.domain.tickets.TicketHistory;
import com.edunext.edutrack.domain.tickets.TicketHistoryRepository;
import com.edunext.edutrack.domain.workflow.TicketStageTransition;
import com.edunext.edutrack.domain.workflow.TicketStageTransitionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * A-044 · verifies one ticket's three chains and says what is wrong with them.
 *
 * <p>PLAN.md §3.5 names this as the backstop for the case where the A-008
 * triggers have been defeated: "tampering that bypasses triggers still breaks
 * the chain and is detected by the nightly verifier". Everything else in the
 * immutability design refuses a bad write; this is the only part that looks at
 * what is already stored.
 *
 * <h2>It recomputes rather than re-derives</h2>
 *
 * <p>The hash is recomputed with {@link ChainDigest} over {@link ChainPayloads},
 * the same two classes the journal used to write it. That sharing is the whole
 * reason A-041 put the canonical form in {@code common} and A-042 put the
 * payload builder in {@code domain}: a second implementation here would agree
 * with the first until somebody edited one, and then every row would fail
 * verification with no code change to blame.
 *
 * <h2>Ordering is by id, and for transitions that is not obvious</h2>
 *
 * <p>The chain is insertion order. For {@code ticket_stage_transitions} that is
 * <em>not</em> {@code seq_no} order — {@code seq_no} restarts at 1 for each
 * cycle, so a reopened ticket walked that way interleaves its second cycle
 * among its first and reports a sound chain as broken, on exactly the tickets
 * with the most history.
 *
 * <h2>The anchor is only moved after a clean verify</h2>
 *
 * <p>Anchoring a chain that just reported a break would file the corruption as
 * the new known-good state, and the next run would compare against it and find
 * nothing wrong — the verifier would launder the very thing it exists to
 * report.
 *
 * <h2>Read-only, and it stays that way</h2>
 *
 * <p>Verification never repairs. There is nothing it could legitimately do: a
 * row whose hash does not match cannot be corrected without an UPDATE the
 * triggers refuse, and rewriting the chain to make it consistent is
 * indistinguishable from doing the tampering a second time. Its only outputs
 * are findings and an anchor.
 */
@Component
public class ChainVerifier {

    static final String HISTORY = "ticket_history";
    static final String EFFORT = "ticket_effort_logs";
    static final String TRANSITIONS = "ticket_stage_transitions";

    private final TicketHistoryRepository history;
    private final TicketEffortLogRepository effortLogs;
    private final TicketStageTransitionRepository transitions;
    private final ChainAnchorRepository anchors;
    private final Clock clock;

    ChainVerifier(TicketHistoryRepository history,
                  TicketEffortLogRepository effortLogs,
                  TicketStageTransitionRepository transitions,
                  ChainAnchorRepository anchors,
                  Clock clock) {
        this.history = history;
        this.effortLogs = effortLogs;
        this.transitions = transitions;
        this.anchors = anchors;
        this.clock = clock;
    }

    /**
     * All three chains for one ticket.
     *
     * <p>{@code REQUIRES_NEW} so one ticket's verification is its own unit: a
     * run covers every ticket in the system, and a failure on ticket 4711 must
     * not roll back the anchors written for the four thousand before it.
     *
     * @return every break found, empty when all three chains are sound
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<ChainBreak> verify(long ticketId) {
        List<ChainBreak> breaks = new ArrayList<>();
        Map<String, ChainAnchorRepository.Anchor> anchored = new HashMap<>();
        for (ChainAnchorRepository.Anchor a : anchors.findAllFor(ticketId)) {
            anchored.put(a.table(), a);
        }

        breaks.addAll(verifyChain(ticketId, HISTORY,
                history.findByTicketIdOrderByIdAsc(ticketId),
                TicketHistory::getId, TicketHistory::getPrevHash, TicketHistory::getRowHash,
                ChainPayloads::of, anchored.get(HISTORY)));

        breaks.addAll(verifyChain(ticketId, EFFORT,
                effortLogs.findByTicketIdOrderByIdAsc(ticketId),
                TicketEffortLog::getId, TicketEffortLog::getPrevHash, TicketEffortLog::getRowHash,
                ChainPayloads::of, anchored.get(EFFORT)));

        breaks.addAll(verifyChain(ticketId, TRANSITIONS,
                transitions.findByTicketIdOrderByIdAsc(ticketId),
                TicketStageTransition::getId, TicketStageTransition::getPrevHash,
                TicketStageTransition::getRowHash, ChainPayloads::of, anchored.get(TRANSITIONS)));

        return breaks;
    }

    /**
     * One chain, walked once.
     *
     * <p>The checks are deliberately independent rather than short-circuiting.
     * A chain with two problems has two problems, and reporting only the first
     * would mean the second surfaces a night later as a fresh alarm on a ticket
     * somebody has already investigated and closed.
     */
    private <T> List<ChainBreak> verifyChain(
            long ticketId, String table, List<T> rows,
            Function<T, Long> idOf, Function<T, String> prevHashOf, Function<T, String> rowHashOf,
            Function<T, Map<String, Object>> payloadOf, ChainAnchorRepository.Anchor anchor) {

        List<ChainBreak> breaks = new ArrayList<>();
        if (rows.isEmpty()) {
            // No rows and no anchor is an ordinary ticket nothing has happened
            // to yet. No rows but an anchor is every row having been deleted,
            // which is the most complete truncation there is.
            if (anchor != null) {
                breaks.add(ChainBreak.at(ticketId, table, null, ChainBreak.Kind.TRUNCATED,
                        "the anchor records " + anchor.rowCount() + " rows and the chain is now empty"));
            }
            return breaks;
        }

        Set<String> parentsSeen = new HashSet<>();
        Set<String> parentsReported = new HashSet<>();
        int genesisCount = 0;

        for (int i = 0; i < rows.size(); i++) {
            T row = rows.get(i);
            Long rowId = idOf.apply(row);
            String stored = rowHashOf.apply(row);
            String prev = prevHashOf.apply(row);

            if (stored == null) {
                // Written around the journal. Recomputing would compare against
                // nothing, and the next row's link check will report separately
                // that it could not chain onto this — which is the honest
                // account of a row that is not part of the chain at all.
                breaks.add(ChainBreak.at(ticketId, table, rowId, ChainBreak.Kind.UNCHAINED,
                        "row has no row_hash, so it is not part of the chain"));
            } else {
                String recomputed = ChainDigest.rowHash(prev, payloadOf.apply(row));
                if (!recomputed.equals(stored)) {
                    breaks.add(ChainBreak.at(ticketId, table, rowId, ChainBreak.Kind.HASH_MISMATCH,
                            "stored " + stored + ", recomputed " + recomputed));
                }
            }

            if (prev == null) {
                genesisCount++;
            } else if (!parentsSeen.add(prev) && parentsReported.add(prev)) {
                breaks.add(ChainBreak.at(ticketId, table, rowId, ChainBreak.Kind.FORK,
                        "more than one row claims parent " + prev));
            }

            if (i > 0) {
                String expected = rowHashOf.apply(rows.get(i - 1));
                if (prev != null && expected != null && !prev.equals(expected)) {
                    breaks.add(ChainBreak.at(ticketId, table, rowId, ChainBreak.Kind.BROKEN_LINK,
                            "names parent " + prev + " but the preceding row is " + expected));
                }
            }
        }

        if (genesisCount > 1) {
            breaks.add(ChainBreak.at(ticketId, table, null, ChainBreak.Kind.MULTIPLE_GENESIS,
                    genesisCount + " rows carry no prev_hash — each writer believed it was first"));
        }

        breaks.addAll(compareToAnchor(ticketId, table, rows, idOf, anchor));

        if (breaks.isEmpty()) {
            T head = rows.getLast();
            anchors.anchor(ticketId, table, rows.size(), idOf.apply(head),
                    rowHashOf.apply(head), clock.instant());
        }
        return breaks;
    }

    /**
     * The check chaining cannot make. Row counts on an append-only table only
     * ever rise, so a smaller one is a deletion — no cryptography required, just
     * a number that has no legitimate way down.
     */
    private <T> List<ChainBreak> compareToAnchor(long ticketId, String table, List<T> rows,
                                                 Function<T, Long> idOf,
                                                 ChainAnchorRepository.Anchor anchor) {
        if (anchor == null) {
            return List.of();
        }
        List<ChainBreak> breaks = new ArrayList<>();
        if (rows.size() < anchor.rowCount()) {
            breaks.add(ChainBreak.at(ticketId, table, null, ChainBreak.Kind.TRUNCATED,
                    "the anchor records " + anchor.rowCount() + " rows and only " + rows.size()
                            + " remain"));
        }
        boolean headStillThere = rows.stream()
                .map(idOf)
                .anyMatch(id -> id != null && id == anchor.headRowId());
        if (!headStillThere) {
            breaks.add(ChainBreak.at(ticketId, table, anchor.headRowId(),
                    ChainBreak.Kind.TRUNCATED,
                    "the row this chain was last anchored on is gone"));
        }
        return breaks;
    }

    /** Exposed for the scanner's digest, which reports what a run covered. */
    Optional<ChainAnchorRepository.Anchor> anchorFor(long ticketId, String table) {
        return anchors.find(ticketId, table);
    }
}
