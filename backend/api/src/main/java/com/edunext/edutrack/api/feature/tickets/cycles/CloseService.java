package com.edunext.edutrack.api.feature.tickets.cycles;

import com.edunext.edutrack.api.feature.tickets.TicketWire;
import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.api.security.scope.ScopedTickets;
import com.edunext.edutrack.api.feature.notifications.events.TicketEventNotifier;
import com.edunext.edutrack.domain.journal.TicketJournal;
import com.edunext.edutrack.domain.tickets.Ticket;
import com.edunext.edutrack.domain.tickets.TicketCycle;
import com.edunext.edutrack.domain.tickets.TicketCycleRepository;
import com.edunext.edutrack.domain.tickets.TicketHistory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * C-040 · the close transaction, blueprint §4A.2 / S-23.
 *
 * <p>{@link ReopenService}'s mirror image: that one opens a cycle, this one
 * seals it. Four writes, one transaction:
 *
 * <ol>
 *   <li>the current cycle is sealed ({@code is_sealed = true}) and carries its
 *       close facts — {@code actual_close_date}, {@code resolution_summary},
 *       {@code root_cause_category}, {@code client_verification_requested}</li>
 *   <li>{@code tickets.status} becomes {@code CLOSED} and
 *       {@code tickets.actual_close_date} is stamped to match</li>
 *   <li>an optional {@code FIELD_CHANGED} history row for
 *       {@code finalEffortHours}, if the caller confirmed one</li>
 *   <li>the {@code CLOSED} status-change history row, last, exactly as
 *       {@link ReopenService#reopen} writes its own status row last</li>
 * </ol>
 *
 * <h2>Only RESOLVED may be closed</h2>
 *
 * <p>{@code workflow_transitions} seeds exactly one row set into
 * {@code CLOSED}, and its {@code from_status} is {@code RESOLVED} (row 12,
 * {@code V20260807_1100}) — G-3's sign-off move, reserved for Admin, PM and
 * Support. See {@link TicketNotResolvedException}.
 *
 * <h2>What this deliberately does not touch</h2>
 *
 * <p><b>{@code ticket_effort_logs} and {@code total_effort_hrs}.</b>
 * {@code finalEffortHours} is a confirmation of hours already logged through
 * {@code POST /tickets/{ticketId}/effort} (C-035), not a new figure — appending
 * it as a fresh entry would double the cell it is meant to confirm, the exact
 * failure {@link ReopenService}'s own javadoc warns against for the opposite
 * direction. So it is recorded as one {@code FIELD_CHANGED} history row and
 * nothing in the ledger moves.
 *
 * <p><b>{@code level} and {@code original_level}.</b> G-4 (PLAN.md §5) — a
 * close does not revert an escalated level, and nothing here reads either
 * column.
 *
 * <p><b>{@code current_stage} and the open {@code ticket_stage_transitions} hop.</b>
 * S-23's dialog closes the ticket; it does not draw the ribbon's terminal
 * segment. That is C-042's transition service, on {@link ReopenService}'s own
 * precedent for its new cycle's first hop — {@code TicketJournal} publishes
 * {@code seal} but no way to <em>find</em> the open row from this package
 * (A-040's door, A-037's rule). Left exactly where reopen left it: a real gap,
 * raised rather than worked around, and C-042's to close alongside reopen's.
 *
 * <h2>Client verification is recorded, not acted on</h2>
 *
 * <p>{@code requestClientVerification} is stamped on the cycle and, when true,
 * gets its own history row so the close is reproducible from
 * {@code ticket_history} alone — the same "written whether or not anybody is
 * listening" position {@code PriorityChangeService} takes for §4B.1's critical
 * alert. Nothing here emails a client contact; that is Stream D's engine and
 * has no consumer for this event yet.
 *
 * <h2>Concurrency</h2>
 *
 * <p>Same position as {@link ReopenService}: the ticket is read through
 * {@link ScopedTickets} — a plain {@code SELECT} — and the per-ticket
 * {@code SELECT … FOR UPDATE} arrives later, inside the journal's append.
 * Unlike a reopen, there is no unique constraint backstopping a double close —
 * two concurrent closes of the same RESOLVED ticket both succeed, and the
 * second simply overwrites the first's resolution facts with its own. A
 * narrower window than reopen's, and the same one a double-submitted browser
 * tab already risks on {@code PATCH /tickets/{ticketId}/priority}; closing it
 * needs a locking read {@code ScopedTickets} does not expose, which is
 * {@link ReopenService}'s own reason for not taking one either.
 */
@Service
class CloseService {

    private static final String RESOLVED = "RESOLVED";
    private static final String CLOSED = "CLOSED";

    private final ScopedTickets tickets;
    private final TicketCycleRepository cycles;
    private final TicketJournal journal;
    /**
     * D-037 · §4B.6's producer for this event. **Stream D's class, injected
     * into Stream C's service** — the arrangement `CommentService` already has
     * with `CommentMentionNotifier`. Flagged for Divyansh in the pull request
     * rather than added quietly.
     */
    private final TicketEventNotifier notifier;
    private final Clock clock;

    @Autowired
    CloseService(ScopedTickets tickets, TicketCycleRepository cycles, TicketJournal journal,
                 TicketEventNotifier notifier) {
        this(tickets, cycles, journal, notifier, Clock.systemUTC());
    }

    /**
     * The clock is injectable for the same reason {@link ReopenService}'s is —
     * the close instant is written to three places (the cycle's
     * {@code actual_close_date}, the ticket's, and the history row) and a test
     * asserting they agree could otherwise only do so to within however long
     * the method took to run.
     */
    CloseService(ScopedTickets tickets, TicketCycleRepository cycles, TicketJournal journal,
                 TicketEventNotifier notifier, Clock clock) {
        this.tickets = tickets;
        this.cycles = cycles;
        this.journal = journal;
        this.notifier = notifier;
        this.clock = clock;
    }

    /**
     * Seal the current cycle as closed.
     *
     * @return the closed ticket, as the contract's {@code TicketResponse} data
     * @throws com.edunext.edutrack.api.security.scope.TicketNotFoundException
     *         404, identically for a ticket that does not exist and one this
     *         caller may not see (A-035)
     * @throws TicketNotResolvedException 422 — nothing to close
     */
    @Transactional
    TicketWire.Ticket close(Authentication caller, String ticketCode, CloseDtos.CloseRequest request) {

        /*
         * Scope first, and only once — ReopenService's own opening line.
         *
         * requireByCode, not require: the contract's {ticketId} path segment
         * is the code (CRM-26-00347), not the numeric row id.
         * PriorityChangeController's own long note records the two routes
         * that still get this wrong and why it is not fixed there in
         * passing — found here by calling this route against the real
         * running application rather than only the mock.
         */
        Ticket ticket = tickets.requireByCode(caller, ticketCode);
        long ticketId = ticket.getId();

        if (!RESOLVED.equals(ticket.getStatus())) {
            throw new TicketNotResolvedException(ticket.getStatus());
        }

        // DATETIME(6) rounds rather than truncates (ReopenService's own
        // comment on the same truncation); done once so every place this
        // instant lands agrees.
        Instant closedAt = request.actualCloseDate() != null
                ? request.actualCloseDate().truncatedTo(ChronoUnit.MICROS)
                : Instant.now(clock).truncatedTo(ChronoUnit.MICROS);

        short cycleNo = ticket.getCurrentCycleNo();
        boolean requestedVerification = Boolean.TRUE.equals(request.requestClientVerification());

        // 1 · seal the current cycle and carry its close facts. An absent row
        // is a hard failure rather than a skipped step — ReopenService's own
        // reasoning for the cycle it seals: cycle 1 is created with the
        // ticket (§4.1), so a RESOLVED ticket with no current-cycle row is a
        // data defect, not a case to fall back from.
        TicketCycle current = cycles.findByTicketIdAndCycleNo(ticketId, cycleNo)
                .orElseThrow(() -> new IllegalStateException(
                        "ticket " + ticketId + " is RESOLVED at cycle " + cycleNo
                                + " but has no ticket_cycles row for it, so there is nothing to seal. "
                                + "Cycle 1 is created with the ticket (§4.1); this ticket predates that "
                                + "or was written around it."));
        current.setActualCloseDate(closedAt);
        current.setResolutionSummary(request.resolutionSummary());
        current.setRootCauseCategory(blankToNull(request.rootCauseCategory()));
        current.setClientVerificationRequested(requestedVerification);
        current.setSealed(true);

        // 2 · the ticket itself.
        ticket.setStatus(CLOSED);
        ticket.setActualCloseDate(closedAt);
        // is_delayed / delayed_since are Stream D's scanner's to clear, on
        // ReopenService's own reasoning: a closed ticket with no open planned
        // close date is out of the breach sweep on the next scan, and
        // guessing at the flag here would be a second implementation of "is
        // this ticket late".

        // 3 · the confirmed effort, if the caller sent one — a FIELD_CHANGED
        // row and nothing in ticket_effort_logs. See the class javadoc.
        if (request.finalEffortHours() != null) {
            journal.append(finalEffortEntry(ticketId, cycleNo, caller, request.finalEffortHours()));
        }

        // 4 · client verification, if requested — recorded for reproducibility
        // even though nothing reads it yet. See the class javadoc.
        if (requestedVerification) {
            journal.append(clientVerificationEntry(ticketId, cycleNo, caller));
        }

        // 5 · the CLOSED status row, last, through the journal — which takes
        // the per-ticket SELECT … FOR UPDATE and computes the hash chain
        // (A-042). Stamped with the closing cycle, per ReopenService's own
        // reasoning for stamping its history with the cycle a reader looking
        // at that cycle's History tab would expect to see.
        journal.append(closedEntry(ticketId, cycleNo, caller, request.resolutionSummary()));

        // D-037 · §4B.6 row 12 — reporter, client contact and watchers. After
        // the history row, which is the record; a mail that cannot be queued
        // must never roll back a close, and the notifier swallows its own
        // failures for that reason.
        notifier.closed(ticket, actorIdOrZero(caller));

        return TicketWire.of(ticket);
    }

    private static TicketHistory closedEntry(long ticketId, short cycleNo,
                                              Authentication caller, String resolutionSummary) {
        TicketHistory entry = new TicketHistory();
        entry.setTicketId(ticketId);
        entry.setCycleNo(cycleNo);
        entry.setEventType(CLOSED);
        entry.setFieldName("status");
        entry.setOldValue(RESOLVED);
        entry.setNewValue(CLOSED);
        Long actor = actorId(caller);
        entry.setActorId(actor);
        entry.setActorType(actor == null ? "SYSTEM" : "USER");
        entry.setRemarks(resolutionSummary);
        return entry;
    }

    private static TicketHistory finalEffortEntry(long ticketId, short cycleNo,
                                                   Authentication caller, BigDecimal finalEffortHours) {
        TicketHistory entry = new TicketHistory();
        entry.setTicketId(ticketId);
        entry.setCycleNo(cycleNo);
        entry.setEventType("FIELD_CHANGED");
        entry.setFieldName("finalEffortHours");
        entry.setNewValue(finalEffortHours.toPlainString());
        Long actor = actorId(caller);
        entry.setActorId(actor);
        entry.setActorType(actor == null ? "SYSTEM" : "USER");
        return entry;
    }

    private static TicketHistory clientVerificationEntry(long ticketId, short cycleNo, Authentication caller) {
        TicketHistory entry = new TicketHistory();
        entry.setTicketId(ticketId);
        entry.setCycleNo(cycleNo);
        entry.setEventType("CLIENT_VERIFICATION_REQUESTED");
        Long actor = actorId(caller);
        entry.setActorId(actor);
        entry.setActorType(actor == null ? "SYSTEM" : "USER");
        return entry;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Who is closing. Null would mean SYSTEM, and the journal enforces that
     * pairing; the route is behind {@code hasAuthority('ticket.close')}, so an
     * unidentifiable caller cannot reach here — {@link ReopenService#actorId}'s
     * identical guard and reasoning.
     */
    /** D-037 · the caller, or 0 when there is none — `dev-noauth` has no principal. */
    private static long actorIdOrZero(Authentication caller) {
        Long id = actorId(caller);
        return id == null ? 0L : id;
    }

    private static Long actorId(Authentication caller) {
        return Optional.ofNullable(caller)
                .flatMap(CallerIdentity::of)
                .map(CallerIdentity::userId)
                .orElse(null);
    }
}
