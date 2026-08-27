package com.edunext.edutrack.api.feature.transitions;

import com.edunext.edutrack.domain.tickets.Ticket;
import com.edunext.edutrack.domain.workflow.WorkflowStage;
import com.edunext.edutrack.domain.workflow.WorkflowStageRepository;

import java.util.List;

/**
 * "Is this the last stage on the ticket's template?" — the Closed segment of
 * §4A.1, the one with nothing after it to hand off to.
 *
 * <h2>Why this is a shared predicate and not a private method</h2>
 *
 * <p>Exactly {@link StageOwnership}'s reasoning, for exactly the same pair of
 * callers. {@link TransitionService} needs the answer to decide that a forward
 * move into that stage is the sign-off — the work is claimed complete, so the
 * ticket becomes {@code RESOLVED}, which is the one status
 * {@code CloseService} will close from. {@code TicketDetailService} needs the
 * identical answer to decide that {@code handoff} has left
 * {@code availableActions} and {@code close} has arrived in it. Two private
 * copies of "which stage is last" is the divergence the contract's own note on
 * that field warns about: "the client renders buttons from this rather than
 * re-deriving permissions, because two implementations of the same rule always
 * diverge."
 *
 * <h2>What an unknown template answers</h2>
 *
 * <p>{@code false}. A ticket with no {@code workflow_template_id}, a template
 * with no stages, or a ticket standing in a stage that is not on its own
 * template is <em>not</em> treated as terminal — {@code TransitionService}'s
 * {@code resolveToStage} and {@code TicketDetailService}'s
 * {@code isCurrentStageSkippable} both take the same position on the same gap,
 * and for the same reason: there is nothing to check against, and inventing an
 * answer here would strip {@code handoff} off tickets that predate B-043's
 * designer and can still legitimately move. {@code nextStageAfter} refusing
 * with {@link NoNextStageException} stays the honest failure for a caller that
 * actually tries to move one.
 */
public final class TerminalStage {

    private TerminalStage() {
    }

    /**
     * @param stageCode the stage being asked about — the ticket's current one
     *                  for {@code availableActions}, the destination one for a
     *                  transition, which are deliberately not the same
     *                  question at the moment a ticket is being moved
     * @return {@code true} only when the ticket has a template, that template
     *         has stages, and {@code stageCode} is the last of them by
     *         {@code seq}
     */
    public static boolean is(WorkflowStageRepository stages, Ticket ticket, String stageCode) {
        Long templateId = ticket.getWorkflowTemplateId();
        if (templateId == null || stageCode == null) {
            return false;
        }
        List<WorkflowStage> ordered = stages.findByTemplateIdOrderBySeqAsc(templateId);
        return !ordered.isEmpty()
                && stageCode.equals(ordered.get(ordered.size() - 1).getStageCode());
    }
}
