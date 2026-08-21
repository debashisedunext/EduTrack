package com.edunext.edutrack.api.feature.masters.templates;

import com.edunext.edutrack.domain.workflow.WorkflowTemplate;
import com.edunext.edutrack.domain.workflow.WorkflowTemplateMapping;
import com.edunext.edutrack.domain.workflow.WorkflowTemplateMappingRepository;
import com.edunext.edutrack.domain.workflow.WorkflowTemplateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * B-041 · which workflow template a ticket gets — blueprint §4A.9.
 *
 * <p>Public because it is the half of this task that outlives the screen.
 * Everything else in this package serves S-13 tab 3; this answers the question
 * ticket creation asks, and it is deliberately reachable without going through a
 * controller.
 *
 * <h2>Nothing sets {@code tickets.workflow_template_id} today</h2>
 *
 * <p>Worth stating plainly, because it is surprising. The column has existed
 * since A-004, the foreign key since A-005, and the only code in the repository
 * that ever writes it is {@code SingleTicketFixture}. Every ticket created
 * through {@code POST /tickets} carries {@code null}, which is why
 * {@code RibbonAssembler} and {@code NoNextStageException} both have a documented
 * "ticket with no template" path and why C-013's stage filter is built from the
 * template list rather than from the ticket.
 *
 * <p><b>Wiring this into ticket creation is Stream C's, not this task's.</b>
 * {@code TicketService} is {@code feature/tickets/}, and CLAUDE.md asks for that
 * owner's sign-off rather than a quiet edit. So B-041 ships the table, the
 * ladder, and a route that returns the answer; the call site is C's to add. Until
 * they do, this resolver is read by S-13 tab 3 and by its tests, and every ticket
 * still gets no template — the same as before, and now with somewhere for the
 * answer to come from.
 *
 * <h2>The ladder</h2>
 *
 * <ol>
 *   <li>{@code (project, taskType)} — the exact pair</li>
 *   <li>{@code (project, null)} — this project, any task type</li>
 *   <li>{@code (null, taskType)} — this task type, any project</li>
 *   <li>{@code (null, null)} — an explicit catch-all rule, if somebody wrote
 *       one</li>
 *   <li>{@code workflow_templates.is_default}</li>
 *   <li>nothing</li>
 * </ol>
 *
 * <p><b>Rungs 2 and 3 are ordered and the order is a real decision.</b> Project
 * beats task type, because a project is the narrower population: an organisation
 * that runs one flow for everything on a particular engagement means that
 * regardless of what the task type is, while a task-type rule is a statement
 * about the kind of work that holds in the absence of anything more local. The
 * opposite precedence is defensible and would produce different tickets, which is
 * exactly why it is written down here rather than left to whichever {@code ORDER
 * BY} someone wrote first.
 *
 * <p><b>An inactive template still wins its rung.</b> Skipping it and falling
 * through to the next would silently re-route live traffic on the strength of a
 * flag flipped on another screen, which is the failure mode this whole package is
 * trying to make visible. Instead {@code TemplateService} refuses to deactivate a
 * template that rules point at, so the state cannot be reached from the API. If
 * it is reached anyway — by a migration, or by a hand-written {@code UPDATE} —
 * the resolution reports it as it is and the screen shows an inactive template
 * being routed to, which is the fact.
 */
@Service
public class TemplateResolver {

    private final WorkflowTemplateMappingRepository mappings;
    private final WorkflowTemplateRepository templates;

    TemplateResolver(WorkflowTemplateMappingRepository mappings,
                     WorkflowTemplateRepository templates) {
        this.mappings = mappings;
        this.templates = templates;
    }

    /**
     * The template id for a project × task type, or empty if nothing answers.
     *
     * <p>The narrow entry point, for a caller that wants the answer and not the
     * reasoning — which is what ticket creation will want. {@link #explain} is the
     * same computation with the rung attached.
     */
    @Transactional(readOnly = true)
    public Optional<Long> resolve(Long projectId, Integer taskTypeId) {
        return Optional.ofNullable(explain(projectId, taskTypeId).templateId());
    }

    /**
     * The template <em>and</em> which rung produced it.
     *
     * <p>One query for the rules and at most one for the default, rather than a
     * query per rung. Three sequential reads would not be three independent facts;
     * they are one decision, and a rule inserted between the first and the third
     * would let the resolution match no state the table was ever in.
     */
    @Transactional(readOnly = true)
    public TemplateDtos.TemplateResolution explain(Long projectId, Integer taskTypeId) {
        List<WorkflowTemplateMapping> candidates =
                mappings.findCandidates(projectId, taskTypeId);

        // findCandidates already orders by specificity, but rungs 2 and 3 tie at
        // one apiece and SQL cannot express "project beats task type" as part of
        // that expression without repeating the precedence rule in two places.
        // Re-sorting here is cheap — a handful of rows — and puts the whole rule
        // in one readable comparator.
        Optional<WorkflowTemplateMapping> winner = candidates.stream()
                .min(Comparator.comparingInt(TemplateResolver::rank));

        if (winner.isPresent()) {
            WorkflowTemplateMapping m = winner.get();
            String name = templates.findById(m.getTemplateId())
                    .map(WorkflowTemplate::getName)
                    .orElse(null);
            return new TemplateDtos.TemplateResolution(
                    m.getTemplateId(), name, rungOf(m), m.getId());
        }

        // Rung 5. More than one row may carry is_default — the schema permits it,
        // which is why WorkflowTemplateRepository returns a list here rather than
        // an Optional. TemplateService keeps it to one; this picks the lowest id
        // so that a database in the state the service forbids still resolves
        // deterministically rather than by whatever the optimiser reached first.
        return templates.findByIsDefaultTrueAndIsActiveTrue().stream()
                .min(Comparator.comparing(WorkflowTemplate::getId))
                .map(t -> new TemplateDtos.TemplateResolution(
                        t.getId(), t.getName(), TemplateDtos.TemplateResolution.DEFAULT, null))
                .orElseGet(() -> new TemplateDtos.TemplateResolution(
                        null, null, TemplateDtos.TemplateResolution.NONE, null));
    }

    /** Lower is better. Exact 0, project 1, task type 2, catch-all 3. */
    private static int rank(WorkflowTemplateMapping m) {
        if (m.getProjectId() != null && m.getTaskTypeId() != null) return 0;
        if (m.getProjectId() != null) return 1;
        if (m.getTaskTypeId() != null) return 2;
        return 3;
    }

    private static String rungOf(WorkflowTemplateMapping m) {
        return switch (rank(m)) {
            case 0 -> TemplateDtos.TemplateResolution.EXACT;
            case 1 -> TemplateDtos.TemplateResolution.PROJECT;
            case 2 -> TemplateDtos.TemplateResolution.TASK_TYPE;
            default -> TemplateDtos.TemplateResolution.ANY;
        };
    }
}
