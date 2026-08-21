package com.edunext.edutrack.api.feature.transitions;

import com.edunext.edutrack.api.feature.tickets.TicketWire;
import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.api.security.scope.ScopedTickets;
import com.edunext.edutrack.domain.journal.TicketJournal;
import com.edunext.edutrack.domain.masters.WorkingHoursService;
import com.edunext.edutrack.domain.tickets.Ticket;
import com.edunext.edutrack.domain.tickets.TicketHistory;
import com.edunext.edutrack.domain.workflow.TicketStageTransition;
import com.edunext.edutrack.domain.workflow.WorkflowStage;
import com.edunext.edutrack.domain.workflow.WorkflowStageRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * C-042 · the transition service — blueprint §4A.2.
 *
 * <p>Every move a ticket makes through its ribbon — forward, backward, a
 * skip, a forced override — is one {@code ticket_stage_transitions} row,
 * written through {@link TicketJournal}, which enforces the hash chain and
 * the per-ticket lock but deliberately computes nothing about <em>where</em>
 * the ticket is going: "the journal is a door, not a workflow." This class is
 * the workflow. It seals the hop the ticket is leaving, works out the
 * numbering the next one is entered under, and inserts it — all under one
 * transaction, so a caller never observes a ticket with two open hops or
 * none.
 *
 * <h2>Scope — what C-042 is, and what it is not</h2>
 *
 * <p>This is the shared engine {@link #advance} and nothing that sits on top
 * of it:
 *
 * <ul>
 *   <li><b>The golden rule is enforced here, and only here.</b> §2 — only the
 *       current stage owner, plus PM and Admin, may advance a ticket — is
 *       C-043, checked via {@link StageOwnership#mayAdvance} as the first
 *       thing this method does once the ticket is loaded. It is a shared
 *       predicate rather than a private check so {@code TicketDetailService}
 *       can ask the identical question when deciding whether
 *       {@code handoff}/{@code rework} belong in {@code availableActions} —
 *       see {@link StageOwnership}'s own javadoc.</li>
 *   <li><b>No route.</b> The contract already declares {@code handoffTicket},
 *       {@code reworkTicket} and {@code skipStage}; each is built by the task
 *       that owns it (C-044…C-048) and maps its own request shape onto
 *       {@link TransitionDtos.TransitionRequest}.</li>
 *   <li><b>No in-app notification, no mail.</b> Who gets told a ticket
 *       landed on them, and with what wording, is specific to the action
 *       code — {@code HANDOFF_RECEIVED} for a forward handoff,
 *       {@code QA_FAILED_REWORK} for a rework, and so on — so each stays with
 *       the route that knows which one applies ({@link HandoffNotifier} for
 *       C-045's route today). What <em>is</em> raised here, action-agnostic,
 *       is {@link TicketStageAdvanced} — C-045's wiring of the seam
 *       {@code StageQueueBroadcaster}'s own javadoc left open, and D-058's
 *       {@code stage.changed} push, both fired after commit by
 *       {@link RibbonLiveBroadcaster} since every action code changes the
 *       stage the same way.</li>
 *   <li><b>No mandatory-effort-confirmation, no reassignment-within-a-stage.</b>
 *       The first is a {@code ticket_effort_logs} write with its own required
 *       fields (stage, iteration, hours, work date) that a generic transition
 *       request has no natural place for; the second — C-049 — deliberately
 *       does <em>not</em> create a new segment, which is the opposite of what
 *       every action code here does.</li>
 * </ul>
 *
 * <h2>C-050 · the receiving role with nobody on the project</h2>
 *
 * <p>{@link #resolveAssignee} is the one piece of assignee logic this class
 * does own, and it is narrow: an explicit {@code assigneeId} is trusted
 * outright, and the only case that differs from C-042's original "keep the
 * current owner" default is a destination stage whose role has no active
 * project member at all, which leaves the ticket unassigned rather than
 * silently misattributed. Everything else about who a handoff dialog would
 * offer as a candidate — current load, project membership generally — is
 * still C-044's screen, not this engine.
 *
 * <h2>What advancing a ticket that has never had a first hop does</h2>
 *
 * <p>Refuses, with {@link NoOpenStageException}. Opening the very first hop
 * of a ticket's life — or of a reopened ticket's new cycle — is a different
 * operation from advancing an existing one: there is no stage to seal and no
 * "from" to record. {@code ReopenService}'s own javadoc names this exact gap
 * and says C-042 is where the pair belongs; it remains open because it is a
 * second operation this task did not build, not because it was overlooked.
 * {@link TicketJournal#openHopFor} will find the stale open hop a reopen
 * deliberately leaves behind — but at the <em>wrong</em> cycle, which is
 * exactly what {@link #advance} checks for and refuses on, rather than
 * silently sealing a hop that belongs to a cycle already closed.
 */
@Service
class TransitionService {

    /**
     * The eight moves {@code ticket_stage_transitions.action_code} may carry —
     * {@link TicketStageTransition}'s own javadoc states the set; this is
     * where it is enforced.
     */
    static final Set<String> VALID_ACTIONS = Set.of(
            "FORWARD", "REWORK", "DEPLOY_FAILED", "VERIFY_FAILED", "SIGNOFF_REJECTED",
            "CLARIFICATION", "SKIP", "OVERRIDE");

    /**
     * Mirrors {@code TicketJournal.BACKWARD_ACTIONS} exactly, and has to: that
     * private set is what makes {@code reason} mandatory on the row, and
     * {@code iterationNo} must increment on precisely the same four codes, or
     * "cycle 2 · iteration 3" and the mandatory-reason rule would silently
     * disagree about what a backward move is.
     */
    private static final Set<String> BACKWARD_ACTIONS =
            Set.of("REWORK", "DEPLOY_FAILED", "VERIFY_FAILED", "SIGNOFF_REJECTED");

    private static final String FORWARD = "FORWARD";
    private static final String STAGE_CHANGED = "STAGE_CHANGED";

    private final ScopedTickets tickets;
    private final TicketJournal journal;
    private final WorkflowStageRepository stages;
    private final WorkingHoursService workingHours;
    private final ReceivingRoleRepository receivingRoles;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    /*
     * @Autowired is required rather than decoration — see ReopenService's
     * identical note. Two constructors leaves Spring no candidate to prefer.
     */
    @Autowired
    TransitionService(ScopedTickets tickets,
                      TicketJournal journal,
                      WorkflowStageRepository stages,
                      WorkingHoursService workingHours,
                      ReceivingRoleRepository receivingRoles,
                      ApplicationEventPublisher events) {
        this(tickets, journal, stages, workingHours, receivingRoles, events, Clock.systemUTC());
    }

    TransitionService(ScopedTickets tickets,
                      TicketJournal journal,
                      WorkflowStageRepository stages,
                      WorkingHoursService workingHours,
                      ReceivingRoleRepository receivingRoles,
                      ApplicationEventPublisher events,
                      Clock clock) {
        this.tickets = tickets;
        this.journal = journal;
        this.stages = stages;
        this.workingHours = workingHours;
        this.receivingRoles = receivingRoles;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Seal the current open hop, insert the next.
     *
     * <h2>Numbering</h2>
     *
     * <ul>
     *   <li>{@code cycleNo} is always the ticket's current cycle — this method
     *       only ever writes into the cycle already open.</li>
     *   <li>{@code seqNo} is the current hop's plus one.</li>
     *   <li>{@code iterationNo} increments only for
     *       {@link #BACKWARD_ACTIONS}; every other action carries the current
     *       hop's iteration forward unchanged (§4A.2 — iteration counts
     *       backward moves, not hops).</li>
     * </ul>
     *
     * <h2>{@code toStageCode}</h2>
     *
     * <p>Required for every action but {@code FORWARD}, which defaults to the
     * template's next stage after the one being left
     * ({@link #nextStageAfter}). Whatever the code resolves to is validated
     * against the ticket's workflow template — a ticket with none is not
     * checked, on {@code ReopenService.restartStage}'s own precedent, since
     * there is nothing to validate against and plenty of seeded tickets
     * predate B's designer.
     *
     * @throws com.edunext.edutrack.api.security.scope.TicketNotFoundException
     *         404, identically for a ticket that does not exist and one this
     *         caller may not see
     * @throws NotCurrentStageOwnerException  422 — the golden rule, C-043
     * @throws NoOpenStageException           422 — no open hop on this cycle
     * @throws UnknownActionCodeException     400
     * @throws ToStageRequiredException       400
     * @throws NoNextStageException           400
     * @throws UnknownTransitionStageException 400
     */
    @Transactional
    TicketWire.Ticket advance(Authentication caller, long ticketId,
                              TransitionDtos.TransitionRequest request) {
        Ticket ticket = tickets.require(caller, ticketId);

        // C-043 · the golden rule, checked before anything about the request
        // itself: an unauthorised caller gets no feedback about the move they
        // tried to make, only that they may not make one. An unidentifiable
        // caller (CallerIdentity.of empty) is denied the same way — "absent,
        // not defaulted" per that class's own doc.
        CallerIdentity identity = CallerIdentity.of(caller).orElse(null);
        if (identity == null || !StageOwnership.mayAdvance(identity, ticket)) {
            throw new NotCurrentStageOwnerException(ticketId, ticket.getAssignedTo());
        }

        String actionCode = normalize(request.actionCode());
        if (!VALID_ACTIONS.contains(actionCode)) {
            throw new UnknownActionCodeException(actionCode, VALID_ACTIONS);
        }

        TicketStageTransition open = journal.openHopFor(ticketId)
                .filter(hop -> hop.getCycleNo() == ticket.getCurrentCycleNo())
                .orElseThrow(() -> new NoOpenStageException(ticketId));

        String toStage = resolveToStage(ticket, actionCode, open.getToStage(), request.toStageCode());

        boolean backward = BACKWARD_ACTIONS.contains(actionCode);
        short iterationNo = backward ? (short) (open.getIterationNo() + 1) : open.getIterationNo();
        int seqNo = open.getSeqNo() + 1;
        Long toUserId = resolveAssignee(ticket, toStage, request.assigneeId());

        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);

        // Seal what is being left before inserting what is being entered — the
        // order append() itself insists on (it refuses a second open hop), and
        // the same order ReopenService documents for the same reason.
        journal.seal(open.getId(), now, workingMinutesFor(open, now, ticket));

        TicketStageTransition hop = new TicketStageTransition();
        hop.setTicketId(ticketId);
        hop.setCycleNo(ticket.getCurrentCycleNo());
        hop.setIterationNo(iterationNo);
        hop.setSeqNo(seqNo);
        hop.setFromStage(open.getToStage());
        hop.setToStage(toStage);
        hop.setFromUserId(open.getToUserId());
        hop.setToUserId(toUserId);
        hop.setActionCode(actionCode);
        hop.setHandoffNote(request.handoffNote());
        hop.setReason(request.reason());
        hop.setEnteredAt(now);
        journal.append(hop);

        journal.append(stageChangedEntry(ticket, open.getToStage(), toStage, caller, request));

        ticket.setCurrentStage(toStage);
        ticket.setStageEnteredAt(now);
        ticket.setCurrentIteration(iterationNo);
        if (request.assigneeId() != null) {
            ticket.setAssignedTo(request.assigneeId());
            ticket.setAssignedBy(actorId(caller));
        } else if (!Objects.equals(toUserId, ticket.getAssignedTo())) {
            // C-050 · resolveAssignee found nobody: the receiving role has no
            // member on this project, so the ticket falls to the project-level
            // queue rather than keeping the outgoing owner, who does not hold
            // the role that now owns the stage. assignedBy is left as whoever
            // last made a real assignment — nobody chose this, the queue did.
            ticket.setAssignedTo(null);
        }
        // rework_count counts backward moves over the ticket's whole life
        // (A-070) and is never reset — ReopenService's own note on the same
        // field says as much for the reopen path; this is the other place it
        // moves.
        if (backward) {
            ticket.setReworkCount((short) (ticket.getReworkCount() + 1));
        }

        // C-045 · the seam StageQueueBroadcaster's own javadoc names as
        // waiting on this task, plus D-058's ticket-topic push — both
        // action-agnostic, so raised here rather than by whichever route
        // called advance. RibbonLiveBroadcaster delivers both after commit.
        events.publishEvent(new TicketStageAdvanced(ticketId, ticket.getProjectId(), open.getToStage(), toStage));

        return TicketWire.of(ticket);
    }

    /**
     * The destination stage, resolved and validated.
     *
     * <p>An explicit {@code toStageCode} always wins. {@code FORWARD} with
     * none falls back to {@link #nextStageAfter}; every other action with none
     * is refused — a rework, a skip or an override with a guessed destination
     * moves a ticket somewhere nobody asked for.
     */
    private String resolveToStage(Ticket ticket, String actionCode, String fromStage, String requested) {
        String stageCode;
        if (requested != null && !requested.isBlank()) {
            stageCode = normalize(requested);
        } else if (FORWARD.equals(actionCode)) {
            stageCode = nextStageAfter(ticket, fromStage);
        } else {
            throw new ToStageRequiredException(actionCode);
        }

        Long templateId = ticket.getWorkflowTemplateId();
        if (templateId != null && stages.findByTemplateIdAndStageCode(templateId, stageCode).isEmpty()) {
            throw new UnknownTransitionStageException(stageCode, templateId);
        }
        return stageCode;
    }

    /**
     * C-050 · blueprint §4A.6 — "if the receiving role has no member on the
     * project, the handoff falls to a project-level queue."
     *
     * <p>An explicit {@code assigneeId} always wins with no lookup here:
     * C-044's dialog only ever offers a member of the receiving role, so a
     * caller that names somebody has already answered this question. Absent
     * one, the ticket keeps its outgoing owner — unchanged since C-042 — with
     * one exception: if the destination stage's role genuinely has nobody
     * active on this project, carrying the old owner forward would leave a
     * Developer silently "owning" a stage QA is meant to run, which is a
     * worse failure than no owner at all, because it looks like the ribbon is
     * fine when the queue is actually stuck. {@code null} here is what D-026's
     * scanner already watches for and alerts the PM about after two working
     * hours — this method does not need to know that, only to stop hiding it.
     *
     * <p>A ticket with no workflow template has no stage to look up a role
     * for, so it is left exactly as {@code advance} always left it —
     * {@link #resolveToStage}'s own precedent for the same gap.
     */
    private Long resolveAssignee(Ticket ticket, String toStage, Long requestedAssigneeId) {
        if (requestedAssigneeId != null) {
            return requestedAssigneeId;
        }
        Long templateId = ticket.getWorkflowTemplateId();
        String ownerRole = templateId == null ? null
                : stages.findByTemplateIdAndStageCode(templateId, toStage)
                        .map(WorkflowStage::getOwnerRole)
                        .orElse(null);
        if (ownerRole == null || receivingRoles.hasActiveMember(ticket.getProjectId(), ownerRole)) {
            return ticket.getAssignedTo();
        }
        return null;
    }

    /**
     * The template's stage immediately after {@code fromStage}, left to right.
     *
     * <p>Package-private rather than private since C-047, which needs the
     * identical answer for a different reason: {@code skip-stage} defaults its
     * destination to the template's next stage, but {@link #resolveToStage}
     * deliberately reserves that fallback for {@code FORWARD} — widening it
     * would hand the same guess to {@code CLARIFICATION} and {@code OVERRIDE},
     * which is what {@link ToStageRequiredException} exists to prevent. Shared
     * rather than copied so "the template's next stage" cannot come to mean two
     * things.
     */
    String nextStageAfter(Ticket ticket, String fromStage) {
        Long templateId = ticket.getWorkflowTemplateId();
        if (templateId == null) {
            throw new NoNextStageException(ticket.getId(), fromStage);
        }
        List<WorkflowStage> ordered = stages.findByTemplateIdOrderBySeqAsc(templateId);
        for (int i = 0; i < ordered.size() - 1; i++) {
            if (ordered.get(i).getStageCode().equals(fromStage)) {
                return ordered.get(i + 1).getStageCode();
            }
        }
        throw new NoNextStageException(ticket.getId(), fromStage);
    }

    /**
     * The hop being sealed's duration, in <b>working</b> minutes — never
     * wall-clock, per every other place this figure is computed in this
     * codebase.
     *
     * <p>{@link WorkingHoursService#workingHoursBetween} answers in hours to
     * two decimal places, which {@code journal.seal} cannot take directly. The
     * result is clamped to the wall-clock ceiling rather than trusted as-is:
     * working time is a subset of elapsed time by construction, but two
     * roundings — hours to 2dp, then hours to minutes — can in principle land
     * a working-minutes figure a minute above a wall-clock span that itself
     * rounds to a whole number of minutes. {@code seal}'s own guard would
     * refuse that arithmetically-impossible-looking figure outright; clamping
     * here is cheaper than a handoff failing on a rounding artefact nobody
     * could explain by reading the numbers back.
     */
    private int workingMinutesFor(TicketStageTransition open, Instant now, Ticket ticket) {
        BigDecimal workingHrs = workingHours.workingHoursBetween(
                open.getEnteredAt(), now, ticket.getProjectId(), open.getToUserId());
        long workingMins = workingHrs.multiply(BigDecimal.valueOf(60))
                .setScale(0, RoundingMode.HALF_UP).longValueExact();
        long wallClockMins = (Duration.between(open.getEnteredAt(), now).toSeconds() + 59) / 60;
        return (int) Math.min(workingMins, wallClockMins);
    }

    /**
     * The accompanying {@code ticket_history} row — {@code TicketJournal}'s own
     * javadoc names this as part of "the journal is a door, not a workflow":
     * writing the history that goes with a transition belongs to whichever
     * service moves the ticket.
     */
    private static TicketHistory stageChangedEntry(Ticket ticket, String fromStage, String toStage,
                                                    Authentication caller,
                                                    TransitionDtos.TransitionRequest request) {
        TicketHistory entry = new TicketHistory();
        entry.setTicketId(ticket.getId());
        entry.setCycleNo(ticket.getCurrentCycleNo());
        entry.setEventType(STAGE_CHANGED);
        entry.setFieldName("current_stage");
        entry.setOldValue(fromStage);
        entry.setNewValue(toStage);
        Long actor = actorId(caller);
        entry.setActorId(actor);
        entry.setActorType(actor == null ? "SYSTEM" : "USER");
        entry.setRemarks(request.reason() != null ? request.reason() : request.handoffNote());
        return entry;
    }

    /**
     * Who is advancing the ticket. Null means SYSTEM, and the journal enforces
     * that pairing; every route this will eventually sit behind requires an
     * identified caller, the same as {@code ReopenService}.
     */
    private static Long actorId(Authentication caller) {
        return Optional.ofNullable(caller)
                .flatMap(CallerIdentity::of)
                .map(CallerIdentity::userId)
                .orElse(null);
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}
