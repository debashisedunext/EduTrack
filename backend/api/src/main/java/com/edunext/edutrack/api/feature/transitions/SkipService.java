package com.edunext.edutrack.api.feature.transitions;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.api.security.scope.ScopedTickets;
import com.edunext.edutrack.domain.tickets.Ticket;
import com.edunext.edutrack.domain.workflow.WorkflowStage;
import com.edunext.edutrack.domain.workflow.WorkflowStageRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * C-047 · {@code POST /tickets/{ticketId}/skip-stage} — the shared engine
 * ({@code TransitionService}, C-042) with the {@code SKIP} action code fixed,
 * plus the two things a skip needs that a generic transition does not.
 *
 * <p>Everything the ribbon and the queues need is already free, exactly as
 * C-045's note promised for C-046/047/048: {@code advance} seals the hop being
 * left, appends the next, leaves {@code iterationNo} and {@code cycleNo}
 * alone — a skip is a forward move, not rework — and raises
 * {@link TicketStageAdvanced}, which {@link RibbonLiveBroadcaster} turns into
 * the live ribbon frame and the stage-queue nudges. None of it is repeated
 * here, and {@code status} is deliberately untouched, on the contract's own
 * wording: "{@code handoff}, {@code skip-stage} and {@code force-move} all
 * leave the status alone" — only a backward move says something about the
 * work rather than about where it is.
 *
 * <h2>1 · Which stage is being skipped</h2>
 *
 * <p><b>The one the ticket is standing in</b>, and {@code toStageCode} is
 * where it lands — the same meaning that field carries on the three sibling
 * routes in this package. This is worth stating plainly because the codebase
 * has said both things: {@code ToStageRequiredException}'s javadoc read
 * "{@code SKIP} names which stage is being skipped past" until this task
 * corrected it, while {@code frontend/src/mocks/handlers/ribbon.ts} has
 * stamped the skip onto the stage being <em>left</em> since D-004 and the
 * frontend is coded against that.
 *
 * <p>The mock wins, on two grounds. A field that means "destination" on
 * {@code /handoff}, {@code /rework} and {@code /force-move} and "the stage to
 * skip" on the fourth route is precisely the kind of ambiguity that writes an
 * unrecoverable row into an append-only, hash-chained ledger. And the
 * alternative reading needs <em>two</em> hops per skip — one into the skipped
 * stage carrying the reason, one straight back out — which invents a
 * zero-duration segment, fires the queue nudge for a role that never receives
 * the ticket, and runs {@code resolveAssignee} through a stage nobody owns.
 * One hop is both simpler and truer: the ticket left the stage by skipping it.
 *
 * <p>{@link RibbonAssembler} was keyed on the wrong end of that hop (a
 * {@code SKIP} arrival struck through the <em>destination</em> segment) and is
 * corrected by this task — see its own note. Nothing had exercised it: no
 * route wrote a {@code SKIP} row until this one.
 *
 * <h2>2 · The stage must be optional</h2>
 *
 * <p>Blueprint §4A.6 — "Skipping an optional stage requires PM/Admin and a
 * reason" — and {@code workflow_stages.is_optional} is §7's column for saying
 * which stages those are. Checked here rather than in {@code advance}, on
 * {@code ReworkService}'s own reasoning for {@code can_return_to}:
 * {@code ForceMoveService} routes through the same engine and its entire
 * purpose is moving where the template does not allow. Refused with 422 —
 * {@link StageNotOptionalException} says why.
 *
 * <p><b>A ticket with no workflow template is not checked</b>, and neither is
 * one standing in a stage that is not on its own template, both on
 * {@code ReworkService.requireReturnTargetAllowed}'s own precedent and for its
 * reasons: there is nothing to validate against, plenty of seeded tickets
 * predate B's designer, and inventing a refusal for the second case would hide
 * whichever real problem produced it.
 *
 * <h2>3 · Who authorised it, and why it goes in the reason</h2>
 *
 * <p>§4A.3's segment-state table asks the skipped segment's hover to show
 * "the skip reason <em>and who authorised it</em>", and Stream B's B-045 note
 * flags the missing authoriser as this task's to supply.
 * {@code ticket_stage_transitions} has {@code from_user_id} and
 * {@code to_user_id} — the outgoing and incoming stage owners, neither of whom
 * is the PM who authorised the skip — and no actor column at all; the actor is
 * recorded only on the accompanying {@code ticket_history} row, which is a
 * different tab.
 *
 * <p>So the authoriser is appended to the stored reason, exactly as
 * {@code ReworkService} folds a defect list into the same column and for the
 * same reason: the alternatives are a migration against an append-only
 * hash-chained table for one name, or a new {@code RibbonSegment.skippedBy}
 * field that would still have nothing to read. The name is denormalised at the
 * moment of the skip and does not follow later renames — which is the correct
 * behaviour for a ledger entry rather than a defect: it records who authorised
 * it then.
 */
