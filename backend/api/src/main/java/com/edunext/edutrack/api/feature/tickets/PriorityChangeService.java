package com.edunext.edutrack.api.feature.tickets;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.api.security.scope.ScopedTickets;
import com.edunext.edutrack.domain.journal.TicketJournal;
import com.edunext.edutrack.domain.tickets.Ticket;
import com.edunext.edutrack.domain.tickets.TicketCycle;
import com.edunext.edutrack.domain.tickets.TicketCycleRepository;
import com.edunext.edutrack.domain.tickets.TicketHistory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;

/**
 * C-020 · the level change, blueprint §4B.1.
 *
 * <p>Three writes, one transaction, and only two of them are on the ticket:
 *
 * <ol>
 *   <li>{@code tickets.level} moves</li>
 *   <li>{@code tickets.planned_close_date} is recomputed from the §6 SLA ladder
 *       under the new level</li>
 *   <li>a {@code LEVEL_CHANGED} entry is appended to {@code ticket_history}</li>
 * </ol>
 *
 * <h2>🔴 {@code original_level} is never written here</h2>
 *
 * <p>Not "is restored afterwards" — <em>never written</em>. There is no
 * {@code setOriginalLevel} call below and there must not be one. It is set once
 * by the create path and is the only thing in the schema that can answer A-070's
 * "how many were <em>born</em> critical versus <em>became</em> critical", which
 * is the first question a manager asks of an escalation report. A single
 * overwrite is unrecoverable: the ticket then claims it was always Critical, the
 * history row that would have contradicted it agrees, and nothing in the system
 * knows the difference.
 *
 * <p>{@link TicketWire}'s own javadoc says the same of the field, and
 * {@code ReopenService} records that a reopen leaves both levels alone. This is
 * the one route whose <em>purpose</em> is to move {@code level}, so it is the
 * one place the rule can be broken by an ordinary-looking line, and
 * {@code PriorityChangeServiceTest} asserts it directly rather than by
 * inspection.
 *
 * <h2>Where the recomputed clock starts, and why it is not "now"</h2>
 *
 * <p>{@code ReopenService} recomputes from the reopen instant and argues at
 * length that anything else would leave the ticket "born breached". <b>This
 * method deliberately does the opposite</b>, and the two are consistent rather
 * than contradictory:
 *
 * <ul>
 *   <li>A reopen <em>starts a new cycle</em>. Its clock genuinely begins at the
 *       reopen, so measuring from cycle N's start would be measuring from an
 *       event this cycle had no part in.</li>
 *   <li>A level change happens <em>inside</em> a cycle whose clock is already
 *       running. An SLA is "resolve within N hours of being reported" —
 *       {@code PlannedCloseDateService.preview} says so in as many words — so
 *       the target moves, the start does not.</li>
 * </ul>
 *
 * <p>The consequence is deliberate and worth stating plainly: <b>escalating a
 * three-day-old ticket to a four-hour level produces a planned close date in the
 * past, and the ticket is then breached.</b> That is the true answer. It has
 * been open for three days and somebody has just decided it should have been
 * resolved in four hours; a date measured from now would say it is comfortably
 * on track, which is the reading that lets a genuinely late Critical ticket sit
 * quietly in a queue. Stream D's scanner picking it up immediately is the
 * feature, not the bug — and de-escalation moves the date the other way, out of
 * breach, by the same arithmetic.
 *
 * <p>The clock start is the <b>current cycle's</b> {@code start_date}, falling
 * back to {@code date_reported}. For cycle 1 the two agree; after a reopen they
 * do not, and the cycle's is right — cycle 2's SLA has never had anything to do
 * with when the ticket was first raised.
 *
 * <h2>What this does not do</h2>
 *
 * <p>§4B.1's last row — "setting Critical fires an immediate alert to the
 * assignee, PM and reporting manager" — is <b>not</b> here. Notifications are
 * Stream D's ({@code feature/notifications}, {@code worker/}), and
 * {@code SlaEscalation} already fires exactly that fan-out for the automatic
 * half of the same rule. Raising it rather than writing a second, divergent
 * recipient list in Stream C's package: the {@code LEVEL_CHANGED} row this
 * method appends is the event a notifier would hang off, and it is written
 * whether or not anybody is listening yet.
 *
 * <p>Nor is §4B.1's "Developer/QA/Deployment can only <em>request</em> a change,
 * which raises a notification to the PM". Those three roles are refused at the
 * route (see {@link PriorityChangeController}); the request path they are
 * offered instead is a notification feature and belongs with the one above.
 *
 * <h2>Concurrency: two changes to the same ticket</h2>
 *
 * <p>The ticket is read through {@link ScopedTickets} — a plain {@code SELECT} —
 * and the {@code SELECT … FOR UPDATE} arrives inside the journal's append. So
 * two callers can both read {@code MEDIUM} and both write, and unlike a reopen
 * there is no unique constraint to refuse the loser: this is a last-writer-wins
 * field, and both {@code LEVEL_CHANGED} rows land.
 *
 * <p>That is the right outcome and not a gap. The history chain records both
 * decisions with both actors and both reasons, in order, which is what an audit
 * of "who moved this to Critical" needs; a 409 would instead discard one
 * person's stated reason and leave the ticket claiming the other's. The stale
 * {@code oldValue} on the second row is visible rather than silent — it names a
 * level the first row has already moved away from, which reads correctly as two
 * people acting on the same stale screen.
 */
