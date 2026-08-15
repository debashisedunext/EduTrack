package com.edunext.edutrack.api.feature.masters.tasktypes;

import com.edunext.edutrack.domain.masters.Priority;
import com.edunext.edutrack.domain.masters.PriorityRepository;
import com.edunext.edutrack.domain.masters.TaskType;
import com.edunext.edutrack.domain.masters.TaskTypeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * B-020 · S-11, the Task Type Master.
 *
 * <p>The data layer was already here — A-007 created {@code task_types} and
 * B-002 seeded the eleven types of blueprint §S-11 with their icons, colours,
 * default levels and default SLAs. That seed's own header says so: "these are
 * master-data seed values, not schema — Admin can edit every one of them after
 * go-live via S-11/S-12 without a release." This class is the screen that makes
 * that sentence true, and the rules the schema deliberately does not encode.
 *
 * <h2>There is no delete, and that is the design</h2>
 *
 * <p>{@code tickets.task_type_id}, {@code sla_policies.task_type_id} and
 * B-019's {@code project_task_types.task_type_id} are all foreign keys
 * <b>without</b> cascades. A delete would fail at the database as a constraint
 * violation naming a MySQL index rather than a way forward — and "fixing" that
 * with a cascade would silently rewrite what a historical ticket says it was
 * raised against. B-019's migration already wrote this down at the constraint:
 * "an allow-list row referencing a task type is a reason that task type must not
 * vanish, and B-020's master deactivates rather than deletes for exactly this
 * class of reason." Retiring is {@code isActive = false}, which is the same call
 * B-018 made on a cleared SLA override and B-042 will make on a stage in use.
 *
 * <h2>Three refusals, none of them enforced by the schema</h2>
 *
 * <p><b>{@code code} is immutable</b> once created · <b>{@code code} is
 * unique</b> · <b>{@code name} is unique</b>, case-insensitively. Only the first
 * of those has an index behind it ({@code uq_task_types_code}), and an index
 * refuses with a constraint name rather than with a field-keyed message the form
 * can land on an input.
 *
 * <p>The name rule is the one that looks like tidiness and is not.
 * {@code ticketForm.ts} decides which types make the Client field mandatory
 * (§4B.2) by <b>matching on the display name</b>, so two types called "Client
 * Bug" would take that rule with them — and this screen is the only thing in the
 * product that can create the collision.
 */
@Service
public class TaskTypeService {

    /**
     * The four values {@code Level} can carry on the wire.
     *
     * <p><b>This is not the vocabulary a default level is validated against</b>
     * — {@code priorities} is, because B-021's whole point is that an Admin can
     * add a level and a hardcoded set is what B-015 removed from
     * {@code ResourceController}. This is the narrower second check: the
     * contract types {@code defaultLevel} as a closed four-value enum, so a
     * fifth priority stored here would serialise into a response the generated
     * TypeScript client's own zod schema rejects — a screen that breaks on read
     * because of what somebody saved on a different screen.
     *
     * <p>Opening {@code Level} touches Streams A, C and D ({@code tickets.level}
     * is typed by it everywhere) and is B-021's call, not this task's. Until
     * then the refusal is explicit and says which of the two rules it is, rather
     * than the fifth level being accepted and discovered later as a rendering
     * failure.
     */
    static final Set<String> CONTRACT_LEVELS = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");

    /** New types sort to the end, a gap apart, so a manual reorder has room. */
    private static final short SEQ_STEP = 10;

    private final TaskTypeRepository taskTypes;
    private final PriorityRepository priorities;
    private final TaskTypeUsageRepository usage;