@Service
class SkipService {

    private static final String SKIP = "SKIP";

    private final ScopedTickets tickets;
    private final TransitionService transitionService;
    private final WorkflowStageRepository stages;
    private final TransitionUserRefs people;
    private final RibbonAssembler ribbon;

    SkipService(ScopedTickets tickets, TransitionService transitionService, WorkflowStageRepository stages,
                TransitionUserRefs people, RibbonAssembler ribbon) {
        this.tickets = tickets;
        this.transitionService = transitionService;
        this.stages = stages;
        this.people = people;
        this.ribbon = ribbon;
    }

    /**
     * @throws StageNotOptionalException       422 — the stage being left is not skippable
     * @throws NotCurrentStageOwnerException   422 — the golden rule, from {@code advance}
     * @throws NoOpenStageException            422 — from {@code advance}
     * @throws NoNextStageException            400 — no {@code toStageCode} and no next stage to default to
     * @throws UnknownTransitionStageException 400 — not a stage on this template, from {@code advance}
     */
    @Transactional
    SkipDtos.RibbonResponse skip(Authentication caller, String ticketCode, SkipDtos.SkipRequest request) {
        Ticket ticket = tickets.requireByCode(caller, ticketCode);

        // Read before advance() moves the ticket on — HandoffService's and
        // ReworkService's own rule, for their reason.
        String skippedStage = ticket.getCurrentStage();

        // Refused before the destination is resolved: "this stage cannot be
        // skipped" is a truer answer than "there is no next stage to skip to"
        // for a ticket standing in the template's last, non-optional stage,
        // and both would otherwise be reachable from the same request.
        requireSkippable(ticket, skippedStage);

        String toStage = resolveDestination(ticket, skippedStage, request.toStageCode());

        TransitionDtos.TransitionRequest transitionRequest = new TransitionDtos.TransitionRequest(
                SKIP, toStage, null, null, reasonWithAuthoriser(caller, request.reason()));

        // The golden rule and every other refusal fire in here.
        transitionService.advance(caller, ticket.getId(), transitionRequest);

        boolean canAdvance = CallerIdentity.of(caller)
                .map(identity -> StageOwnership.mayAdvance(identity, ticket))
                .orElse(false);
        return new SkipDtos.RibbonResponse(ribbon.assembleCurrentCycle(ticket, canAdvance));
    }

    /** §4A.6's optional-stage rule, read off the stage the ticket is standing in. */
    private void requireSkippable(Ticket ticket, String stageCode) {
        Long templateId = ticket.getWorkflowTemplateId();
        if (templateId == null || stageCode == null || stageCode.isBlank()) {
            return;
        }
        WorkflowStage stage = stages.findByTemplateIdAndStageCode(templateId, normalize(stageCode)).orElse(null);
        if (stage == null || stage.isOptional()) {
            return;
        }
        throw new StageNotOptionalException(stage.getDisplayName(), stage.getStageCode());
    }

    /**
     * Where the ticket lands — the caller's {@code toStageCode}, or the
     * template's next stage after the one being skipped.
     *
     * <p>Defaulted here rather than in {@code advance}, which requires an
     * explicit destination for every action but {@code FORWARD} and should
     * keep doing so: the default belongs to the route the contract documents
     * it on, and widening the engine's rule would hand the same fallback to
     * {@code CLARIFICATION} and {@code OVERRIDE}, where a guessed destination
     * is exactly what {@link ToStageRequiredException} exists to prevent.
     * {@link TransitionService#nextStageAfter} is reused rather than copied so
     * "the template's next stage" cannot come to mean two things.
     */
    private String resolveDestination(Ticket ticket, String skippedStage, String requested) {
        if (requested != null && !requested.isBlank()) {
            return requested;
        }
        return transitionService.nextStageAfter(ticket, skippedStage);
    }

    /**
     * The reason with "Skipped by …" appended — the class javadoc's §3 says
     * why it lands in this column rather than one of its own.
     *
     * <p>An unresolvable caller leaves the reason exactly as sent rather than
     * appending a placeholder: {@code advance} refuses an unidentifiable
     * caller outright a moment later, so this is only reachable for an
     * identified caller whose {@code users} row has since gone, and "Skipped
     * by: Unknown" is worse than the reason on its own.
     */
    private String reasonWithAuthoriser(Authentication caller, String rawReason) {
        String reason = rawReason.trim();
        return CallerIdentity.of(caller)
                .map(CallerIdentity::userId)
                .map(userId -> people.resolve(List.of(userId)).get(userId))
                .map(ref -> reason + "\n\nSkipped by: " + ref.displayName())
                .orElse(reason);
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}
