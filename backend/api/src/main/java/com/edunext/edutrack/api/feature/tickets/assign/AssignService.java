package com.edunext.edutrack.api.feature.tickets.assign;

import com.edunext.edutrack.api.feature.notifications.events.TicketEventNotifier;
import com.edunext.edutrack.api.feature.tickets.TicketRefResolver;
import com.edunext.edutrack.api.feature.tickets.TicketRefResolver;
import com.edunext.edutrack.api.feature.tickets.TicketWire;
import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.api.security.scope.ScopedTickets;
import com.edunext.edutrack.domain.identity.UserRepository;
import com.edunext.edutrack.domain.journal.TicketJournal;
import com.edunext.edutrack.domain.tickets.Ticket;
import com.edunext.edutrack.domain.tickets.TicketHistory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

/**
 * C-049 · {@code POST /tickets/{ticketId}/assign}, blueprint §4A.6.
 *
 * <h2>Deliberately not {@code TransitionService}</h2>
 *
 * <p>{@code TransitionService}'s own javadoc names this task as the thing it
 * does not do: "reassignment-within-a-stage … deliberately does not create a
 * new segment, which is the opposite of what every action code here does."
 * So this class never touches {@code ticket_stage_transitions} — no hop is
 * sealed, none is appended, and {@code TicketJournal.openHopFor} is never
 * consulted. The ticket's {@code assignedTo} moves and one
 * {@code ticket_history} row records it; the ribbon is untouched.
 *
 * <h2>Splitting effort attribution needed nothing new to build</h2>
 *
 * <p>The blueprint's own line — "splits effort attribution between the two,
 * so both appear in the roll-up" — reads like a second write this class
 * would have to make. It is not. {@code ticket_effort_logs.user_id} has
 * always been stamped from <em>whoever is logging the hours</em>, never from
 * {@code tickets.assigned_to} — see {@code EffortLogService.append} and
 * {@code QuickUpdateService.appendEffort}, both of which pass the caller's
 * own id and nothing else. And {@code JourneyRepository.perResource} already
 * groups the roll-up by that same column, independently of which hop an
 * entry falls inside. So an entry logged before this call and one logged
 * after already land under two different resources with no coordination
 * between them — reassigning is not a precondition the roll-up needed
 * satisfied, it is a fact that was already true of every effort log in this
 * codebase.
 *
 * <h2>Who may call this</h2>
 *
 * <p>{@code ticket.assign} — blueprint §2's "Assign / reassign ticket" row,
 * Admin/PM/Support — and unlike {@code PriorityChangeController}'s use of the
 * same capability, this is not a borrowing: assigning or reassigning a
 * ticket is exactly what the code is named for.
 */
@Service
class AssignService {

    private static final String STAGE_REASSIGNED = "STAGE_REASSIGNED";

    private final ScopedTickets tickets;
    private final UserRepository users;
    private final TicketJournal journal;
    private final TicketEventNotifier notifier;
    /**
     * Resolves the four reference objects the contract's {@code Ticket}
     * declares. Without it this route answered {@code assignee: null} — and
     * an assign response whose entire subject is <em>who now holds this</em>
     * must not be the one response that cannot say.
     */
    private final TicketRefResolver refs;

    AssignService(ScopedTickets tickets, UserRepository users, TicketJournal journal,
                  TicketEventNotifier notifier,
                  TicketRefResolver refs) {
        this.tickets = tickets;
        this.users = users;
        this.journal = journal;
        this.notifier = notifier;
        this.refs = refs;
    }

    /**
     * @throws UnknownAssigneeException 400 — {@code assigneeId} names nobody
     */
    @Transactional
    TicketWire.Ticket assign(Authentication caller, String ticketCode, AssignDtos.AssignRequest request) {
        // Scope first, and only once — QuickUpdateService's and
        // PriorityChangeService's own rule for every ticket-mutating service in
        // this package: an out-of-scope ticket is 404 before anything else runs.
        Ticket ticket = tickets.requireByCode(caller, ticketCode);

        Long assigneeId = request.assigneeId();
        Long previous = ticket.getAssignedTo();
        if (Objects.equals(previous, assigneeId)) {
            // Already theirs — an ordinary misclick, not an error, on
            // PriorityChangeService's identical no-op-on-repeat rule, decided
            // before the assignee lookup below for the same reason that rule
            // is: a row reading "Priya -> Priya" cannot be deleted once
            // written, and the History tab is the one place noise is expensive.
            return TicketWire.of(ticket, refs.resolve(ticket));
        }

        if (!users.existsById(assigneeId)) {
            throw new UnknownAssigneeException(assigneeId);
        }

        Long actor = actorId(caller);
        ticket.setAssignedTo(assigneeId);
        ticket.setAssignedBy(actor);
        journal.append(reassignedEntry(ticket, previous, assigneeId, actor, request.note()));

        // D-037 · §4B.6 row 3, "Reassigned within a stage". Last, after every
        // refusal above has had its chance and the history row is written —
        // HandoffService's own ordering rule, for the same reason: nobody is
        // told about a reassignment that did not happen. The notifier swallows
        // and logs its own failures, so an unreachable SMTP host cannot roll
        // this transaction back.
        //
        // A SYSTEM actor (null) is not reachable on this route — the capability
        // check refuses an unidentified caller long before here — and is
        // tolerated rather than asserted, so a future SYSTEM-driven reassignment
        // does not have to revisit this line.
        notifier.reassignedWithinStage(ticket, actor == null ? 0L : actor, previous, assigneeId);

        return TicketWire.of(ticket, refs.resolve(ticket));
    }

    private static TicketHistory reassignedEntry(Ticket ticket, Long previous, Long assigneeId,
                                                  Long actor, String note) {
        TicketHistory entry = new TicketHistory();
        entry.setTicketId(ticket.getId());
        entry.setCycleNo(ticket.getCurrentCycleNo());
        entry.setEventType(STAGE_REASSIGNED);
        entry.setFieldName("assignedTo");
        entry.setOldValue(previous == null ? null : String.valueOf(previous));
        entry.setNewValue(String.valueOf(assigneeId));
        entry.setActorId(actor);
        entry.setActorType(actor == null ? "SYSTEM" : "USER");
        entry.setRemarks(blankToNull(note));
        return entry;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Long actorId(Authentication caller) {
        return Optional.ofNullable(caller)
                .flatMap(CallerIdentity::of)
                .map(CallerIdentity::userId)
                .orElse(null);
    }
}