@Service
class PriorityChangeService {

    private static final String LEVEL_CHANGED = "LEVEL_CHANGED";

    private final ScopedTickets tickets;
    private final TicketCycleRepository cycles;
    private final TicketJournal journal;
    private final PlannedCloseDateService slaLadder;

    /*
     * One constructor, and so no @Autowired — unlike ReopenService, CommentService
     * and AttachmentService, which each carry the marker because a second
     * constructor leaves Spring no candidate to prefer.
     *
     * There is no second constructor here because there is no Clock to inject.
     * Nothing below stamps an instant of its own: the history entry's timestamp
     * is the journal's, and the planned close date is measured from a *stored*
     * start rather than from now — which is the whole argument in the class
     * javadoc above. A clock taken and never read would be a field that looks
     * like testability and provides none.
     */
    PriorityChangeService(ScopedTickets tickets,
                          TicketCycleRepository cycles,
                          TicketJournal journal,
                          PlannedCloseDateService slaLadder) {
        this.tickets = tickets;
        this.cycles = cycles;
        this.journal = journal;
        this.slaLadder = slaLadder;
    }

    /**
     * Move the level, recompute the date, append the row.
     *
     * <p><b>A change to the level it already holds is a no-op</b>, and returns
     * the ticket unchanged with nothing appended. It is not an error: S-20's
     * chip is a picker, and re-picking the selected option is an ordinary
     * misclick. What it must not do is write a {@code LEVEL_CHANGED} row saying
     * {@code HIGH → HIGH}, because the History tab is read as a list of things
     * that happened and a row that records nothing happening is noise in the one
     * place noise is expensive. The planned close date is left alone too — the
     * ladder would resolve the same target, but recomputing it would silently
     * overwrite a date a PM had overridden by hand (§7.5), for no gain.
     *
     * @return the ticket, as the contract's {@code TicketResponse} data
     * @throws com.edunext.edutrack.api.security.scope.TicketNotFoundException
     *         404, identically for a ticket that does not exist and one this
     *         caller may not see (A-035)
     * @throws UnknownLevelException          400; not a code in the priority master
     * @throws LevelReasonRequiredException   400; assigned, and no reason given
     */
    @Transactional
    TicketWire.Ticket change(Authentication caller,
                             String ticketCode,
                             PriorityChangeDtos.ChangePriorityRequest request) {

        // Scope first, and only once. Everything below works from the row this
        // returns, so a caller who got past this line would relevel another
        // project's ticket and no later write would notice.
        //
        // By code, because the contract's TicketId is a code — see
        // PriorityChangeController for why this route resolves it differently
        // from the two beside it. `requireByCode` answers 404 identically for a
        // code that does not exist and one out of scope (A-035).
        Ticket ticket = tickets.requireByCode(caller, ticketCode);

        // Upper-cased for the same reason ReopenService upper-cases its restart
        // stage: the master's codes are upper-case, the lookup is exact, and a
        // client sending "high" should get the level rather than a 400 that
        // reads as "no such level" for a level that plainly exists.
        String level = request.level().trim().toUpperCase(Locale.ROOT);

        String previous = ticket.getLevel();
        if (level.equals(previous)) {
            return TicketWire.of(ticket);
        }

        // §4B.1: mandatory once assigned, optional while the ticket is still
        // being triaged. Checked before the level is validated so a caller who
        // omitted the reason is told about the reason, rather than being sent
        // round the loop twice.
        if (ticket.getAssignedTo() != null && isBlank(request.reason())) {
            throw new LevelReasonRequiredException();
        }

        // Validates the level against the master and recomputes in one call —
        // preview() throws UnknownLevelException (400) for a code S-12 does not
        // carry, which is the check that has to happen before anything is
        // written. Its UnknownProjectException is unreachable: the project id
        // comes from the row and tickets.project_id is a foreign key.
        //
        // The assignee is passed so the calendar can stop the clock for their
        // approved leave, exactly as the create form's preview does — a date
        // promised against somebody who is away all next week is the kind of
        // commitment that gets made to a client.
        PlannedCloseDateService.Preview recomputed = slaLadder.preview(
                ticket.getProjectId(),
                ticket.getTaskTypeId(),
                level,
                ticket.getAssignedTo(),
                slaClockStart(ticket));

        ticket.setLevel(level);
        // 🔴 originalLevel is NOT touched. See the class javadoc; this is the
        // line whose absence is the feature.
        //
        // A null planned close date is written as null rather than skipped: the
        // ladder having no target for the new level is a fact about the new
        // level, and leaving the old level's date in place would have the ticket
        // claiming a commitment nothing now backs. `hasTarget` is the same
        // signal ReopenService reads.
        ticket.setPlannedCloseDate(truncate(recomputed.plannedCloseDate()));

        // Last, and through the journal — which takes the per-ticket
        // SELECT … FOR UPDATE and computes the hash chain (A-042). Stamped with
        // the ticket's current cycle, so the History tab filtered to cycle 2
        // shows the changes made during cycle 2.
        journal.append(levelChangedEntry(ticket, previous, level, caller, request.reason()));

        return TicketWire.of(ticket);
    }

