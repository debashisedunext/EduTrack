package com.edunext.edutrack.api.feature.transitions;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.api.security.scope.ScopedTickets;
import com.edunext.edutrack.domain.journal.TicketJournal;
import com.edunext.edutrack.domain.tickets.Ticket;
import com.edunext.edutrack.domain.tickets.TicketCycle;
import com.edunext.edutrack.domain.tickets.TicketCycleRepository;
import com.edunext.edutrack.domain.tickets.TicketEffortLog;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * C-044 · {@code POST /tickets/{ticketId}/handoff} — the shared engine
 * ({@code TransitionService}, C-042) plus the two things it deliberately does
 * not do (its own javadoc names both): the golden-rule route, and the
 * mandatory effort-confirmation write for the stage being left.
 *
 * <h2>Effort is captured before {@link TransitionService#advance} runs</h2>
 *
 * <p>{@code advance} mutates the ticket's {@code currentStage},
 * {@code currentIteration} in place — so the stage/cycle/iteration the
 * confirmed hours belong to has to be read <b>before</b> calling it, not
 * derived from the ticket afterwards. This method captures all three first
 * and only then calls {@code advance}, so the golden rule and every other
 * refusal {@code advance} raises fires before anything here has been
 * written — an unauthorised caller's attempted effort confirmation never
 * reaches the ledger, and the whole method is one {@code @Transactional}
 * unit, so a later refusal rolls back an earlier write within it too.
 *
 * <h2>Why this does not call {@code EffortLogService}</h2>
 *
 * <p>{@code EffortLogService.log} stamps an entry from the ticket's
 * <em>current</em> stage/cycle/iteration at the moment it runs — exactly
 * wrong here, since by the time effort would be confirmed {@code advance} has
 * already moved the ticket on. It is also package-private in
 * {@code feature.tickets.effort}, on this codebase's "a feature declares its
 * own write" convention ({@code EffortLogUserRefs}' own javadoc states it for
 * reads; {@code CommentService}/{@code AttachmentService} each hold their own
 * writes rather than reaching into a sibling feature). So this method writes
 * the entry directly through {@link TicketJournal#append(TicketEffortLog)}
 * and replicates C-041's total-refresh — {@code EffortLogService.refreshTotals}
 * — rather than calling either.
 *
 * <h2>{@code attachmentIds} — accepted, not linked</h2>
 *
 * <p>Nothing here writes anything for them. {@code AttachmentController}'s
 * own upload path already stamps {@code stageCode}/{@code cycleNo} on a file
 * at upload time (C-025/C-028, confirmed again by C-060's note that the
 * columns "were already shipped for the strip's own use") — the same
 * auto-stamping principle CLAUDE.md states for effort. So an attachment
 * picked in the handoff dialog was tagged to the stage being left the moment
 * it was uploaded, and {@code attachmentIds} on the request is the client
 * telling the server which files it already showed the user, not a link this
 * route has to create. If a future screen needs attachments picked <em>at</em>
 * handoff time but uploaded earlier under a different stage, that is a real
 * gap and belongs to whichever task finds it, not guessed at here.
 *
 * <h2>The path variable is the ticket code, not the numeric id, from the start</h2>
 *
 * <p>{@code CloseController}'s own note records {@code @PathVariable long
 * ticketId} as a bug caught only by running the packaged app against the real
 * ticket-code path segment — {@code ReopenController} and
 * {@code TicketDetailController} still carry it. This route is new, so it is
 * built with {@code String} and {@link ScopedTickets#requireByCode} from the
 * first commit rather than repeating the mistake a third time.
 */
@Service
class HandoffService {

    private final ScopedTickets tickets;
    private final TransitionService transitionService;
    private final TicketJournal journal;
    private final TicketCycleRepository cycles;
    private final RibbonAssembler ribbon;

    HandoffService(ScopedTickets tickets, TransitionService transitionService, TicketJournal journal,
                   TicketCycleRepository cycles, RibbonAssembler ribbon) {
        this.tickets = tickets;
        this.transitionService = transitionService;
        this.journal = journal;
        this.cycles = cycles;
        this.ribbon = ribbon;
    }

    /**
     * @throws EffortHoursRequiredException         400 — see the exception's own javadoc
     * @throws NotCurrentStageOwnerException 422 — the golden rule, from {@code advance}
     * @throws NoOpenStageException          422 — from {@code advance}
     * @throws UnknownTransitionStageException 400 — {@code toStageCode} not on this template
     */
    @Transactional
    HandoffDtos.RibbonResponse handoff(Authentication caller, String ticketCode, HandoffDtos.HandoffRequest request) {
        Ticket ticket = tickets.requireByCode(caller, ticketCode);

        if (request.effortHours() == null) {
            throw new EffortHoursRequiredException();
        }

        // Captured before advance() mutates the ticket — see the class javadoc.
        String leavingStage = ticket.getCurrentStage();
        short leavingCycle = ticket.getCurrentCycleNo();
        short leavingIteration = ticket.getCurrentIteration();

        TransitionDtos.TransitionRequest transitionRequest = new TransitionDtos.TransitionRequest(
                "FORWARD", request.toStageCode(), request.toUserId(), request.note(), null);

        // The golden rule (and every other refusal) fires in here, before a
        // single row below has been written.
        transitionService.advance(caller, ticket.getId(), transitionRequest);

        if (request.effortHours().compareTo(BigDecimal.ZERO) > 0) {
            logEffort(caller, ticket, leavingStage, leavingCycle, leavingIteration, request.effortHours());
        }

        boolean canAdvance = CallerIdentity.of(caller)
                .map(identity -> StageOwnership.mayAdvance(identity, ticket))
                .orElse(false);
        return new HandoffDtos.RibbonResponse(ribbon.assembleCurrentCycle(ticket, canAdvance));
    }

    /**
     * The confirmation write, stamped with the stage being <b>left</b> —
     * captured by the caller before {@code advance} moved the ticket on — and
     * attributed to whoever is confirming, not to the ticket's assignee. They
     * usually coincide (only the stage owner, PM or Admin reach this at all),
     * but a PM handing off on someone else's behalf confirms their own
     * involvement, not the developer's — {@code EffortLogService.append}'s
     * identical choice of {@code actorId(caller)} over the ticket's
     * {@code assignedTo}.
     *
     * <p>C-041's running totals, replicated rather than shared — see the class
     * javadoc's "why this does not call {@code EffortLogService}" heading.
     */
    private void logEffort(Authentication caller, Ticket ticket, String leavingStage, short leavingCycle,
                           short leavingIteration, BigDecimal hours) {
        long actorId = CallerIdentity.of(caller)
                .map(CallerIdentity::userId)
                .orElseThrow(() -> new IllegalStateException(
                        "a handoff reached the effort write with no identifiable actor; "
                                + "the golden rule above should already have refused this caller"));

        TicketEffortLog entry = new TicketEffortLog();
        entry.setTicketId(ticket.getId());
        entry.setCycleNo(leavingCycle);
        entry.setStageCode(leavingStage);
        entry.setIterationNo(leavingIteration);
        entry.setUserId(actorId);
        entry.setWorkDate(java.time.LocalDate.now());
        entry.setHours(hours);
        TicketEffortLog saved = journal.append(entry);

        TicketCycle cycle = cycles.findByTicketIdAndCycleNo(ticket.getId(), saved.getCycleNo())
                .orElseThrow(() -> new IllegalStateException(
                        "handoff effort " + saved.getId() + " landed on ticket " + ticket.getId()
                                + " cycle " + saved.getCycleNo() + ", which has no ticket_cycles row"));
        cycle.setEffortHrs(cycle.getEffortHrs().add(saved.getHours()));
        ticket.setTotalEffortHrs(ticket.getTotalEffortHrs().add(saved.getHours()));
    }
}
