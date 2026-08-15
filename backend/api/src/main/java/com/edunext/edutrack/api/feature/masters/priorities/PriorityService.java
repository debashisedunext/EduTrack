package com.edunext.edutrack.api.feature.masters.priorities;

import com.edunext.edutrack.domain.masters.Priority;
import com.edunext.edutrack.domain.masters.PriorityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * B-021 · S-12, the Priority / Level Master.
 *
 * <p>The data layer was already here — A-007 created {@code priorities} and
 * B-002 seeded Low, Medium, High and Critical with the §12.1 chip colours, the
 * default SLA hours and the escalation flag on Critical. That seed's own header
 * says so: "these are master-data seed values, not schema — Admin can edit every
 * one of them after go-live via S-11/S-12 without a release." This class is the
 * screen that makes that sentence true, and the four rules the schema
 * deliberately does not encode.
 *
 * <h2>Nothing served this table until now</h2>
 *
 * <p>{@code GET /masters/priorities} has been in the contract, in the MSW mock
 * and in the generated TypeScript client since D-001 with <b>no controller
 * anywhere in the backend</b> — the fifth instance of "declared, mocked, never
 * mounted", after B-023's nine calendar operations, B-014's
 * {@code PATCH /users/{userId}/status}, B-018's two SLA operations and B-020's
 * {@code listTaskTypes}. It is the second in a row where shipped screens already
 * call it: {@code CreateTicketPage} builds its {@code LevelPicker} from this
 * route and {@code TicketListPage} builds its level filter from it. Both would
 * have 404'd on first contact with a real backend.
 *
 * <h2>There is no delete, and that is the design</h2>
 *
 * <p>{@code tickets.level}, {@code task_types.default_level} and
 * {@code sla_policies.level} all hold the <em>code</em> in a {@code VARCHAR},
 * deliberately not a foreign key — A-007's migration comment states the trade:
 * "no referential integrity on {@code level}, in exchange for a master that can
 * change without rewriting history". Deleting the row therefore succeeds at the
 * database and leaves every ticket ever raised at that level rendering a code
 * nothing resolves. Retiring is {@code isActive = false}, the same call B-020
 * made on a task type and B-042 will make on a stage in use.
 *
 * <h2>Four refusals, none of them enforced by the schema</h2>
 *
 * <ol>
 *   <li><b>{@code level} is immutable</b> once created, and this is stricter
 *       than the sibling screen's version of the rule. A task type's code is
 *       matched on by the Excel import; a level's code is <em>stored</em>, in
 *       three tables, with nothing to cascade a rename through.</li>
 *   <li><b>{@code level} and {@code name} are unique</b>, the latter
 *       case-insensitively and with no index behind it.</li>
 *   <li><b>Exactly one active level carries the escalation flag</b> — see
 *       {@link #applyEscalationFlag}.</li>
 *   <li><b>A level active task types default to cannot be retired</b> — see
 *       {@link #guardRetire}.</li>
 * </ol>
 */
@Service
public class PriorityService {

    /**
     * The four values the contract's {@code Level} can carry, and therefore the
     * only codes this master may hold.
     *
     * <p><b>S-12 says "Admin can add further levels without a release", and this
     * set is why that is not yet true.</b> The decision was taken deliberately
     * in B-021 rather than inherited: {@code Level} types {@code Ticket.level},
     * {@code Ticket.originalLevel}, {@code TaskType.defaultLevel},
     * {@code SlaPolicyWrite.level}, {@code SlaPolicyCell.level},
     * {@code ChangeTicketPriorityBody.level} and two query parameters. A fifth
     * code stored here would serialise into a response the generated TypeScript
     * client's own zod schema rejects — a screen that breaks on read because of
     * what somebody saved on a different screen — and Stream C's
     * {@code LevelPicker} and {@code columns.tsx} key their chip variants off
     * {@code Record<Level, …>} maps a fifth key would leave {@code undefined}.
     *
     * <p>Opening it is a coordinated change across Streams A, C and D, not one
     * this screen can make alone. Until then the refusal is explicit and names
     * what has to change, rather than the fifth level being accepted and
     * discovered later as a rendering failure. Every other field S-12 lists —
     * name, colour, default SLA hours, escalation flag, order, retire — is
     * fully editable.
     *
     * <p>{@code TaskTypeService.CONTRACT_LEVELS} holds the same four for the
     * mirror-image check on {@code defaultLevel}. Not extracted to a shared
     * constant on purpose: the two are equal today because both track the same
     * contract enum, and the day that enum opens they are deleted rather than
     * edited. A shared constant would look like a vocabulary worth keeping.
     */
    static final Set<String> CONTRACT_LEVELS = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");

    /** New levels sort to the end, a gap apart, so a manual reorder has room. */
    private static final short SEQ_STEP = 10;

    private final PriorityRepository priorities;
    private final PriorityUsageRepository usage;

    PriorityService(PriorityRepository priorities, PriorityUsageRepository usage) {
        this.priorities = priorities;
        this.usage = usage;
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /**
     * Active levels in {@code seq} order by default; every level when asked.
     *
     * <p><b>This is the one place S-12 departs from how B-020 and B-064 read
     * their masters</b>, both of which hand retired rows to every caller and
     * tell pickers to filter. The departure is not a preference — it is that the
     * two existing consumers of this route cannot filter. {@code CreateTicketPage}
     * drops {@code isActive === false} task types before building its type
     * picker (line 67) and then maps priorities straight into {@code LevelPicker}
     * without a filter (line 71), because until this task the endpoint could not
     * return a retired one. Widening the default would put a retired level into
     * the create form and the ticket list's filter, both Stream C's files.
     *
     * <p>So the default is what those screens already assume, and the grid — the
     * only caller that needs the rest — asks for it. When C adds the filter, the
     * default can widen and this comment is the note explaining why it was ever
     * narrow.
     */
    @Transactional(readOnly = true)
    public List<PriorityDtos.PriorityView> list(boolean includeInactive) {
        PriorityUsageRepository.Counts counts = usage.all();
        List<Priority> rows = includeInactive
                ? priorities.findAllByOrderBySeqAscIdAsc()
                : priorities.findByIsActiveTrueOrderBySeqAsc();
        return rows.stream().map(p -> toView(p, counts.of(p.getCode()))).toList();
    }

    @Transactional(readOnly = true)
    public Optional<PriorityDtos.PriorityView> find(int priorityId) {
        return priorities.findById(priorityId).map(this::toView);
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /**
     * <p>The code is upper-cased <em>before</em> the uniqueness check rather
     * than after, so {@code critical} is refused as a duplicate of
     * {@code CRITICAL} instead of being stored beside it — B-015's argument on
     * role codes and B-020's on task type codes. The collation would not save us
     * either way, since {@code utf8mb4_0900_ai_ci} lets both through only if
     * they differ by more than case.
     */
    @Transactional
    public PriorityDtos.PriorityView create(PriorityDtos.PriorityWrite write) {
        String code = normaliseLevel(write.level());
        if (priorities.existsByCode(code)) {
            throw new DuplicatePriorityException("level", "A level with code '" + code
                    + "' already exists. To bring back a retired one, reactivate it instead.");
        }

        String name = write.name().trim();
        priorities.findByNameIgnoreCase(name).ifPresent(clash -> {
            throw new DuplicatePriorityException("name", "'" + clash.getName()
                    + "' already exists. Two levels with the same name are indistinguishable in "
                    + "the picker, in the ticket grid and in every SLA matrix's column headers.");
        });

        boolean active = write.isActive() == null || write.isActive();
        boolean wantsFlag = Boolean.TRUE.equals(write.autoEscalates());

        Priority priority = new Priority();
        priority.setCode(code);
        priority.setName(name);
        priority.setColour(write.colour().trim());
        priority.setDefaultSlaHours(write.defaultSlaHrs());
        priority.setSeq(write.seq() != null ? toSeq(write.seq()) : nextSeq());
        priority.setActive(active);
        priority.setEscalationTrigger(false);

        // Checked against the end state before the first save rather than after
        // it. A retired-and-flagged create would otherwise insert a row and
        // rely on the transaction rolling it back — correct, but it makes the
        // refusal depend on a rollback rather than on never having written.
        if (wantsFlag && !active) {
            applyEscalationFlag(priority, true, false);
        }

        Priority saved = priorities.save(priority);
        if (wantsFlag) {
            applyEscalationFlag(saved, true, active);
            saved = priorities.save(saved);
        }

        // A level nothing has been raised at yet, so all three counts are known
        // without asking — and asking would be three statements for numbers that
        // cannot be anything else.
        return toView(saved, new PriorityUsageRepository.Counts.Row(0L, 0, 0));
    }

    /**
     * Rename, recolour, re-target, reorder, retire.
     *
     * <p><b>A code change is refused, and resending the stored code is a
     * no-op.</b> S-12 submits the whole form on every save, so any other reading
     * makes every edit a 409 — B-016's rule about resending a project code and
     * B-020's about resending a task type code.
     *
     * <p>The order below is deliberate. Both cross-row rules —
     * {@link #guardRetire} and {@link #applyEscalationFlag} — run before any
     * field is written, so a refused save leaves the row exactly as it was
     * rather than half-applied. Within a transaction that would roll back
     * anyway; the entity in the persistence context would not.
     */
    @Transactional
    public Optional<PriorityDtos.PriorityView> update(int priorityId,
                                                      PriorityDtos.PriorityPatch patch) {
        Optional<Priority> found = priorities.findById(priorityId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Priority priority = found.get();

        if (patch.level() != null
                && !patch.level().trim().toUpperCase(Locale.ROOT).equals(priority.getCode())) {
            throw new ImmutablePriorityCodeException(priority.getCode());
        }

        // The state the row will be in when this patch is done, computed before
        // anything is written. `{"isActive": false, "autoEscalates": true}` is a
        // single request that would otherwise pass both guards by reading a
        // half-applied entity: the escalation check would see the level still
        // active, and the retire check would see it not yet flagged. Deriving
        // the end state once is what closes that, rather than ordering the two
        // writes carefully and hoping nobody reorders them later.
        boolean willBeActive = patch.isActive() != null ? patch.isActive() : priority.isActive();
        if (priority.isActive() && !willBeActive) {
            guardRetire(priority);
        }
        if (patch.autoEscalates() != null) {
            applyEscalationFlag(priority, patch.autoEscalates(), willBeActive);
        }

        if (patch.name() != null) {
            String name = patch.name().trim();
            priorities.findByNameIgnoreCase(name)
                    .filter(clash -> !clash.getId().equals(priority.getId()))
                    .ifPresent(clash -> {
                        throw new DuplicatePriorityException("name", "'" + clash.getName()
                                + "' already exists. Two levels with the same name are "
                                + "indistinguishable in the picker, in the ticket grid and in "
                                + "every SLA matrix's column headers.");
                    });
            priority.setName(name);
        }
        if (patch.colour() != null) {
            priority.setColour(patch.colour().trim());
        }
        if (patch.defaultSlaHrs() != null) {
            priority.setDefaultSlaHours(patch.defaultSlaHrs().orElse(null));
        }
        if (patch.seq() != null) {
            priority.setSeq(toSeq(patch.seq()));
        }
        if (patch.isActive() != null) {
            priority.setActive(patch.isActive());
        }

        return Optional.of(toView(priorities.save(priority)));
    }

    // ------------------------------------------------------------------
    // The two cross-row rules
    // ------------------------------------------------------------------

    /**
     * Exactly one <em>active</em> level is the SLA engine's escalation target.
     *
     * <p>Blueprint §6 and §1: "a task crossing its Planned Close Date is
     * auto-promoted to Critical and pushed to the reporting manager", and A-007
     * wrote the column's meaning down as "the level the SLA engine escalates TO
     * on breach". So the flag is a <b>pointer</b>, not an attribute — and the
     * column is a bare {@code TINYINT(1) NOT NULL DEFAULT 0} with no uniqueness
     * constraint and, until this method, nothing in the codebase reading or
     * writing it at all.
     *
     * <p>Two failure modes, both silent, both discovered by Stream D's scanner
     * rather than by whoever caused them:
     *
     * <ul>
     *   <li><b>Zero flags</b> — auto-escalation has nowhere to promote a ticket
     *       to, and the §6 engine stops doing the thing blueprint §1 lists as
     *       one of the product's three headline behaviours. Clearing the last
     *       one is therefore refused.</li>
     *   <li><b>Two flags</b> — the target is ambiguous and which one wins is
     *       whatever order the scanner's query happens to return. So setting the
     *       flag is <b>single-writer</b>: it clears every other row first. An
     *       admin moves the target by setting it where they want it, never by
     *       clearing it where they don't.</li>
     * </ul>
     *
     * <p>A retired level cannot hold it, for the same reason zero cannot:
     * promoting a ticket to a level no picker offers is a ticket nobody can
     * subsequently change.
     *
     * @param willBeActive the row's active state <em>after</em> the whole patch,
     *                     not the stored one — see the note in {@link #update}
     */
    private void applyEscalationFlag(Priority priority, boolean wanted, boolean willBeActive) {
        if (!wanted) {
            if (!priority.isEscalationTrigger()) {
                return;
            }
            throw new EscalationTargetException("autoEscalates",
                    "'" + priority.getCode() + "' is the level the SLA engine escalates to on "
                    + "breach (§6). Clearing it would leave auto-escalation with no target and "
                    + "silently stop promoting overdue tickets. Set the flag on another level "
                    + "instead — doing so clears this one.");
        }

        if (!willBeActive) {
            throw new EscalationTargetException("autoEscalates",
                    "A retired level cannot be the escalation target. A ticket promoted to '"
                    + priority.getCode() + "' would carry a level no picker offers, and nobody "
                    + "could change it afterwards.");
        }

        for (Priority other : priorities.findByIsEscalationTriggerTrue()) {
            if (!other.getId().equals(priority.getId())) {
                other.setEscalationTrigger(false);
                priorities.save(other);
            }
        }
        priority.setEscalationTrigger(true);
    }

    /**
     * Retiring is refused when active task types still default to this level,
     * and permitted whatever the ticket and SLA-policy counts are.
     *
     * <p>The asymmetry is the point, and it is not the call B-020 made — that
     * screen never refuses a deactivate. The difference is what the leftover
     * state does:
     *
     * <ul>
     *   <li><b>Tickets keep the level and go on rendering it.</b> That is the
     *       whole reason {@code tickets.level} is a {@code VARCHAR} and not a
     *       foreign key. Refusing here would leave an organisation unable to
     *       retire a level it had stopped using, which is the only reason the
     *       flag exists.</li>
     *   <li><b>SLA policy rows stop resolving, and that is visible and
     *       repairable.</b> {@code SlaMatrixService} builds its column axis from
     *       the active levels, so the column simply leaves every project's grid.
     *       Nothing is broken, nothing is deleted, and reactivating brings it
     *       back.</li>
     *   <li><b>Task types become unsaveable, and that is neither.</b>
     *       {@code TaskTypeService.normaliseLevel} refuses a retired level as a
     *       {@code defaultLevel} — so a type left pointing here fails validation
     *       on its next save, on a screen whose admin has no way to see what
     *       caused it. A screen must not be able to put a different screen into
     *       a state it cannot get out of. Repoint the types first.</li>
     * </ul>
     *
     * <p>The escalation target is guarded separately: retiring the flagged level
     * hits {@link #applyEscalationFlag}'s active check via the same path, so a
     * retire that would leave §6 with no target is refused with the message
     * about the target rather than the one about task types.
     */
    private void guardRetire(Priority priority) {
        if (priority.isEscalationTrigger()) {
            throw new EscalationTargetException("isActive",
                    "'" + priority.getCode() + "' is the level the SLA engine escalates to on "
                    + "breach (§6). Retiring it would leave auto-escalation with no target. Move "
                    + "the escalation flag to another level first.");
        }

        PriorityUsageRepository.Counts.Row counts = usage.forLevel(priority.getCode());
        if (counts.taskTypes() > 0) {
            List<String> names = usage.activeTaskTypeNamesDefaultingTo(priority.getCode());
            throw new PriorityInUseException(counts.taskTypes(), names, priority.getCode());
        }
    }

    // ------------------------------------------------------------------
    // validation
    // ------------------------------------------------------------------

    /**
     * Upper-cases the code and refuses anything outside the contract's four.
     *
     * <p>The message names the change and the streams it touches rather than
     * saying "invalid", because the caller has done nothing wrong: S-12 promises
     * they can do this, and the reason they cannot is a limitation of the wire
     * format. {@code TaskTypeService.normaliseLevel} refuses the mirror case
     * with the mirror message.
     */
    private static String normaliseLevel(String raw) {
        String level = raw.trim().toUpperCase(Locale.ROOT);
        if (!CONTRACT_LEVELS.contains(level)) {
            throw new PriorityValidationException("level", "'" + level + "' cannot be carried by "
                    + "the API's Level type, which is a closed four-value enum "
                    + "(LOW, MEDIUM, HIGH, CRITICAL). S-12 does promise that an Admin can add "
                    + "levels; delivering it means opening that enum, which retypes six ticket "
                    + "and SLA schemas and needs Stream C's two Record<Level, …> chip maps made "
                    + "total. Until then a fifth level cannot be stored, because it would "
                    + "serialise into a response the generated client's own schema rejects.");
        }
        return level;
    }

    // ------------------------------------------------------------------
    // mapping
    // ------------------------------------------------------------------

    private PriorityDtos.PriorityView toView(Priority priority) {
        return toView(priority, usage.forLevel(priority.getCode()));
    }

    private static PriorityDtos.PriorityView toView(Priority priority,
                                                    PriorityUsageRepository.Counts.Row counts) {
        return new PriorityDtos.PriorityView(
                priority.getId(),
                priority.getCode(),
                priority.getName(),
                priority.getColour(),
                priority.getDefaultSlaHours(),
                priority.isEscalationTrigger(),
                priority.getSeq(),
                priority.isActive(),
                counts.tickets(),
                counts.taskTypes(),
                counts.slaPolicies());
    }

    /**
     * {@code MAX(seq) + 10}, so a new level lands at the end of the picker with
     * room either side of it for a later reorder.
     *
     * <p>Read from the entities rather than with a {@code MAX()} statement, for
     * the reason {@code TaskTypeService.nextSeq} gives: four rows are already in
     * memory for every other reason this class has, and a {@code seq} tie is a
     * display-order tie that {@code findAllByOrderBySeqAscIdAsc} already breaks.
     */
    private short nextSeq() {
        short highest = 0;
        for (Priority priority : priorities.findAll()) {
            if (priority.getSeq() > highest) {
                highest = priority.getSeq();
            }
        }
        return (short) Math.min(Short.MAX_VALUE, highest + SEQ_STEP);
    }

    /**
     * The column is a {@code SMALLINT} and the contract says {@code int32}, so
     * the narrowing has to happen somewhere. Refusing out of range beats
     * truncating: {@code (short) 40000} is negative, and a level that silently
     * sorted to the front of every picker <em>and</em> became the first column of
     * every SLA matrix would be a display bug nobody could trace back to the save
     * that caused it.
     */
    private static short toSeq(int seq) {
        if (seq < 0 || seq > Short.MAX_VALUE) {
            throw new PriorityValidationException("seq",
                    "seq must be between 0 and " + Short.MAX_VALUE + ".");
        }
        return (short) seq;
    }

    // ------------------------------------------------------------------
    // Refusals — see PriorityExceptionHandler for the wire shapes
    // ------------------------------------------------------------------

    /**
     * One exception for both uniqueness rules, carrying the field it belongs to.
     *
     * <p>Two classes would be two handlers producing the same problem document
     * with a different key, and the S-12 form's only question is which input to
     * put the message on.
     */
    static class DuplicatePriorityException extends RuntimeException {
        private final String field;

        DuplicatePriorityException(String field, String message) {
            super(message);
            this.field = field;
        }

        String field() {
            return field;
        }
    }

    static class ImmutablePriorityCodeException extends RuntimeException {
        ImmutablePriorityCodeException(String code) {
            super("A level code cannot be changed once created. '" + code + "' is stored as a "
                    + "string in tickets.level, task_types.default_level and sla_policies.level, "
                    + "none of which is a foreign key — so a rename here would not cascade, it "
                    + "would orphan every row holding the old value. Retire this level and "
                    + "create a replacement.");
        }
    }

    /**
     * The escalation target is missing, ambiguous or retired — §6's pointer.
     *
     * <p>Its own class rather than a {@link PriorityValidationException} with a
     * different sentence, because the two are different situations with
     * different statuses: this is a 409 (the request is well formed and the rule
     * is about the rest of the table), that is a 400 (the value itself is
     * wrong). The S-12 form tells them apart by problem `type` rather than by
     * prose.
     */
    static class EscalationTargetException extends RuntimeException {
        private final String field;

        EscalationTargetException(String field, String message) {
            super(message);
            this.field = field;
        }

        String field() {
            return field;
        }
    }

    /** A retire refused because active task types still default to this level. */
    static class PriorityInUseException extends RuntimeException {
        private final int taskTypeCount;
        private final List<String> taskTypeNames;

        PriorityInUseException(int taskTypeCount, List<String> taskTypeNames, String code) {
            super(taskTypeCount + " active task type" + (taskTypeCount == 1 ? "" : "s")
                    + " default to '" + code + "'"
                    + (taskTypeNames.isEmpty() ? "" : " (" + String.join(", ", taskTypeNames)
                            + (taskTypeCount > taskTypeNames.size()
                                    ? " and " + (taskTypeCount - taskTypeNames.size()) + " more"
                                    : "")
                            + ")")
                    + ". Retiring it would leave them pre-filling the create form with a level "
                    + "its own picker no longer offers, and failing validation on their next "
                    + "save. Repoint them on the task type master first.");
            this.taskTypeCount = taskTypeCount;
            this.taskTypeNames = List.copyOf(taskTypeNames);
        }

        int taskTypeCount() {
            return taskTypeCount;
        }

        List<String> taskTypeNames() {
            return taskTypeNames;
        }
    }

    /**
     * 400, field-keyed: a value the caller can correct, not a rule refusing an
     * otherwise valid request.
     *
     * <p>Bean Validation cannot express either of the two rules that raise this
     * — one is a narrowing that depends on what the contract's enum happens to
     * contain, the other a {@code SMALLINT} range — so they run in the service
     * and come back in the same shape a {@code @Pattern} would.
     */
    static class PriorityValidationException extends RuntimeException {
        private final String field;

        PriorityValidationException(String field, String message) {
            super(message);
            this.field = field;
        }

        String field() {
            return field;
        }
    }
}