    /**
     * The {@code LEVEL_CHANGED} row, in the shape {@code SlaEscalation} already
     * writes for the automatic half of §4B.1 and {@code SingleTicketFixture}
     * seeds: {@code field_name = "level"}, old → new, reason in
     * {@code remarks}.
     *
     * <p>Matching that shape matters more than it looks. C-034 interleaves
     * history into one chronological stream and A-070 reports over it, so a
     * manual escalation that rendered differently from an automatic one would
     * look like two different kinds of event — when the only real difference is
     * {@code actor_type}, which is precisely the difference those readers exist
     * to show.
     */
    private static TicketHistory levelChangedEntry(Ticket ticket,
                                                   String previous,
                                                   String level,
                                                   Authentication caller,
                                                   String reason) {
        TicketHistory entry = new TicketHistory();
        entry.setTicketId(ticket.getId());
        entry.setCycleNo(ticket.getCurrentCycleNo());
        entry.setEventType(LEVEL_CHANGED);
        entry.setFieldName("level");
        entry.setOldValue(previous);
        entry.setNewValue(level);
        Long actor = actorId(caller);
        entry.setActorId(actor);
        // The journal refuses a row whose actor_id and actor_type disagree: no
        // actor means SYSTEM, and SYSTEM means no actor. Unreachable from this
        // route — it is behind a capability — but the pair has to be written
        // consistently rather than assumed. The SYSTEM rows on this event type
        // are the scanner's, and they must stay distinguishable from a person's.
        entry.setActorType(actor == null ? "SYSTEM" : "USER");
        // Null for an unassigned ticket, where §4B.1 makes the reason optional.
        // Stored as null rather than as an invented string: "no reason was
        // required" and "the reason was empty" must not look alike to the
        // History tab.
        entry.setRemarks(isBlank(reason) ? null : reason.trim());
        return entry;
    }

    /**
     * When this cycle's SLA clock started.
     *
     * <p>The cycle's {@code start_date}, falling back to the ticket's
     * {@code date_reported} — see the class javadoc for why it is not now. The
     * fallback covers a ticket with no {@code ticket_cycles} row, which
     * {@code ReopenService} treats as a hard data defect on the path where a
     * missing row means the ticket has a history it cannot show. Here it does
     * not: this method needs an instant to measure from, {@code date_reported}
     * is the right one for a ticket that has never been reopened, and refusing a
     * level change over a missing cycle row would block the one action that
     * might be urgent.
     */
    private Instant slaClockStart(Ticket ticket) {
        return cycles.findByTicketIdAndCycleNo(ticket.getId(), ticket.getCurrentCycleNo())
                .map(TicketCycle::getStartDate)
                .filter(java.util.Objects::nonNull)
                .orElseGet(ticket::getDateReported);
    }

    /**
     * DATETIME(6) is microsecond precision and MySQL <em>rounds</em> rather than
     * truncating, so an Instant carrying nanoseconds would be stored as a
     * different value from the one that was computed. Truncated on the way in,
     * as {@code ReopenService} does for the same reason.
     */
    private static Instant truncate(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Who is changing it. Null would mean SYSTEM, and the journal enforces that
     * pairing; the route is behind a capability, so an unidentifiable caller
     * cannot reach here.
     */
    private static Long actorId(Authentication caller) {
        return Optional.ofNullable(caller)
                .flatMap(CallerIdentity::of)
                .map(CallerIdentity::userId)
                .orElse(null);
    }
}
