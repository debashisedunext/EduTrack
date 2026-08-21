package com.edunext.edutrack.api.feature.masters.templates;

import com.edunext.edutrack.domain.masters.TaskTypeRepository;
import com.edunext.edutrack.domain.workflow.WorkflowStage;
import com.edunext.edutrack.domain.workflow.WorkflowStageRepository;
import com.edunext.edutrack.domain.workflow.WorkflowTemplate;
import com.edunext.edutrack.domain.workflow.WorkflowTemplateMapping;
import com.edunext.edutrack.domain.workflow.WorkflowTemplateMappingRepository;
import com.edunext.edutrack.domain.workflow.WorkflowTemplateRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * B-041 · S-13 tab 3 — the Workflow Template Master.
 *
 * <p>B-040 built tab 2 and left this tab a disabled button naming this task. What
 * it could not build was the half of §7.4 that has no table: <em>"named templates
 * built by picking stages, then mapped to project × task type"</em>. The mapping
 * table arrives with this service.
 *
 * <h2>The refusals</h2>
 *
 * <table>
 *   <tr><th>Rule</th><th>Where</th><th>Status</th></tr>
 *   <tr><td>{@code name} is unique</td>
 *       <td>{@link #create}, {@link #update}</td><td>409 {@code duplicate}</td></tr>
 *   <tr><td>A template with no live stage may not be the default</td>
 *       <td>{@link #applyDefault}</td><td>409 {@code empty-template}</td></tr>
 *   <tr><td>The default may not be cleared, only moved</td>
 *       <td>{@link #applyDefault}</td><td>409 {@code last-default}</td></tr>
 *   <tr><td>The default may not be deactivated</td>
 *       <td>{@link #applyActive}</td><td>409 {@code last-default}</td></tr>
 *   <tr><td>A template routing rules point at may not be deactivated</td>
 *       <td>{@link #applyActive}</td><td>409 {@code template-in-use}</td></tr>
 *   <tr><td>A template any ticket ever started on may not be deleted</td>
 *       <td>{@link #delete}</td><td>409 {@code template-in-use}</td></tr>
 *   <tr><td>A pair may be claimed by one template only</td>
 *       <td>{@link #replaceMappings}</td><td>409 {@code mapping-claimed}</td></tr>
 *   <tr><td>A rule must name a real project and a real task type</td>
 *       <td>{@link #replaceMappings}</td><td>400 {@code validation}</td></tr>
 * </table>
 *
 * <h2>Why "in use" means two different things here</h2>
 *
 * <p>Deactivation and deletion are refused by different counts, and folding them
 * into one rule would be wrong in both directions.
 *
 * <p><b>Deactivation is refused by {@code mappingCount}</b> — by what the template
 * is <em>for</em>, not by what it has done. A template three rules route to
 * cannot be switched off, because the next ticket on any of those pairs would
 * resolve to a template the master says is out of service. History is irrelevant
 * to that: a template with ten thousand closed tickets and no live rule may be
 * retired freely, and retiring it is exactly the right thing to do.
 *
 * <p><b>Deletion is refused by {@code ticketCount}</b> — by history, and history
 * alone. Deleting a template cascades its {@code workflow_stages} rows, and every
 * historical ribbon segment resolves its display name, icon and owner role
 * through those. This is B-042's argument one table up, with one difference worth
 * recording: {@code tickets.workflow_template_id} is a <em>real</em> foreign key,
 * so unlike a stage-code delete the database would refuse this on its own. The
 * service check exists so the refusal arrives as a sentence with a number in it,
 * and so the screen can decline to offer the button rather than discovering the
 * rule by pressing it.
 *
 * <h2>Exactly one default, and it can only be moved</h2>
 *
 * <p>{@code is_default} is the last rung of {@link TemplateResolver}'s ladder, so
 * two of them means a ticket's ribbon depends on row order and none of them means
 * an unmapped pair routes nowhere. The database asserts neither — it is a plain
 * {@code TINYINT} with an index — so it is held here: setting a new default
 * clears the old one in the same transaction, and clearing the current default
 * without naming a replacement is refused. That is B-039's *"at least one
 * on-create transition must survive"* rule, on a different table and for the same
 * reason: this is the only screen that could undo it.
 */
@Service
class TemplateService {

    private final WorkflowTemplateRepository templates;
    private final WorkflowTemplateMappingRepository mappings;
    private final WorkflowStageRepository stages;
    private final TaskTypeRepository taskTypes;
    private final TemplateUsageRepository usage;
    private final JdbcClient jdbc;

    TemplateService(WorkflowTemplateRepository templates,
                    WorkflowTemplateMappingRepository mappings,
                    WorkflowStageRepository stages,
                    TaskTypeRepository taskTypes,
                    TemplateUsageRepository usage,
                    JdbcClient jdbc) {
        this.templates = templates;
        this.mappings = mappings;
        this.stages = stages;
        this.taskTypes = taskTypes;
        this.usage = usage;
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    Optional<TemplateDtos.WorkflowTemplateDetail> get(long templateId) {
        return templates.findById(templateId).map(t -> view(t,
                usage.stageCounts(), usage.mappingCounts(), usage.ticketCounts()));
    }

    @Transactional(readOnly = true)
    Optional<List<TemplateDtos.TemplateMapping>> listMappings(long templateId) {
        if (!templates.existsById(templateId)) {
            return Optional.empty();
        }
        return Optional.of(usage.mappingsFor(templateId));
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /**
     * Create a template, optionally as a copy of an existing ribbon.
     *
     * <p>§7.4's "built by picking stages" and A-005's "versioned by copy, never
     * edited in place" are the same operation seen from two ends, and this is it.
     * A copy duplicates every stage of the source — deprecated ones included, and
     * that is deliberate: the copy is a new ribbon whose shape is the old one, and
     * silently dropping the retired segments would produce a template that no
     * longer matches the flow it was cloned from, differing in a way nobody asked
     * for and nothing records.
     *
     * <p>{@code seq} values are copied verbatim rather than renumbered.
     * {@code uq_workflow_stages_seq} is scoped to the template, so there is
     * nothing to collide with, and B-004's 10/20/30 spacing is the property that
     * lets tab 2 insert between two stages later.
     */
    @Transactional
    TemplateDtos.WorkflowTemplateDetail create(TemplateDtos.WorkflowTemplateWriteRequest req) {
        String name = req.name().trim();
        templates.findByName(name).ifPresent(existing -> {
            throw new DuplicateTemplateException(name);
        });

        WorkflowTemplate t = new WorkflowTemplate();
        t.setName(name);
        t.setDescription(blankToNull(req.description()));
        t.setActive(true);
        t.setDefault(false);
        WorkflowTemplate saved = templates.save(t);

        if (req.copyStagesFromTemplateId() != null) {
            copyStages(req.copyStagesFromTemplateId(), saved);
        }

        if (Boolean.TRUE.equals(req.isDefault())) {
            applyDefault(saved, true);
        }

        // Re-read the counts after the copy, or a template created with eight
        // stages would be reported as having none.
        return view(saved, usage.stageCounts(), usage.mappingCounts(), usage.ticketCounts());
    }

    @Transactional
    Optional<TemplateDtos.WorkflowTemplateDetail> update(long templateId,
                                               TemplateDtos.WorkflowTemplatePatchRequest req) {
        Optional<WorkflowTemplate> found = templates.findById(templateId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        WorkflowTemplate t = found.get();

        if (req.name() != null) {
            String name = req.name().trim();
            templates.findByName(name)
                    .filter(other -> !other.getId().equals(t.getId()))
                    .ifPresent(other -> {
                        throw new DuplicateTemplateException(name);
                    });
            t.setName(name);
        }
        if (req.description() != null) {
            t.setDescription(blankToNull(req.description()));
        }
        // Order matters. isActive is applied first so that a request switching a
        // template off *and* handing the default to another one in the same call
        // is refused — the deactivation guard sees the template still holding the
        // flag, which is the state that makes it refusable. The alternative order
        // would let one PATCH walk through a rule neither field could break alone.
        if (req.isActive() != null) {
            applyActive(t, req.isActive());
        }
        if (req.isDefault() != null) {
            applyDefault(t, req.isDefault());
        }

        templates.save(t);
        return Optional.of(view(t,
                usage.stageCounts(), usage.mappingCounts(), usage.ticketCounts()));
    }

    /**
     * Delete a template nothing has ever run on.
     *
     * <p>The complement of the rule rather than a general delete — B-042's shape,
     * and the surviving case is the same one: a template created by mistake and
     * caught the same afternoon. Everything else is 409 carrying the count
     * <b>and</b> {@code canDeactivate}, because an Admin told "no" with no
     * alternative concludes the row cannot be got rid of at all.
     *
     * <p>The delete cascades {@code workflow_stages} and
     * {@code workflow_template_mappings}, both by {@code ON DELETE CASCADE}. The
     * stages go because a stage has no existence apart from its template (A-005);
     * the mappings go because a rule routing to a template that no longer exists
     * is a rule that resolves to nothing.
     */
    @Transactional
    Optional<Boolean> delete(long templateId) {
        Optional<WorkflowTemplate> found = templates.findById(templateId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        WorkflowTemplate t = found.get();

        long tickets = usage.ticketCounts().getOrDefault(templateId, 0L);
        long rules = mappings.countByTemplateId(templateId);
        if (tickets > 0 || rules > 0) {
            throw new TemplateInUseException(t.getName(), tickets, rules);
        }
        if (t.isDefault()) {
            throw new LastDefaultException(t.getName());
        }

        templates.delete(t);
        return Optional.of(true);
    }

    /**
     * Replace one template's routing rules wholesale.
     *
     * <p>Matched on the pair rather than on the id, so an unchanged rule keeps its
     * row and its {@code created_at}. B-039's transition matrix upserts for the
     * same reason; the difference is that a mapping carries no authored fields of
     * its own, so what is preserved is provenance rather than data.
     *
     * <p><b>Rules absent from the request are deleted, not deactivated</b>, which
     * is the opposite of what B-017, B-018 and B-039 all chose for their tables.
     * The distinction is that those rows record something somebody stated — a
     * transition's {@code requiresReason}, a member's allocation — so a cleared
     * one is worth telling apart from one never configured. A mapping is a pure
     * (pair → template) edge with no payload; a deactivated one would be a routing
     * rule that does not route, which is indistinguishable from its own absence
     * except in the ways it could go wrong.
     */
    @Transactional
    Optional<List<TemplateDtos.TemplateMapping>> replaceMappings(
            long templateId, TemplateDtos.TemplateMappingReplaceRequest req) {

        Optional<WorkflowTemplate> found = templates.findById(templateId);
        if (found.isEmpty()) {
            return Optional.empty();
        }

        List<TemplateDtos.TemplateMappingEntry> wanted = dedupe(req.mappings());
        validateReferences(wanted);
        validateNotClaimedElsewhere(templateId, wanted);

        List<WorkflowTemplateMapping> existing =
                mappings.findByTemplateIdOrderByIdAsc(templateId);

        Set<String> keep = new LinkedHashSet<>();
        wanted.forEach(e -> keep.add(key(e.projectId(), e.taskTypeId())));

        List<WorkflowTemplateMapping> doomed = existing.stream()
                .filter(m -> !keep.contains(key(m.getProjectId(), m.getTaskTypeId())))
                .toList();
        mappings.deleteAll(doomed);

        // Flush the deletes before the inserts, or a pair moved from one rule to
        // another inside a single replace collides with the row this same
        // statement is about to remove. uq_workflow_template_mappings_pair is
        // enforced per row by InnoDB — there are no deferred constraints in MySQL
        // 8.4 — which is the same ordering hazard B-040's two-pass reorder exists
        // for, arriving here as delete-then-insert rather than park-then-write.
        mappings.flush();

        Set<String> already = new LinkedHashSet<>();
        existing.stream()
                .filter(m -> keep.contains(key(m.getProjectId(), m.getTaskTypeId())))
                .forEach(m -> already.add(key(m.getProjectId(), m.getTaskTypeId())));

        List<WorkflowTemplateMapping> fresh = new ArrayList<>();
        for (TemplateDtos.TemplateMappingEntry e : wanted) {
            if (already.contains(key(e.projectId(), e.taskTypeId()))) {
                continue;
            }
            WorkflowTemplateMapping m = new WorkflowTemplateMapping();
            m.setTemplateId(templateId);
            m.setProjectId(e.projectId());
            m.setTaskTypeId(e.taskTypeId());
            fresh.add(m);
        }
        mappings.saveAll(fresh);
        mappings.flush();

        return Optional.of(usage.mappingsFor(templateId));
    }

    // ------------------------------------------------------------------
    // Rules
    // ------------------------------------------------------------------

    /**
     * Move the default flag, or refuse to drop it.
     *
     * <p>Clearing the current default is refused rather than allowed-with-a-
     * warning, because the state it produces is one no screen would show: every
     * unmapped project × task type resolving to nothing, discovered on the next
     * ticket somebody raises. Moving it is a single call — set the flag on the new
     * template, and the old one is cleared in the same transaction.
     */
    private void applyDefault(WorkflowTemplate t, boolean wanted) {
        if (!wanted) {
            if (t.isDefault()) {
                throw new LastDefaultException(t.getName());
            }
            return;
        }
        if (t.isDefault()) {
            return;
        }
        if (!t.isActive()) {
            throw new InactiveDefaultException(t.getName());
        }
        long live = stages.findByTemplateIdOrderBySeqAsc(t.getId()).stream()
                .filter(s -> !s.isDeprecated())
                .count();
        if (live == 0) {
            throw new EmptyTemplateException(t.getName());
        }

        // One statement rather than loading every template to clear one flag. The
        // read-modify-write version is also a lost-update waiting to happen: two
        // Admins promoting two different templates would each clear what the other
        // had just set, and the table would end with two defaults or none.
        jdbc.sql("UPDATE workflow_templates SET is_default = 0 WHERE is_default = 1 AND id <> :id")
                .param("id", t.getId())
                .update();
        t.setDefault(true);
    }

    /**
     * Switch a template on or off.
     *
     * <p>Off is the interesting direction. Refused while any routing rule names
     * the template, and refused outright on the default — in both cases because
     * turning it off would leave a pair resolving to a template that is not in
     * service, with nothing on any screen to say so until a ticket lands on it.
     * The remedy travels with the refusal: re-point those rules first.
     */
    private void applyActive(WorkflowTemplate t, boolean wanted) {
        if (wanted == t.isActive()) {
            return;
        }
        if (!wanted) {
            if (t.isDefault()) {
                throw new LastDefaultException(t.getName());
            }
            long rules = mappings.countByTemplateId(t.getId());
            if (rules > 0) {
                throw new TemplateInUseException(t.getName(),
                        usage.ticketCounts().getOrDefault(t.getId(), 0L), rules);
            }
        }
        t.setActive(wanted);
    }

    /**
     * Every project id and task type id in the request must exist.
     *
     * <p>The foreign keys would refuse an unknown id anyway, as an
     * {@code UncategorizedSQLException} arriving from the flush with no field
     * name on it. Checking here is what turns that into a 400 naming
     * {@code mappings[2].projectId}, which is the difference between a form that
     * highlights a row and one that shows a stack trace.
     *
     * <p><b>Projects are not filtered to active ones.</b> A rule may name a
     * project that is on hold or closed — closing a project does not withdraw the
     * routing decision made for it, and a reopened project whose rule had been
     * silently dropped would route to the default with nothing recording the
     * change.
     */
    private void validateReferences(List<TemplateDtos.TemplateMappingEntry> wanted) {
        Set<Long> projectIds = new LinkedHashSet<>();
        Set<Integer> taskTypeIds = new LinkedHashSet<>();
        wanted.forEach(e -> {
            if (e.projectId() != null) projectIds.add(e.projectId());
            if (e.taskTypeId() != null) taskTypeIds.add(e.taskTypeId());
        });

        if (!projectIds.isEmpty()) {
            Set<Long> live = new LinkedHashSet<>(jdbc
                    .sql("SELECT id FROM projects WHERE id IN (:ids)")
                    .param("ids", projectIds)
                    .query(Long.class)
                    .list());
            projectIds.stream().filter(id -> !live.contains(id)).findFirst()
                    .ifPresent(id -> {
                        throw new UnknownReferenceException("projectId", id);
                    });
        }
        taskTypeIds.stream()
                .filter(id -> !taskTypes.existsById(id))
                .findFirst()
                .ifPresent(id -> {
                    throw new UnknownReferenceException("taskTypeId", id);
                });
    }

    /**
     * A pair belongs to one template, and the refusal names the other one.
     *
     * <p>The unique key already guarantees it. What the key cannot do is say
     * <em>which</em> template holds the pair — it arrives as a duplicate-key
     * violation with an index name in it — and that is the only piece of
     * information the Admin needs, because the remedy is on the other template's
     * screen.
     */
    private void validateNotClaimedElsewhere(long templateId,
                                             List<TemplateDtos.TemplateMappingEntry> wanted) {
        for (TemplateDtos.TemplateMappingEntry e : wanted) {
            mappings.findByPair(e.projectId(), e.taskTypeId())
                    .filter(m -> !m.getTemplateId().equals(templateId))
                    .ifPresent(m -> {
                        String other = templates.findById(m.getTemplateId())
                                .map(WorkflowTemplate::getName)
                                .orElse("another template");
                        throw new MappingClaimedException(
                                other, m.getTemplateId(), e.projectId(), e.taskTypeId());
                    });
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void copyStages(long sourceId, WorkflowTemplate target) {
        if (!templates.existsById(sourceId)) {
            throw new UnknownReferenceException("copyStagesFromTemplateId", sourceId);
        }
        List<WorkflowStage> source = stages.findByTemplateIdOrderBySeqAsc(sourceId);
        List<WorkflowStage> copies = new ArrayList<>();
        for (WorkflowStage s : source) {
            WorkflowStage c = new WorkflowStage();
            // The association, not an id column. WorkflowStage maps template_id
            // through a @ManyToOne, so there is no setTemplateId to call — and the
            // target must be the saved entity rather than a fresh one carrying the
            // same id, or Hibernate has two instances of one row in the session.
            c.setTemplate(target);
            c.setSeq(s.getSeq());
            c.setStageCode(s.getStageCode());
            c.setDisplayName(s.getDisplayName());
            c.setOwnerRole(s.getOwnerRole());
            c.setSlaHours(s.getSlaHours());
            c.setOptional(s.isOptional());
            // A fresh list rather than the same reference. canReturnTo is a
            // mutable List behind a JSON converter, and sharing it would make an
            // edit to one template's stage silently rewrite the other's.
            c.setCanReturnTo(s.getCanReturnTo() == null
                    ? null : new ArrayList<>(s.getCanReturnTo()));
            c.setIcon(s.getIcon());
            c.setDeprecated(s.isDeprecated());
            c.setDeprecatedAt(s.getDeprecatedAt());
            copies.add(c);
        }
        stages.saveAll(copies);
    }

    /**
     * Last one wins on a repeated pair.
     *
     * <p>A request naming the same pair twice is a client bug rather than an Admin
     * decision — the screen cannot produce one, since a pair is a row. Refusing it
     * would be defensible; collapsing it is chosen because the unique key would
     * otherwise turn a harmless duplicate into a 500 from the flush, and because
     * both entries name the same template and therefore ask for the same thing.
     */
    private static List<TemplateDtos.TemplateMappingEntry> dedupe(List<TemplateDtos.TemplateMappingEntry> in) {
        Map<String, TemplateDtos.TemplateMappingEntry> byKey = new java.util.LinkedHashMap<>();
        in.forEach(e -> byKey.put(key(e.projectId(), e.taskTypeId()), e));
        return List.copyOf(byKey.values());
    }

    private static String key(Long projectId, Integer taskTypeId) {
        return (projectId == null ? "*" : projectId) + ":"
                + (taskTypeId == null ? "*" : taskTypeId);
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private TemplateDtos.WorkflowTemplateDetail view(WorkflowTemplate t,
                                           Map<Long, Integer> stageCounts,
                                           Map<Long, Long> mappingCounts,
                                           Map<Long, Long> ticketCounts) {
        long id = t.getId();
        int stageCount = stageCounts.getOrDefault(id, 0);
        long mappingCount = mappingCounts.getOrDefault(id, 0L);
        long ticketCount = ticketCounts.getOrDefault(id, 0L);
        return new TemplateDtos.WorkflowTemplateDetail(
                id, t.getName(), t.getDescription(), t.isDefault(), t.isActive(),
                stageCount, mappingCount, ticketCount,
                ticketCount == 0 && mappingCount == 0 && !t.isDefault(),
                t.isActive() && mappingCount == 0 && !t.isDefault(),
                t.getCreatedAt(), t.getUpdatedAt());
    }

    // ------------------------------------------------------------------
    // Refusals
    // ------------------------------------------------------------------

    static class DuplicateTemplateException extends RuntimeException {
        DuplicateTemplateException(String name) {
            super("A workflow template named \"" + name + "\" already exists.");
        }
    }

    /**
     * One exception for two counts, and the handler decides which sentence to
     * write from which one is above zero.
     *
     * <p>Two exception types was the first draft and it split a refusal the screen
     * treats as one: both mean "this template is in service", both carry the same
     * remedy shape, and the response differs only in the number quoted. B-042 made
     * the opposite call for {@code return-target-direction} — one type covering a
     * reorder and a retire — for the same reason.
     */
    static class TemplateInUseException extends RuntimeException {
        private final long ticketCount;
        private final long mappingCount;

        TemplateInUseException(String name, long ticketCount, long mappingCount) {
            super("\"" + name + "\" is in use — "
                    + ticketCount + " ticket(s) started on it and "
                    + mappingCount + " routing rule(s) point at it.");
            this.ticketCount = ticketCount;
            this.mappingCount = mappingCount;
        }

        long ticketCount() {
            return ticketCount;
        }

        long mappingCount() {
            return mappingCount;
        }
    }

    static class LastDefaultException extends RuntimeException {
        LastDefaultException(String name) {
            super("\"" + name + "\" is the default template. Make another template "
                    + "the default first — every project and task type with no rule "
                    + "of its own routes through it.");
        }
    }

    static class InactiveDefaultException extends RuntimeException {
        InactiveDefaultException(String name) {
            super("\"" + name + "\" is inactive and cannot be made the default. "
                    + "Reactivate it first.");
        }
    }

    static class EmptyTemplateException extends RuntimeException {
        EmptyTemplateException(String name) {
            super("\"" + name + "\" has no live stage, so it routes no ticket "
                    + "anywhere. Add a stage before making it the default.");
        }
    }

    static class MappingClaimedException extends RuntimeException {
        private final String templateName;
        private final long templateId;
        private final Long projectId;
        private final Integer taskTypeId;

        MappingClaimedException(String templateName, long templateId,
                                Long projectId, Integer taskTypeId) {
            super("That project and task type already route to \"" + templateName
                    + "\". A pair resolves to one template.");
            this.templateName = templateName;
            this.templateId = templateId;
            this.projectId = projectId;
            this.taskTypeId = taskTypeId;
        }

        String templateName() {
            return templateName;
        }

        long templateId() {
            return templateId;
        }

        Long projectId() {
            return projectId;
        }

        Integer taskTypeId() {
            return taskTypeId;
        }
    }

    static class UnknownReferenceException extends RuntimeException {
        private final String field;

        UnknownReferenceException(String field, Number id) {
            super("No such " + field + ": " + id + ".");
            this.field = field;
        }

        String field() {
            return field;
        }
    }
}
