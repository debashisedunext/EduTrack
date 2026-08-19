package com.edunext.edutrack.api.feature.transitions;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.api.security.scope.ScopedTickets;
import com.edunext.edutrack.domain.tickets.Ticket;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * C-048 · {@code POST /tickets/{ticketId}/force-move} — the shared engine
 * ({@code TransitionService}, C-042) with the {@code OVERRIDE} action code
 * fixed, plus nothing else: no effort confirmation ({@code HandoffService}'s
 * is specific to leaving a stage the normal way), no dedicated notifier
 * ({@code TicketStageAdvanced}/{@code RibbonLiveBroadcaster} already push the
 * live ribbon and team-queue update action-agnostically, C-045's own note on
 * why C-046/047/048 "get it for free").
 *
 * <p><b>Why this is thinner than {@link HandoffService}.</b> A handoff moves a
 * ticket exactly one hop along a template it already knows, so the stage being
 * left is worth capturing for the mandatory effort write. A force-move is a
 * PM/Admin override with no such stage-specific bookkeeping of its own — the
 * blueprint's §2 row is "Force-move ribbon backwards", not "confirm effort for
 * the stage being left", and {@code ticket_effort_logs} is not among the rows
 * this action is documented to touch.
 *
 * <p>The path variable is the ticket <em>code</em>, not the numeric id, from
 * the first commit — {@code CloseController}'s own note records
 * {@code @PathVariable long ticketId} as a bug caught only by exercising the
 * real backend, and {@code HandoffService}'s own note says the same for its
 * route; this one is built with {@code String} and
 * {@link ScopedTickets#requireByCode} rather than repeating it a third time.
 */
@Service
class ForceMoveService {

    private static final String OVERRIDE = "OVERRIDE";

    private final ScopedTickets tickets;
    private final TransitionService transitionService;
    private final RibbonAssembler ribbon;

    ForceMoveService(ScopedTickets tickets, TransitionService transitionService, RibbonAssembler ribbon) {
        this.tickets = tickets;
        this.transitionService = transitionService;
        this.ribbon = ribbon;
    }

    /**
     * @throws NotCurrentStageOwnerException   422 — from {@code advance}; in
     *         practice unreachable, since {@code ticket.force_move} is
     *         Admin/PM's alone and both already satisfy
     *         {@code StageOwnership.mayAdvance} regardless of assignment —
     *         see the class javadoc
     * @throws NoOpenStageException            422 — from {@code advance}
     * @throws UnknownTransitionStageException 400 — {@code toStageCode} not on
     *         this ticket's workflow template
     */
    @Transactional
    ForceMoveDtos.RibbonResponse forceMove(Authentication caller, String ticketCode,
                                           ForceMoveDtos.ForceMoveRequest request) {
        Ticket ticket = tickets.requireByCode(caller, ticketCode);

        TransitionDtos.TransitionRequest transitionRequest = new TransitionDtos.TransitionRequest(
                OVERRIDE, request.toStageCode(), request.toUserId(), null, request.reason());

        transitionService.advance(caller, ticket.getId(), transitionRequest);

        boolean canAdvance = CallerIdentity.of(caller)
                .map(identity -> StageOwnership.mayAdvance(identity, ticket))
                .orElse(false);
        return new ForceMoveDtos.RibbonResponse(ribbon.assembleCurrentCycle(ticket, canAdvance));
    }
}