    TaskTypeService(TaskTypeRepository taskTypes,
                    PriorityRepository priorities,
                    TaskTypeUsageRepository usage) {
        this.taskTypes = taskTypes;
        this.priorities = priorities;
        this.usage = usage;
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /**
     * Every row, retired ones included, in {@code seq} order.
     *
     * <p>The picker filters to the active ones client-side; the grid cannot,
     * and a ticket raised against a since-retired type still has to render its
     * name. B-064 states the same rule for the module master, in the same words,
     * for the same reason.
     */
    @Transactional(readOnly = true)
    public List<TaskTypeDtos.TaskTypeView> list() {
        Map<Integer, Long> counts = usage.ticketCountsByTaskType();
        return taskTypes.findAllByOrderBySeqAscIdAsc().stream()
                .map(t -> toView(t, counts.getOrDefault(t.getId(), 0L)))
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<TaskTypeDtos.TaskTypeView> find(int taskTypeId) {
        return taskTypes.findById(taskTypeId).map(this::toView);
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /**
     * <p>The code is upper-cased <em>before</em> the uniqueness check rather
     * than after, so {@code client_bug} is refused as a duplicate of
     * {@code CLIENT_BUG} instead of being stored beside it — B-015's argument on
     * role codes, and the collation would not save us either way since
     * {@code utf8mb4_0900_ai_ci} would let both through only if they differed by
     * more than case.
     */
    @Transactional
    public TaskTypeDtos.TaskTypeView create(TaskTypeDtos.TaskTypeWrite write) {
        String code = write.code().trim().toUpperCase(Locale.ROOT);
        if (taskTypes.existsByCode(code)) {
            throw new DuplicateTaskTypeException("code", "A task type with code '" + code
                    + "' already exists.");
        }

        String name = write.name().trim();
        taskTypes.findByNameIgnoreCase(name).ifPresent(clash -> {
            throw new DuplicateTaskTypeException("name", "'" + clash.getName()
                    + "' already exists. Two task types with the same name are "
                    + "indistinguishable in every picker that renders them.");
        });

        String level = normaliseLevel(write.defaultLevel());

        TaskType type = new TaskType();
        type.setCode(code);
        type.setName(name);
        type.setIcon(blankToNull(write.icon()));
        type.setColour(write.colour().trim());
        type.setDefaultLevel(level);
        type.setDefaultSlaHours(write.defaultSlaHrs());
        type.setSeq(write.seq() != null ? toSeq(write.seq()) : nextSeq());
        type.setActive(write.isActive() == null || write.isActive());

        // A type nothing has been raised against yet, so the count is known
        // without asking — and asking would be a second statement for a number
        // that cannot be anything else.
        return toView(taskTypes.save(type), 0L);
    }

    /**
     * Rename, recolour, re-ice, reorder, retire.
     *
     * <p><b>A code change is refused, and resending the stored code is a
     * no-op.</b> S-11 submits the whole form on every save, so any other reading
     * makes every edit a 409 — the mirror of the {@code u.id <> ?} B-013
     * documents on the resource form and of B-016's rule about resending a
     * project code. The code is what {@code TaskTypeRepository.findByCode} —
     * and so the Excel import — matches on, and it is the key any client should
     * hold instead of the mutable name.
     *
     * <p><b>Deactivating is never refused, whatever the ticket count.</b> The
     * count is on the row so the decision is informed; refusing it would leave
     * an organisation unable to retire a type it has stopped using, which is the
     * only reason the flag exists. The consequences are real and are the
     * screen's to state: the type leaves the create form's picker, and it leaves
     * every project's SLA matrix, which {@code SlaMatrixService} builds from the
     * active types.
     */
    @Transactional
    public Optional<TaskTypeDtos.TaskTypeView> update(int taskTypeId, TaskTypeDtos.TaskTypePatch patch) {
        Optional<TaskType> found = taskTypes.findById(taskTypeId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        TaskType type = found.get();

        if (patch.code() != null
                && !patch.code().trim().toUpperCase(Locale.ROOT).equals(type.getCode())) {
            throw new ImmutableTaskTypeCodeException(type.getCode());
        }

        if (patch.name() != null) {
            String name = patch.name().trim();
            taskTypes.findByNameIgnoreCase(name)
                    .filter(clash -> !clash.getId().equals(type.getId()))
                    .ifPresent(clash -> {
                        throw new DuplicateTaskTypeException("name", "'" + clash.getName()
                                + "' already exists. Two task types with the same name are "
                                + "indistinguishable in every picker that renders them.");
                    });
            type.setName(name);
        }
        if (patch.icon() != null) {
            type.setIcon(patch.icon().map(TaskTypeService::blankToNull).orElse(null));
        }
        if (patch.colour() != null) {
            type.setColour(patch.colour().trim());
        }
        if (patch.defaultLevel() != null) {
            type.setDefaultLevel(normaliseLevel(patch.defaultLevel()));
        }
        if (patch.defaultSlaHrs() != null) {
            type.setDefaultSlaHours(patch.defaultSlaHrs().orElse(null));
        }
        if (patch.seq() != null) {
            type.setSeq(toSeq(patch.seq()));
        }
        if (patch.isActive() != null) {
            type.setActive(patch.isActive());
        }

        return Optional.of(toView(taskTypes.save(type)));
    }

    // ------------------------------------------------------------------
    // validation
    // ------------------------------------------------------------------

    /**
     * Two checks, in this order, and the order is the point.
     *
     * <p>The priority master is asked first because it is the real referential
     * rule — a default level naming a level that does not exist, or one that has
     * been retired, pre-fills the create form with a value its own level picker
     * will not offer. The contract enum is asked second, and its message names
     * B-021, because that refusal is a limitation of the wire format rather than
     * a fact about the organisation.
     */
    private String normaliseLevel(String raw) {
        String level = raw.trim().toUpperCase(Locale.ROOT);

        Optional<Priority> priority = priorities.findByCode(level);
        if (priority.isEmpty()) {
            throw new TaskTypeValidationException("defaultLevel", "No such level: '" + level
                    + "'. Levels come from the priority master (S-12).");
        }
        if (!priority.get().isActive()) {
            throw new TaskTypeValidationException("defaultLevel", "'" + level + "' is a retired "
                    + "level. A task type defaulting to it would pre-fill the create form with a "
                    + "value its own level picker no longer offers.");
        }
        if (!CONTRACT_LEVELS.contains(level)) {
            throw new TaskTypeValidationException("defaultLevel", "'" + level + "' exists in the "
                    + "priority master but cannot be carried by the API's Level type, which is a "
                    + "closed four-value enum. Opening it is B-021's change and touches the ticket "
                    + "contract in three other streams; until then a task type cannot default to it.");
        }
        return level;
    }

    // ------------------------------------------------------------------
    // mapping
    // ------------------------------------------------------------------

    private TaskTypeDtos.TaskTypeView toView(TaskType type) {
        return toView(type, usage.ticketCount(type.getId()));
    }

    private static TaskTypeDtos.TaskTypeView toView(TaskType type, long ticketCount) {
        return new TaskTypeDtos.TaskTypeView(
                type.getId(),
                type.getCode(),
                type.getName(),
                type.getIcon(),
                type.getColour(),
                type.getDefaultLevel(),
                type.getDefaultSlaHours(),
                type.getSeq(),
                type.isActive(),
                ticketCount);
    }

    /**
     * {@code MAX(seq) + 10}, so a new type lands at the end of the picker with
     * room either side of it for a later reorder.
     *
     * <p>Read from the entities rather than with a {@code MAX()} statement:
     * eleven rows are already in memory for every other reason this class has,
     * and the alternative is a second round trip for a number that is not
     * required to be exact — two types created in the same millisecond sharing a
     * {@code seq} is a display-order tie, which
     * {@code findAllByOrderBySeqAscIdAsc} already breaks.
     */
    private short nextSeq() {
        short highest = 0;
        for (TaskType type : taskTypes.findAll()) {
            if (type.getSeq() > highest) {
                highest = type.getSeq();
            }
        }
        return (short) Math.min(Short.MAX_VALUE, highest + SEQ_STEP);
    }

    /**
     * The column is a {@code SMALLINT} and the contract says {@code int32}, so
     * the narrowing has to happen somewhere. Refusing out of range beats
     * truncating: {@code (short) 40000} is a negative number, and a type that
     * silently sorted to the front of every picker would be a display bug
     * nobody could trace back to the save that caused it.
     */
    private static short toSeq(int seq) {
        if (seq < 0 || seq > Short.MAX_VALUE) {
            throw new TaskTypeValidationException("seq",
                    "seq must be between 0 and " + Short.MAX_VALUE + ".");
        }
        return (short) seq;
    }

    /**
     * An empty icon and an absent one mean the same thing, so they are stored
     * the same way — otherwise "has an icon" is true for a type that renders
     * none. {@code RoleService.blankToNull} makes the same call on a
     * description.
     */
    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    // ------------------------------------------------------------------
    // Refusals — see TaskTypeExceptionHandler for the wire shapes
    // ------------------------------------------------------------------

    /**
     * One exception for both uniqueness rules, carrying the field it belongs to.
     *
     * <p>Two classes would be two handlers producing the same problem document
     * with a different key, and the S-11 form's only question is which input to
     * put the message on.
     */
    static class DuplicateTaskTypeException extends RuntimeException {
        private final String field;

        DuplicateTaskTypeException(String field, String message) {
            super(message);
            this.field = field;
        }

        String field() {
            return field;
        }
    }

    static class ImmutableTaskTypeCodeException extends RuntimeException {
        ImmutableTaskTypeCodeException(String code) {
            super("A task type code cannot be changed once created — '" + code + "' is what the "
                    + "Excel import matches on and what identifies the type to any client that "
                    + "cannot rely on the name. Deactivate this type and create a replacement.");
        }
    }

    /**
     * 400, field-keyed: a value the caller can correct, not a rule refusing an
     * otherwise valid request.
     *
     * <p>Bean Validation cannot express either of the two rules that raise this
     * — one is a lookup against another table, the other a narrowing that
     * depends on what the contract's enum happens to contain — so they run in
     * the service and come back in the same shape a {@code @Pattern} would.
     * {@code SlaValidationException} in the projects feature is the same idea.
     */
    static class TaskTypeValidationException extends RuntimeException {
        private final String field;

        TaskTypeValidationException(String field, String message) {
            super(message);
            this.field = field;
        }

        String field() {
            return field;
        }
    }
}
