package com.edunext.edutrack.api.feature.tickets;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * C-011 · issues one ticket code. PLAN.md §3.2, contract {@code createTicket}.
 *
 * <p>Two collaborators, one rule each: {@link TicketSequenceRepository} owns the
 * number and {@link TicketCode} owns the shape. This class owns the thing
 * neither of them can see — the transaction they must both run inside.
 *
 * <h2>Why {@link Propagation#MANDATORY}</h2>
 *
 * <p>{@code LAST_INSERT_ID()} is <b>connection-local</b>. Spring binds one
 * connection to a transaction and hands the same one to every statement in it;
 * with no transaction, {@code JdbcClient} borrows a fresh connection from Hikari
 * per statement. The {@code UPDATE} would then increment the counter on
 * connection A and the {@code SELECT} would ask connection B what <i>it</i> last
 * inserted — answering 0, or, far worse, a number connection B was handed while
 * serving somebody else's ticket a moment ago. Under load that is duplicate
 * ticket IDs, appearing only when the pool recycles, and never in a test.
 *
 * <p>{@code MANDATORY} makes that unreachable: calling this outside a
 * transaction throws {@code IllegalTransactionStateException} at the proxy,
 * before a single statement runs. {@code REQUIRED} would also be
 * <i>correct</i> — it would open its own transaction and keep one connection —
 * but it would quietly commit the increment even when the caller's ticket insert
 * later failed, and it would let the invariant be broken by accident by someone
 * who simply forgot the annotation. This method is only ever called from inside
 * C-010's creation transaction, so demanding one costs nothing and states the
 * requirement where it cannot be overlooked.
 *
 * <p>Not read-only, for the obvious reason: allocation writes.
 */
@Service
class TicketCodeGenerator {

    private final TicketSequenceRepository sequences;
    private final Clock clock;

    @Autowired
    TicketCodeGenerator(TicketSequenceRepository sequences) {
        this(sequences, Clock.systemUTC());
    }

    /** Test seam — a fixed clock is the only way to assert the year rollover rule. */
    TicketCodeGenerator(TicketSequenceRepository sequences, Clock clock) {
        this.sequences = sequences;
        this.clock = clock;
    }

    /**
     * Allocates and renders the next ticket code for a project, e.g.
     * {@code CRM-26-00347}.
     *
     * <p>The project code is read <i>before</i> the counter moves, so a bad
     * project id costs a lookup rather than a wasted number. Both are inside the
     * caller's transaction regardless, so either order is safe — this one is
     * just tidier in the logs.
     *
     * @throws UnknownProjectException                                     no such project (renders as 404, never 403)
     * @throws org.springframework.transaction.IllegalTransactionStateException
     *         called outside a transaction — see the class note; this is a
     *         programming error, not a runtime condition
     */
    @Transactional(propagation = Propagation.MANDATORY)
    String nextTicketCode(long projectId) {
        String projectCode = sequences.findProjectCode(projectId)
                .orElseThrow(() -> new UnknownProjectException(projectId));

        long sequence = sequences.allocateNextSequence(projectId);

        // UTC, matching storage (CLAUDE.md: "time is UTC everywhere in storage").
        // A ticket raised at 02:00 IST on 1 January therefore carries the
        // previous year's two digits. Harmless by construction: the year is
        // descriptive and takes no part in uniqueness (TicketCode's note), and
        // an org-local year here would make the code depend on which timezone
        // the caller happened to be in.
        int year = LocalDate.now(clock.withZone(ZoneOffset.UTC)).getYear();

        return TicketCode.format(projectCode, year, sequence);
    }
}
