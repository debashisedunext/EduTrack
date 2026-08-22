package com.edunext.edutrack.api.feature.tickets.cycles;

import com.edunext.edutrack.api.feature.tickets.PlannedCloseDateService;
import com.edunext.edutrack.api.feature.tickets.SlaResolution;
import com.edunext.edutrack.api.feature.tickets.TicketWire;
import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.api.security.scope.ScopedTickets;
import com.edunext.edutrack.api.feature.notifications.events.TicketEventNotifier;
import com.edunext.edutrack.domain.journal.TicketJournal;
import com.edunext.edutrack.domain.masters.WorkingHoursService;
import com.edunext.edutrack.domain.tickets.Ticket;
import com.edunext.edutrack.domain.tickets.TicketCycle;
import com.edunext.edutrack.domain.tickets.TicketCycleRepository;
import com.edunext.edutrack.domain.tickets.TicketHistory;
import com.edunext.edutrack.domain.workflow.WorkflowStageRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * C-038 · the reopen transaction, blueprint §4.1.
 *
 * <p>Six writes, one transaction, and the whole task is that the sixth cannot
 * land without the first:
 *
 * <ol>
 *   <li>cycle N is sealed — {@code is_sealed = true}</li>
 *   <li>cycle N+1 is inserted, with a fresh start date, planned close date and
 *       assignee</li>
 *   <li>{@code tickets.status} becomes {@code REOPENED}</li>
 *   <li>{@code current_cycle_no} becomes N+1 and {@code reopen_count}
 *       increments</li>
 *   <li>{@code actual_close_date} is cleared</li>
 *   <li>a {@code REOPENED} entry is appended to {@code ticket_history}</li>
 * </ol>
 *
 * <p>Half of that list is a ticket that is open with no cycle to work in, or a
 * cycle nobody can find because the ticket still reads {@code CLOSED}, or a
 * counter that says two while the journey says one. None of those states has a
 * repair — the reopen is not idempotent, so it cannot simply be retried, and
 * there is no {@code UPDATE} that can put a hash-chained history entry back. So
 * this is one {@code @Transactional} method and deliberately not a sequence of
 * smaller sanctioned ones.
 *
 * <h2>🔴 Cycle N's effort is never touched</h2>
 *
 * <p>Not "is preserved" — <em>not touched</em>. Nothing below reads
 * {@code ticket_effort_logs}, writes {@code ticket_cycles.effort_hrs}, or moves
 * {@code tickets.total_effort_hrs}, and that is the point rather than an
 * omission:
 *
 * <ul>
 *   <li>{@code total_effort_hrs} is already Σ across <b>all</b> cycles
 *       (the baseline migration says so in the column comment), so a reopen has
 *       nothing to add to it and adjusting it would double-count on the spot.</li>
 *   <li>{@code ticket_cycles.effort_hrs} is cycle N's final figure. It is what
 *       the §4A.4 grid reports as "24.5 h this cycle" for the cycle now sealed,
 *       and it stops moving because the cycle stopped.</li>
 *   <li>Effort logs carry {@code cycle_no}, and the roll-up joins on it
 *       (PLAN.md §3.4 corrects the blueprint's own query, which omits it) — so
 *       cycle 1's hours are already separated from cycle 2's by the data, with
 *       no zeroing, copying or carrying-forward for this method to do.</li>
 * </ul>
 *
 * <p>The one field that would tempt a change is {@code estimated_effort_hrs},
 * and S-22's "revised estimated effort" is exactly that request. See
 * {@link #reopen} — it moves the ticket's estimate and leaves every recorded
 * hour alone.
 *
 * <h2>🔴 What this deliberately does not do: the new cycle's ribbon</h2>
 *
 * <p>A reopened ticket needs a {@code ticket_stage_transitions} row for the
 * stage its new cycle restarts in, and the row cycle N was resting in
 * ({@code CLOSED}, left open because closure is normally terminal) needs
 * sealing. <b>Neither happens here, and both must happen before the ribbon is
 * correct.</b>
 *
 * <p>It is left out because it cannot be done from this package today, not
 * because it does not matter. {@code TicketJournal} publishes {@code seal} but
 * no way to <em>find</em> the open hop — {@code findByCurrentTicketId} is behind
 * A-040's door, and A-037's rule forbids holding
 * {@code TicketStageTransitionRepository} outside {@code domain.journal} for
 * reads as well as writes. Adding a reader to the journal is a change to Stream
 * A's {@code domain/journal/}, so it is raised rather than made.
 *
 * <p>The consequence is precise and worth stating so C-042 inherits it rather
 * than discovers it: <b>after this method runs, a fixture-seeded ticket still
 * has an open hop at {@code CLOSED}</b>, so the first append of cycle N+1's
 * ribbon will be refused by {@link TicketJournal#append(
 * com.edunext.edutrack.domain.workflow.TicketStageTransition)} — "ticket … is
 * still in stage CLOSED". That refusal is the correct behaviour of a correct
 * guard; what is missing is the seal in front of it. C-042 owns the transition
 * service and is where the pair belongs, with the exit duration in <b>working</b>
 * minutes from the B-024 calendar, which is the figure a caller outside that
 * service has no business computing.
 *
 * <p>Tickets created through the application have no transition rows at all yet
 * (C-042 is unbuilt), so nothing is broken today that was working yesterday.
 * {@code current_stage} <em>is</em> set below, because it is a column on the
 * ticket and S-22's restart stage has to land somewhere — a ticket that reopened
 * into no stage would be unassignable.
 *
 * <h2>Concurrency: two reopens of the same ticket</h2>
 *
 * <p>The ticket is read through {@link ScopedTickets} — a plain {@code SELECT} —
 * and the {@code SELECT … FOR UPDATE} arrives later, inside the journal's
 * append. So two callers can both read {@code CLOSED} and both compute N+1.
 * <b>{@code uq_ticket_cycles (ticket_id, cycle_no)} is what stops that being a
 * double count</b>: the second insert is refused, its transaction rolls back
 * whole, and the ticket keeps one cycle 2 with one reason. A 409 for the loser
 * of a double-submitted dialog is the right outcome, and it is the same defence
 * {@code ResourceController.create} documents for a retried resource create
 * against {@code uq_users_username}.
 *
 * <p>Not a lock taken here, because there is no scoped way to take one:
 * {@code ScopedTickets} exposes no locking read, and reaching for
 * {@code TicketRepository.findByIdForUpdate} directly is what A-037's
 * {@code ScopeGuardRulesTest} exists to refuse. The constraint is the stronger
 * guarantee in any case — it holds against a caller that bypasses this service
 * entirely.
 */
@Service
class ReopenService {

    /** The only status §4.1 reopens from — see {@link TicketNotClosedException}. */
    private static final String CLOSED = "CLOSED";

    private static final String REOPENED = "REOPENED";

    /** The contract's default for {@code restartStageCode}, and S-22's. */
    private static final String DEFAULT_RESTART_STAGE = "TRIAGE";

    private final ScopedTickets tickets;
    private final TicketCycleRepository cycles;
    private final TicketJournal journal;
    private final WorkflowStageRepository stages;
    private final PlannedCloseDateService slaLadder;
    private final WorkingHoursService workingHours;
    /**
     * D-037 · §4B.6's producer for this event. **Stream D's class, injected
     * into Stream C's service** — the arrangement `CommentService` already has
     * with `CommentMentionNotifier`. Flagged for Divyansh in the pull request
     * rather than added quietly.
     */
    private final TicketEventNotifier notifier;
    private final Clock clock;

    /*
     * @Autowired is required rather than decoration: two constructors leaves
     * Spring no candidate to prefer and it fails at startup with "no default
     * constructor found", naming neither this class nor its real problem.
     * CommentService and AttachmentService carry the same marker for the same
     * pair, and CommentService's javadoc records what the omission cost.
     */
    @Autowired
    ReopenService(ScopedTickets tickets,
                  TicketCycleRepository cycles,
                  TicketJournal journal,
                  WorkflowStageRepository stages,
                  PlannedCloseDateService slaLadder,
                  WorkingHoursService workingHours,
                  TicketEventNotifier notifier) {
        this(tickets, cycles, journal, stages, slaLadder, workingHours, notifier, Clock.systemUTC());
    }

    /**
     * The clock is injectable because the reopen instant is written to four
     * places — the new cycle's {@code start_date}, the ticket's
     * {@code stage_entered_at}, the recomputed planned close date and the
     * history entry — and a test that asserted they agree could otherwise only
     * do so to within however long the method took to run.
     */
    ReopenService(ScopedTickets tickets,
                 TicketCycleRepository cycles,
                 TicketJournal journal,
                 WorkflowStageRepository stages,
                 PlannedCloseDateService slaLadder,
                 WorkingHoursService workingHours,
                 TicketEventNotifier notifier,
                 Clock clock) {
        this.tickets = tickets;
        this.cycles = cycles;
        this.journal = journal;
        this.stages = stages;
        this.slaLadder = slaLadder;
        this.workingHours = workingHours;
        this.notifier = notifier;
        this.clock = clock;
    }

    /**
     * Seal cycle N, open cycle N+1.
     *
     * <h2>Every default falls back to something the ticket already knows</h2>
     *
     * <p>S-22 makes the reason mandatory and the other four optional, so each one
     * omitted has to resolve to a value that is right rather than merely legal:
     *
     * <ul>
     *   <li><b>assignee</b> → the closing cycle's assignee, then the ticket's.
     *       S-22 says "defaults to previous", and previous means whoever held the
     *       cycle being sealed — which is not always {@code tickets.assigned_to},
     *       because a ticket can be reassigned after closure.</li>
     *   <li><b>planned close date</b> → <b>recomputed</b> from the §6 SLA ladder
     *       measured from now, never cycle N's, which is a date in the past. A
     *       reopened ticket carrying it would be born breached, and Stream D's
     *       scanner would alert on it before anybody had read the reason. Null if
     *       the ladder has nothing to say — see {@link SlaResolution#hasTarget}.</li>
     *   <li><b>restart stage</b> → {@code TRIAGE}, the contract's default and
     *       S-22's, and validated against the ticket's own workflow template
     *       ({@link UnknownRestartStageException}).</li>
     *   <li><b>estimated hours</b> → left alone. Null means "I have no revised
     *       figure", which is different from zero, and overwriting a good estimate
     *       with nothing is the one default here that would destroy information.</li>
     * </ul>
     *
     * <h2>What the ticket keeps</h2>
     *
     * <p>{@code level} and {@code original_level} both survive: G-4 (PLAN.md §5)
     * decided a close does not revert an escalated level, so a reopen has none to
     * revert. {@code rework_count} survives too — it counts backward moves over
     * the ticket's whole life and A-070 reports on it, whereas
     * {@code current_iteration} resets to 1 because iteration is <em>within</em> a
     * cycle (§4A.2) and this is a new one. Those two lines are the two counters
     * the spec is most often misread on, sitting next to each other.
     *
     * @return the reopened ticket, as the contract's {@code TicketResponse} data
     * @throws com.edunext.edutrack.api.security.scope.TicketNotFoundException
     *         404, identically for a ticket that does not exist and one this
     *         caller may not see (A-035)
     * @throws TicketNotClosedException      422 — nothing to reopen
     * @throws UnknownRestartStageException  400 — no such stage in the template
     */
    @Transactional
    TicketWire.Ticket reopen(Authentication caller,
                             String ticketCode,
                             ReopenDtos.ReopenRequest request) {

        // Scope first, and only once. Everything below works by ticket id, so a
        // caller who got past this line would reopen another project's ticket and
        // no later write would notice.
        // The ticket CODE, per the contract's TicketId $ref — CloseController's
        // sibling route has taken one since 20 Aug and this one 400'd until now.
        Ticket ticket = tickets.requireByCode(caller, ticketCode);
        long ticketId = ticket.getId();

        if (!CLOSED.equals(ticket.getStatus())) {
            throw new TicketNotClosedException(ticket.getStatus());
        }

        short closingCycleNo = ticket.getCurrentCycleNo();
        short newCycleNo = (short) (closingCycleNo + 1);

        /*
         * DATETIME(6) is microsecond precision and MySQL *rounds* rather than
         * truncating, so an Instant carrying nanoseconds would be stored as a
         * different value from the one hashed into the history chain — and
         * A-041 refuses such a payload outright rather than letting it verify
         * false for ever. Truncated once, here, so all four places this instant
         * lands carry the same value.
         */
        Instant reopenedAt = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);

        String restartStage = restartStage(ticket, request.restartStageCode());
        Long assignee = assignee(ticket, closingCycleNo, request.assigneeId());
        Instant plannedCloseDate = request.plannedCloseDate() != null
                ? request.plannedCloseDate()
                : recomputePlannedCloseDate(ticket, assignee, reopenedAt);

        // 1 · seal cycle N. Sealing is what makes it read-only: C-053 renders a
        // sealed cycle's journey without action affordances, and nothing in the
        // reopen path writes to it again.
        //
        // An absent row is a hard failure rather than a skipped step. The
        // blueprint creates cycle 1 with the ticket, so a closed ticket with no
        // current cycle row is a data defect — and inserting N+1 over the hole
        // would make the ticket permanently claim a history it has no record of.
        TicketCycle closing = cycles.findByTicketIdAndCycleNo(ticketId, closingCycleNo)
                .orElseThrow(() -> new IllegalStateException(
                        "ticket " + ticketId + " is CLOSED at cycle " + closingCycleNo
                                + " but has no ticket_cycles row for it, so there is nothing to seal. "
                                + "Cycle 1 is created with the ticket (§4.1); this ticket predates that "
                                + "or was written around it."));
        closing.setSealed(true);

        // 2 · open cycle N+1. actual_close_date and effort_hrs are left at their
        // defaults — null and zero — because this cycle has neither yet, and
        // effort_hrs starting anywhere but zero is how cycle 1's hours would
        // leak into cycle 2's figure.
        TicketCycle reopened = new TicketCycle();
        reopened.setTicketId(ticketId);
        reopened.setCycleNo(newCycleNo);
        reopened.setStartDate(reopenedAt);
        reopened.setAssignedTo(assignee);
        reopened.setAssignedBy(actorId(caller));
        reopened.setLevel(ticket.getLevel());
        reopened.setPlannedCloseDate(plannedCloseDate);
        reopened.setReopenReason(request.reason());
        cycles.save(reopened);

        // 3, 4, 5 · the ticket itself.
        ticket.setStatus(REOPENED);
        ticket.setCurrentCycleNo(newCycleNo);
        ticket.setReopenCount((short) (ticket.getReopenCount() + 1));
        ticket.setReopened(true);
        // Cleared so pcd_open — IF(actual_close_date IS NULL, planned_close_date,
        // NULL) — picks the ticket back up for Stream D's SLA scan. A reopened
        // ticket that kept its close date would be invisible to every "still
        // open" query in the system while sitting in somebody's queue.
        ticket.setActualCloseDate(null);
        ticket.setPlannedCloseDate(plannedCloseDate);
        ticket.setAssignedTo(assignee);
        ticket.setAssignedBy(actorId(caller));
        ticket.setCurrentStage(restartStage);
        ticket.setStageEnteredAt(reopenedAt);
        // Iteration is within a cycle (§4A.2), so a new cycle starts at 1.
        // rework_count is NOT reset — see the javadoc.
        ticket.setCurrentIteration((short) 1);
        if (request.estimatedHrs() != null) {
            ticket.setEstimatedEffortHrs(request.estimatedHrs());
        }
        // is_delayed and delayed_since are left to Stream D's scanner rather than
        // guessed at here: the new planned close date is in the future, so the
        // next scan clears the flag from the truth of the dates. Clearing it here
        // would be a second implementation of "is this ticket late".

        // 6 · the history entry, last, and through the journal — which takes the
        // per-ticket SELECT … FOR UPDATE and computes the hash chain (A-042).
        // Stamped with the NEW cycle: the History tab filtered to cycle 2 must
        // open with the reason cycle 2 exists, and a reader looking at the sealed
        // cycle 1 is looking at a cycle whose story ended when it closed.
        journal.append(reopenedEntry(ticketId, newCycleNo, caller, request.reason()));

        // D-037 · §4B.6 row 13 — the NEW assignee and the project's PMs. The
        // assignee is passed rather than read back because S-22 lets the
        // reopener change it, and `assignee` here is the value this transaction
        // has just set.
        notifier.reopened(ticket, actorIdOrZero(caller), assignee, newCycleNo);

        return TicketWire.of(ticket);
    }

    /**
     * The {@code REOPENED} history row, shaped as a status change because that is
     * what it is: {@code field_name = "status"}, {@code CLOSED → REOPENED}, with
     * the mandatory reason in {@code remarks}.
     *
     * <p>{@code B-007}'s fixture writes this exact shape for its seeded cycle-2
     * tickets, and matching it matters more than it looks — C-034 interleaves
     * history into one chronological stream, and a real reopen that rendered
     * differently from a seeded one would look like two different events.
     */
    private static TicketHistory reopenedEntry(long ticketId,
                                               short newCycleNo,
                                               Authentication caller,
                                               String reason) {
        TicketHistory entry = new TicketHistory();
        entry.setTicketId(ticketId);
        entry.setCycleNo(newCycleNo);
        entry.setEventType(REOPENED);
        entry.setFieldName("status");
        entry.setOldValue(CLOSED);
        entry.setNewValue(REOPENED);
        Long actor = actorId(caller);
        entry.setActorId(actor);
        // The journal refuses a row whose actor_id and actor_type disagree: no
        // actor means SYSTEM, and SYSTEM means no actor. Unreachable from this
        // route — it is behind hasAuthority('ticket.reopen') — but the pair has
        // to be written consistently rather than assumed.
        entry.setActorType(actor == null ? "SYSTEM" : "USER");
        entry.setRemarks(reason);
        return entry;
    }

    /**
     * S-22's restart stage, checked against <em>this ticket's</em> workflow
     * template rather than against a list of known stage codes.
     *
     * <p>A template is per project and per task type (B's designer), so
     * {@code DEPLOY} being a real stage somewhere says nothing about whether this
     * ticket can restart in it. An unchecked code leaves the ticket with a
     * {@code current_stage} its ribbon cannot draw — see
     * {@link UnknownRestartStageException}.
     *
     * <p><b>A ticket with no template is not refused.</b>
     * {@code workflow_template_id} is nullable and tickets created before B's
     * designer landed have none; there is nothing to validate against, and a
     * reopen is not the moment to start refusing those. The default stage is
     * seeded into every template, so the common path is checked.
     */
    private String restartStage(Ticket ticket, String requested) {
        String stageCode = requested == null || requested.isBlank()
                ? DEFAULT_RESTART_STAGE
                : requested.trim().toUpperCase(java.util.Locale.ROOT);

        Long templateId = ticket.getWorkflowTemplateId();
        if (templateId == null) {
            return stageCode;
        }
        if (stages.findByTemplateIdAndStageCode(templateId, stageCode).isEmpty()) {
            throw new UnknownRestartStageException(stageCode, templateId);
        }
        return stageCode;
    }

    /**
     * S-22's "new assignee (defaults to previous)", where previous is the closing
     * cycle's holder and not the ticket's current one.
     *
     * <p>The two differ whenever a ticket was reassigned after it closed, and the
     * cycle is the better answer: it is who actually did the work being
     * complained about. Falls through to the ticket, then to null — an
     * unassigned reopened ticket is a legitimate state, and C-050 gives it a
     * project-level queue.
     */
    private Long assignee(Ticket ticket, short closingCycleNo, Long requested) {
        if (requested != null) {
            return requested;
        }
        return cycles.findByTicketIdAndCycleNo(ticket.getId(), closingCycleNo)
                .map(TicketCycle::getAssignedTo)
                .filter(java.util.Objects::nonNull)
                .orElseGet(ticket::getAssignedTo);
    }

    /**
     * The new cycle's planned close date, from the §6 ladder measured from the
     * reopen instant.
     *
     * <p>Built from {@code resolve} plus {@code addWorkingHours} rather than from
     * {@code PlannedCloseDateService.preview}, and the difference is the failure
     * mode. {@code preview} serves a form and refuses an unknown project or level
     * so the form can say which field is wrong; here both values come from a
     * stored row, so a refusal would fail a reopen over a field the caller never
     * sent — most likely because a level was retired from the priority master
     * months after this ticket was raised. Resolving instead answers "no target",
     * which is the same thing {@code hasTarget} already means and leaves the
     * reopen to succeed with the date the dialog was going to supply anyway.
     *
     * <p>{@code assignee} is passed so the calendar can stop the clock for that
     * resource's approved leave — the same argument {@code preview} makes for
     * refetching when the assignee changes, and a reopen changes it more often
     * than not.
     */
    private Instant recomputePlannedCloseDate(Ticket ticket, Long assignee, Instant from) {
        SlaResolution sla = slaLadder.resolve(
                ticket.getProjectId(), ticket.getTaskTypeId(), ticket.getLevel());
        if (!sla.hasTarget()) {
            return null;
        }
        return workingHours
                .addWorkingHours(from, sla.resolutionHrs(), ticket.getProjectId(), assignee)
                .truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * Who is reopening. Null would mean SYSTEM, and the journal enforces that
     * pairing; the route is behind {@code hasAuthority('ticket.reopen')}, so an
     * unidentifiable caller cannot reach here.
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
