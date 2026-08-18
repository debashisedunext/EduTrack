package com.edunext.edutrack.api.feature.masters.stages;

import com.edunext.edutrack.domain.identity.RoleRepository;
import com.edunext.edutrack.domain.workflow.WorkflowStage;
import com.edunext.edutrack.domain.workflow.WorkflowStageRepository;
import com.edunext.edutrack.domain.workflow.WorkflowTemplate;
import com.edunext.edutrack.domain.workflow.WorkflowTemplateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * B-040 · S-13 tab 2 — the Stage Master, the ribbon's own definition.
 *
 * <p>The data layer was already here: A-005 created {@code workflow_stages},
 * B-004 seeded three templates with 8 + 5 + 5 stages, and
 * {@code WorkflowStageRepository} has been read by Stream C's reopen and Stream
 * D's stage-SLA scanner since. <b>Nothing has ever written to it.</b> Everything
 * an Admin could change about a ribbon lived in a migration until this task.
 *
 * <h2>A stage belongs to a template, and §7.4 reads as though it does not</h2>
 *
 * <p>The blueprint describes tab 2 as one flat list of "the ribbon stages" and
 * tab 3 as templates "built by picking stages" — which implies a catalogue of
 * stage definitions that templates draw from. There is no such table.
 * {@code workflow_stages.template_id} is {@code NOT NULL} behind a cascading
 * foreign key, so a stage has no existence apart from its template, and
 * {@code DEV} on Standard Dev Flow and {@code DEV} on Support Fast-Track are two
 * independent rows that happen to share a code.
 *
 * <p>CLAUDE.md settles which way the disagreement falls — PLAN.md wins on
 * implementation, and A-005 is what it produced. So tab 2 carries a template
 * selector and every route here is scoped beneath one. Recorded rather than
 * quietly reconciled, because the alternative reading would have meant a new
 * table, a migration touching the ribbon, and two sources of truth for what a
 * stage is.
 *
 * <h2>The four refusals</h2>
 *
 * <table>
 *   <tr><th>Rule</th><th>Where</th><th>Status</th></tr>
 *   <tr><td>{@code stageCode} is unique within the template</td>
 *       <td>{@link #create}, {@link #update}</td><td>409 {@code duplicate}</td></tr>
 *   <tr><td>{@code stageCode} is frozen once the stage is in use</td>
 *       <td>{@link #update}</td><td>409 {@code immutable-field}</td></tr>
 *   <tr><td>{@code ownerRole} must match a live {@code roles.code}</td>
 *       <td>{@link #resolveOwnerRole}</td><td>400 {@code validation}</td></tr>
 *   <tr><td>{@code canReturnTo} must be backward, in-template, not self</td>
 *       <td>{@link #validateReturnTargets}</td><td>400/409</td></tr>
 * </table>
 *
 * <h2>Why renaming a code in use is the worst edit in this package</h2>
 *
 * <p>{@code ticket_stage_transitions.to_stage} stores the code as plain text with
 * no foreign key, and {@code StageSlaRepository} joins
 * {@code ws.stage_code = tr.to_stage}. A rename therefore breaks two things at
 * once and <b>neither of them fails</b>: every historical ribbon segment stops
 * resolving to a stage definition, and the §4A.7 "stuck in stage" scan stops
 * matching those rows and silently never alerts on them again. A silent gap in an
 * escalation scanner is not a bug anyone reports — it is an alert that does not
 * arrive. So the code is frozen the moment either count is above zero, and
 * {@code isCodeEditable} travels on the view so the form does not have to restate
 * the rule.
 *
 * <p>On a stage nothing has entered, a rename is provably safe: there is no row
 * anywhere holding the old code. That is the only case it is allowed in, and it
 * is the case that matters — a typo caught the same afternoon.
 *
 * <h2>What this service deliberately does not do</h2>
 *
 * <p><b>There is no delete.</b> §7.4 is explicit: "Stages used by live tickets can
 * only be deprecated, never deleted — otherwise historical ribbons would break."
 * The deprecation flag and its guard are <b>B-042</b>, one task away, and this
 * package ships no removal at all rather than a delete B-042 would then have to
 * take away. The narrower version — "delete only where both counts are zero" —
 * was drawn and rejected: it is safe in itself, and it is also a delete route on
 * the ribbon's definition table that would exist before the rule protecting that
 * table did. B-042 adds removal in the shape §7.4 asks for.
 *
 * <p><b>It does not refuse to edit a template that has live tickets.</b> A-005's
 * own header says a template is "versioned by copy, never edited in place",
 * because a ticket in flight must keep rendering the ribbon it started on. That
 * is kept by <b>B-043</b>'s designer cloning a template — there is no version
 * column to clone into yet, and a tab that refused every template with an open
 * ticket would refuse all three seeded ones and be unusable on the day it ships.
 * What this service does instead is put {@code openTicketCount} on every stage, so
 * the screen states the number before the drag rather than after.
 */
@Service
public class StageService {

    /** B-004's spacing. Wide enough to drop a stage between two without renumbering. */
    private static final short SEQ_STEP = 10;

    private final WorkflowStageRepository stages;
    private final WorkflowTemplateRepository templates;
    private final RoleRepository roles;
    private final StageUsageRepository usage;

    StageService(WorkflowStageRepository stages,
                 WorkflowTemplateRepository templates,
                 RoleRepository roles,
                 StageUsageRepository usage) {
        this.stages = stages;
        this.templates = templates;
        this.roles = roles;
        this.usage = usage;
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /**
     * Every template, for tab 2's selector.
     *
     * <p>Inactive templates included, and that is not the choice the status and
     * priority masters made. Those default to active-only because a retired row
     * handed to a <em>filter</em> offers a value matching no ticket anyone can
     * still raise. A template is not a filter value — it is the thing every
     * historical ticket points at through {@code tickets.workflow_template_id},
     * and hiding a deactivated one would hide the stages of every ticket that ever
     * ran on it. The selector labels them instead.
     */
    @Transactional(readOnly = true)
    public List<StageDtos.WorkflowTemplateView> templates() {
        Map<Long, Integer> counts = usage.stageCounts();
        return templates.findAll().stream()
                .sorted(Comparator.comparing(WorkflowTemplate::getName))
                .map(t -> new StageDtos.WorkflowTemplateView(
                        t.getId(), t.getName(), t.getDescription(),
                        t.isDefault(), t.isActive(),
                        counts.getOrDefault(t.getId(), 0),
                        inlineStages(t.getId())))
                .toList();
    }

    /**
     * The inline vocabulary S-25's stage filter reads — see
     * {@link StageDtos.InlineStageView}.
     *
     * <p>No usage counts, so no {@code StageUsageRepository} round trip per
     * template. That is the point of the narrower shape as much as the response
     * size is: the counts are two grouped statements each, and a ticket list
     * asking for them on every render would pay for four fields it never shows.
     */
    private List<StageDtos.InlineStageView> inlineStages(long templateId) {
        List<WorkflowStage> ordered = stages.findByTemplateIdOrderBySeqAsc(templateId);
        List<StageDtos.InlineStageView> out = new ArrayList<>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            WorkflowStage stage = ordered.get(i);
            out.add(new StageDtos.InlineStageView(
                    stage.getStageCode(), stage.getDisplayName(), i + 1,
                    stage.getOwnerRole(), stage.getIcon(), stage.getSlaHours(),
                    stage.isOptional(),
                    stage.getCanReturnTo() == null ? List.of() : List.copyOf(stage.getCanReturnTo()),
                    // B-042 adds the column. Constant false is what this field has
                    // been since D-001, not new drift introduced here.
                    false));
        }
        return out;
    }

    /** One template's stages, left to right. Empty when the template does not exist. */
    @Transactional(readOnly = true)
    public Optional<List<StageDtos.StageView>> list(long templateId) {
        if (!templates.existsById(templateId)) {
            return Optional.empty();
        }
        return Optional.of(view(stages.findByTemplateIdOrderBySeqAsc(templateId), templateId));
    }

    /**
     * One stage, for the {@code ETag} the {@code PATCH} preconditions on.
     *
     * <p>Reads the whole template rather than the one row, because
     * {@code position} is a fact about the row's neighbours and cannot be derived
     * from the row. Five rows to answer a single-row read is the right trade
     * against serving a position the grid disagrees with.
     */
    @Transactional(readOnly = true)
    public Optional<StageDtos.StageView> find(long templateId, long stageId) {
        return list(templateId).flatMap(all -> all.stream()
                .filter(s -> s.id() == stageId)
                .findFirst());
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /**
     * Append a stage to the end of the ribbon.
     *
     * <p>{@code seq} is {@code max + 10}, never a caller's value — see
     * {@link StageDtos}. Move it afterwards with {@link #reorder}, which is what
     * the S-13 form does when an Admin drops the new row somewhere other than last.
     *
     * <p>Every existing stage is a legal return target for a stage appended last,
     * so the backward rule can only fail here on a code that does not exist or on
     * a duplicate — never on direction.
     */
    @Transactional
    public StageDtos.StageView create(long templateId, StageDtos.StageWrite write) {
        WorkflowTemplate template = templates.findById(templateId)
                .orElseThrow(() -> new TemplateNotFoundException(templateId));

        List<WorkflowStage> existing = stages.findByTemplateIdOrderBySeqAsc(templateId);
        String code = write.stageCode().trim().toUpperCase(Locale.ROOT);

        if (existing.stream().anyMatch(s -> s.getStageCode().equalsIgnoreCase(code))) {
            throw new DuplicateStageException("stageCode",
                    "%s is already a stage on %s. A code is unique within its template."
                            .formatted(code, template.getName()));
        }

        WorkflowStage stage = new WorkflowStage();
        stage.setTemplate(template);
        stage.setStageCode(code);
        stage.setDisplayName(write.displayName().trim());
        stage.setOwnerRole(resolveOwnerRole(write.ownerRole()));
        stage.setSlaHours(write.slaHours());
        stage.setOptional(Boolean.TRUE.equals(write.isOptional()));
        stage.setIcon(blankToNull(write.icon()));
        stage.setSeq(nextSeq(existing));

        List<WorkflowStage> after = new ArrayList<>(existing);
        after.add(stage);
        stage.setCanReturnTo(validateReturnTargets(write.canReturnTo(), stage, after));

        WorkflowStage saved = stages.save(stage);
        return view(saved, after, templateId);
    }

    /**
     * A partial edit. An omitted field keeps its stored value.
     *
     * <p><b>{@code stageCode} is accepted only so that changing it can be refused
     * once the stage is in use.</b> Sending the same code back is a no-op, which
     * is what a form that round-trips the whole record does; sending a different
     * one is a rename, and a rename is checked against both usage counts before
     * anything is written. See this class's header for why that check is not
     * ceremony.
     *
     * <p>{@code seq} is absent from the patch entirely — order is {@link #reorder}
     * and only {@link #reorder}, because one row's position cannot change without
     * deciding what happens to the rows it displaces.
     */
    @Transactional
    public StageDtos.StageView update(long templateId, long stageId, StageDtos.StagePatch patch) {
        List<WorkflowStage> siblings = stages.findByTemplateIdOrderBySeqAsc(templateId);
        WorkflowStage stage = siblings.stream()
                .filter(s -> stageId == s.getId())
                .findFirst()
                .orElseThrow(() -> new StageNotFoundException(templateId, stageId));

        if (patch.stageCode() != null) {
            String code = patch.stageCode().trim().toUpperCase(Locale.ROOT);
            if (!code.equals(stage.getStageCode())) {
                guardRename(templateId, stage, code, siblings);
                stage.setStageCode(code);
            }
        }
        if (patch.displayName() != null) {
            stage.setDisplayName(patch.displayName().trim());
        }
        if (patch.ownerRole() != null) {
            stage.setOwnerRole(resolveOwnerRole(patch.ownerRole()));
        }
        if (patch.slaHours() != null) {
            stage.setSlaHours(patch.slaHours());
        }
        if (patch.isOptional() != null) {
            stage.setOptional(patch.isOptional());
        }
        if (patch.icon() != null) {
            stage.setIcon(blankToNull(patch.icon()));
        }
        if (patch.canReturnTo() != null) {
            stage.setCanReturnTo(validateReturnTargets(patch.canReturnTo(), stage, siblings));
        }

        WorkflowStage saved = stages.save(stage);
        return view(saved, siblings, templateId);
    }

    /**
     * §7.4's "drag to reorder", as one whole-set replace.
     *
     * <h2>Two passes, and the reason is MySQL's</h2>
     *
     * <p>{@code uq_workflow_stages_seq (template_id, seq)} is a unique key, and
     * InnoDB enforces it <b>per row</b> rather than at statement end — there are
     * no deferred constraints in MySQL 8.4. So writing the final values straight
     * out collides the instant two stages swap: setting the stage at 20 to 10
     * fails while 10 is still occupied, and the transaction rolls back on an
     * operation the Admin experiences as dragging one row up. The first pass parks
     * every row above the occupied range, the second writes 10, 20, 30 … and both
     * are in one transaction so no other reader ever sees the parking values.
     *
     * <p>The offset is computed from the current maximum rather than being a
     * constant, so a template already sitting at unusual values cannot collide
     * with the parking range. {@code seq} is a {@code SMALLINT}, and a template
     * with enough stages to overflow it from a legal starting point is not one the
     * ribbon could render.
     *
     * <h2>The refusal that is easy to miss</h2>
     *
     * <p>A return target is a <em>backward</em> target — §4A.1's loop-back table
     * is entirely backward moves, and a forward "return" is an ordinary advance
     * with a reason attached. Reordering can therefore invalidate a
     * {@code canReturnTo} that was correct when it was authored: drag {@code DEV}
     * past {@code QA} and {@code QA → DEV} now points forwards. Saving it would
     * leave the ribbon with an arrow the transition service cannot honour and
     * nothing to indicate when it stopped being true, so the reorder is refused
     * with every offending pair named. Clear the target first, or drag the other
     * row.
     */
    @Transactional
    public List<StageDtos.StageView> reorder(long templateId, List<Long> stageIds) {
        if (!templates.existsById(templateId)) {
            throw new TemplateNotFoundException(templateId);
        }
        List<WorkflowStage> current = stages.findByTemplateIdOrderBySeqAsc(templateId);

        Set<Long> requested = new LinkedHashSet<>(stageIds);
        if (requested.size() != stageIds.size()) {
            throw new StageValidationException("stageIds",
                    "The same stage is listed more than once. Send every stage exactly once.");
        }
        Set<Long> known = current.stream().map(WorkflowStage::getId).collect(Collectors.toSet());
        if (!requested.equals(known)) {
            throw new StageValidationException("stageIds",
                    ("Send every stage of this template exactly once — %d expected, %d given. "
                            + "Moving one stage changes the position of every stage after it, "
                            + "so a partial list would leave the order ambiguous.")
                            .formatted(known.size(), requested.size()));
        }

        Map<Long, WorkflowStage> byId = current.stream()
                .collect(Collectors.toMap(WorkflowStage::getId, Function.identity()));
        List<WorkflowStage> ordered = stageIds.stream().map(byId::get).toList();

        guardReturnTargetsStayBackward(ordered);

        // Pass 1 — park above the occupied range so no intermediate write collides
        // with a row that has not moved yet.
        short parking = (short) (current.stream()
                .mapToInt(WorkflowStage::getSeq)
                .max().orElse(0) + SEQ_STEP);
        for (int i = 0; i < ordered.size(); i++) {
            ordered.get(i).setSeq((short) (parking + (i + 1) * SEQ_STEP));
        }
        stages.saveAllAndFlush(ordered);

        // Pass 2 — the values the ribbon is actually read in.
        for (int i = 0; i < ordered.size(); i++) {
            ordered.get(i).setSeq((short) ((i + 1) * SEQ_STEP));
        }
        stages.saveAllAndFlush(ordered);

        return view(ordered, templateId);
    }

    // ------------------------------------------------------------------
    // Rules
    // ------------------------------------------------------------------

    /**
     * A rename is refused the moment anything holds the old code.
     *
     * <p>Both counts are consulted rather than one. {@code transitionCount} is the
     * history a rename orphans; {@code openTicketCount} is the set of tickets whose
     * {@code current_stage} would stop resolving immediately. Neither is a superset
     * of the other — a ticket can sit in a stage on its very first hop, and a stage
     * every ticket has left still has a journey to render.
     */
    private void guardRename(long templateId, WorkflowStage stage, String code,
                             List<WorkflowStage> siblings) {

        if (siblings.stream().anyMatch(s -> !s.getId().equals(stage.getId())
                && s.getStageCode().equalsIgnoreCase(code))) {
            throw new DuplicateStageException("stageCode",
                    "%s is already a stage on this template.".formatted(code));
        }

        StageUsageRepository.Counts counts = usage.forTemplate(templateId);
        long transitions = counts.transitionsFor(stage.getStageCode());
        long open = counts.openTicketsFor(stage.getStageCode());
        if (transitions > 0 || open > 0) {
            throw new ImmutableStageCodeException(stage.getStageCode(), transitions, open);
        }
    }

    /**
     * {@code owner_role} carries no foreign key — A-005 made it a plain
     * {@code VARCHAR(20)} — so a typo here would not fail loudly. It would leave a
     * segment of the ribbon that no role on earth matches at handoff time, and the
     * ticket sitting in it advanceable only by PM or Admin override. Checked
     * against a live role, and V20260807_1030 is why {@code SUPPORT} and not
     * {@code SUPPORT_DESK}.
     */
    private String resolveOwnerRole(String ownerRole) {
        String code = ownerRole.trim().toUpperCase(Locale.ROOT);
        return roles.findByCode(code)
                .filter(r -> r.isActive())
                .map(r -> r.getCode())
                .orElseThrow(() -> new StageValidationException("ownerRole",
                        ("%s is not an active role. owner_role has no foreign key, so a code "
                                + "nothing matches would leave this stage advanceable only by "
                                + "PM or Admin override.").formatted(code)));
    }

    /**
     * Return targets: in this template, not this stage, and earlier in the order.
     *
     * <p>The third rule is the one worth stating, because it is the column's
     * definition rather than an extra restriction — A-005 annotates
     * {@code can_return_to} as "allowed backward targets" and §4A.1's loop-back
     * table contains nothing else. A forward entry would not be a return; it would
     * be an ordinary advance that had smuggled itself onto the whitelist.
     *
     * <p>Duplicates are dropped rather than refused. A list naming {@code DEV}
     * twice means exactly what naming it once means, and there is no way for the
     * caller to have intended anything else — unlike a duplicate in
     * {@code stageIds}, which changes the length of the sequence and therefore
     * cannot be reconciled.
     */
    private List<String> validateReturnTargets(List<String> requested,
                                               WorkflowStage stage,
                                               List<WorkflowStage> siblings) {
        if (requested == null || requested.isEmpty()) {
            return List.of();
        }
        Map<String, WorkflowStage> byCode = siblings.stream()
                .collect(Collectors.toMap(
                        s -> s.getStageCode().toUpperCase(Locale.ROOT),
                        Function.identity(), (a, b) -> a));

        // Normalise *before* de-duplicating. ["DEV", "dev"] is one target written
        // twice, and a LinkedHashSet over the raw strings would keep both and
        // store the same code twice on the row.
        Set<String> codes = new LinkedHashSet<>();
        for (String raw : requested) {
            codes.add(raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT));
        }

        List<String> resolved = new ArrayList<>();
        for (String code : codes) {
            if (code.equals(stage.getStageCode().toUpperCase(Locale.ROOT))) {
                throw new StageValidationException("canReturnTo",
                        "A stage cannot return to itself.");
            }
            WorkflowStage target = byCode.get(code);
            if (target == null) {
                throw new StageValidationException("canReturnTo",
                        ("%s is not a stage on this template. A return target is a stage code, "
                                + "and it has to be one of this ribbon's own.").formatted(code));
            }
            if (target.getSeq() >= stage.getSeq()) {
                throw new StageValidationException("canReturnTo",
                        ("%s comes after %s in the ribbon. A return target is a backward target "
                                + "— moving forward is an ordinary handoff, not a return.")
                                .formatted(code, stage.getStageCode()));
            }
            resolved.add(target.getStageCode());
        }
        return resolved;
    }

    /**
     * The same backward rule, checked from the reorder's side against the order the
     * caller is proposing rather than the one in the database.
     */
    private void guardReturnTargetsStayBackward(List<WorkflowStage> ordered) {
        Map<String, Integer> positions = new java.util.HashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            positions.put(ordered.get(i).getStageCode().toUpperCase(Locale.ROOT), i);
        }
        List<String> broken = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            WorkflowStage s = ordered.get(i);
            for (String target : s.getCanReturnTo() == null ? List.<String>of() : s.getCanReturnTo()) {
                Integer at = positions.get(target.toUpperCase(Locale.ROOT));
                if (at != null && at >= i) {
                    broken.add("%s → %s".formatted(s.getStageCode(), target));
                }
            }
        }
        if (!broken.isEmpty()) {
            throw new ReturnTargetDirectionException(broken);
        }
    }

    // ------------------------------------------------------------------
    // Mapping
    // ------------------------------------------------------------------

    private List<StageDtos.StageView> view(List<WorkflowStage> ordered, long templateId) {
        StageUsageRepository.Counts counts = usage.forTemplate(templateId);
        List<StageDtos.StageView> out = new ArrayList<>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            out.add(view(ordered.get(i), i + 1, templateId, counts));
        }
        return out;
    }

    private StageDtos.StageView view(WorkflowStage stage, List<WorkflowStage> siblings,
                                     long templateId) {
        List<WorkflowStage> ordered = siblings.stream()
                .sorted(Comparator.comparingInt(WorkflowStage::getSeq))
                .toList();
        int position = 1;
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).getStageCode().equals(stage.getStageCode())) {
                position = i + 1;
            }
        }
        return view(stage, position, templateId, usage.forTemplate(templateId));
    }

    private StageDtos.StageView view(WorkflowStage stage, int position, long templateId,
                                     StageUsageRepository.Counts counts) {
        long transitions = counts.transitionsFor(stage.getStageCode());
        long open = counts.openTicketsFor(stage.getStageCode());
        return new StageDtos.StageView(
                stage.getId(), templateId, stage.getStageCode(), stage.getDisplayName(),
                stage.getOwnerRole(), stage.getSlaHours(), stage.isOptional(),
                stage.getCanReturnTo() == null ? List.of() : List.copyOf(stage.getCanReturnTo()),
                stage.getIcon(), stage.getSeq(), position,
                transitions, open,
                transitions == 0 && open == 0);
    }

    private static short nextSeq(List<WorkflowStage> existing) {
        int max = existing.stream().mapToInt(WorkflowStage::getSeq).max().orElse(0);
        return (short) (max + SEQ_STEP);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    // ------------------------------------------------------------------
    // Refusals
    // ------------------------------------------------------------------

    /** 404 — no such template. */
    public static class TemplateNotFoundException extends RuntimeException {
        public TemplateNotFoundException(long templateId) {
            super("No workflow template %d.".formatted(templateId));
        }
    }

    /** 404 — no such stage on this template. */
    public static class StageNotFoundException extends RuntimeException {
        public StageNotFoundException(long templateId, long stageId) {
            super("No stage %d on template %d.".formatted(stageId, templateId));
        }
    }

    /** 409 — the code is already on this template. */
    public static class DuplicateStageException extends RuntimeException {
        private final String field;

        public DuplicateStageException(String field, String message) {
            super(message);
            this.field = field;
        }

        public String field() {
            return field;
        }
    }

    /**
     * 409 — the code is in use and cannot be renamed.
     *
     * <p>Carries both counts as properties, the way
     * {@code StatusService.StatusInUseException} carries {@code ticketCount}: the
     * screen renders its own sentence from them, and in this case a link to the
     * journey of a ticket that has been through the stage, which is the evidence
     * an Admin would otherwise go looking for.
     */
    public static class ImmutableStageCodeException extends RuntimeException {
        private final long transitionCount;
        private final long openTicketCount;

        public ImmutableStageCodeException(String code, long transitionCount, long openTicketCount) {
            super(("%s has been used — %d ribbon %s and %d ticket%s standing in it now. "
                    + "The code is stored as plain text on every one of those rows, so renaming "
                    + "it would leave their journeys unresolvable and stop the stage-SLA scan "
                    + "matching them, both without any error. Change the display name instead.")
                    .formatted(code, transitionCount,
                            transitionCount == 1 ? "segment" : "segments",
                            openTicketCount, openTicketCount == 1 ? "" : "s"));
            this.transitionCount = transitionCount;
            this.openTicketCount = openTicketCount;
        }

        public long transitionCount() {
            return transitionCount;
        }

        public long openTicketCount() {
            return openTicketCount;
        }
    }

    /** 409 — the proposed order would leave a return target pointing forwards. */
    public static class ReturnTargetDirectionException extends RuntimeException {
        private final List<String> pairs;

        public ReturnTargetDirectionException(List<String> pairs) {
            super(("That order would leave %s pointing forwards. A return target is a backward "
                    + "target, so clear it first or move the other stage instead.")
                    .formatted(String.join(", ", pairs)));
            this.pairs = List.copyOf(pairs);
        }

        public List<String> pairs() {
            return pairs;
        }
    }

    /** 400 — field-keyed, for the rules Bean Validation cannot express. */
    public static class StageValidationException extends RuntimeException {
        private final String field;

        public StageValidationException(String field, String message) {
            super(message);
            this.field = field;
        }

        public String field() {
            return field;
        }
    }
}
