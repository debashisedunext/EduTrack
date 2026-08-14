package com.edunext.edutrack.api.feature.masters.projects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * B-018 · the S-10 SLA tab — "per task type × level → response hrs, resolution
 * hrs, L1/L2 escalation targets".
 *
 * <h2>What the screen edits, and what it only shows</h2>
 *
 * <p>{@code sla_policies} is layered. A null {@code project_id} is the org-wide
 * default and a null {@code task_type_id} means "any type", so blueprint §6
 * resolves most-specific-first. A task type × level grid has exactly one kind of
 * cell — this project, this task type, this level — so <b>this screen writes
 * rung 1 and nothing else</b>. Rungs 2 and 3 are read, rendered, and left
 * alone.
 *
 * <p>Which makes the read and the write deliberately asymmetric, and the
 * asymmetry is the whole design:
 *
 * <ul>
 *   <li><b>{@link #matrix} returns every cell resolved.</b> Returning only this
 *       project's own rows would render as a nearly empty grid for a project
 *       whose tickets all get perfectly good planned close dates — an
 *       administrator would read "nothing configured" and configure it, and the
 *       act of doing so is the defect below.</li>
 *   <li><b>{@link #replace} takes only the cells that are overrides.</b> If the
 *       screen sent the resolved grid back, every inherited figure would become
 *       a project-level row, and the project would silently stop following the
 *       org-wide default it was shown as following. Somebody changes the
 *       org-wide Critical policy six months later, every project moves except
 *       the ones anybody ever opened this tab on, and nothing on any screen says
 *       why.</li>
 * </ul>
 *
 * <h2>The ladder is written down twice, on purpose, with a test between them</h2>
 *
 * <p>C-012's {@code PlannedCloseDateService.resolve} is the same five rungs, one
 * cell at a time, through {@code SlaPolicyRepository}. Calling it per cell here
 * would be up to five statements × eleven task types × four levels for one page
 * load. So {@link #resolve} walks the rungs in memory over three bounded reads,
 * and {@code SlaLadderAgreementTest} asserts that every cell of a real grid —
 * figures <i>and</i> source name — equals what C-012 answers for the same
 * triple. Two implementations with a test between them is a cost; two
 * implementations with nothing between them is how the SLA tab and the create
 * form end up quoting different numbers at the same client.
 *
 * <h2>What is deliberately not a rule</h2>
 *
 * <p><b>The grid does not have to be complete.</b> There is no requirement that
 * every cell be overridden, or that a project have any overrides at all — the
 * ladder is exactly the mechanism that makes an unconfigured project work, and
 * B-016's projects are all created without one.
 *
 * <p><b>An override is not checked against the rung it replaces.</b> A project
 * may set a Critical resolution target <i>longer</i> than the org-wide default;
 * that is a negotiated contract, not a mistake, and a server that refused it
 * would mean the true figure could not be written down. Same call B-017 made
 * about allocations summing past 100.
 */
@Service
class SlaMatrixService {

    private final SlaMatrixRepository repository;

    SlaMatrixService(SlaMatrixRepository repository) {
        this.repository = repository;
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /**
     * The resolved grid: every active task type × every active level.
     *
     * <p>Ordered task type by task type in the master's own {@code seq}, then
     * level in the priority master's — so the screen renders in the order an
     * administrator configured those masters in, rather than by id, which is
     * insertion order and means nothing to anybody.
     *
     * @throws NoSuchProjectException 404, never 403 — CLAUDE.md's rule
     */
    @Transactional(readOnly = true)
    List<SlaPolicyDtos.SlaCell> matrix(long projectId) {
        requireProject(projectId);

        List<SlaMatrixRepository.TaskTypeRow> taskTypes = repository.activeTaskTypes();
        List<SlaMatrixRepository.LevelRow> levels = repository.activeLevels();
        Ladder ladder = new Ladder(projectId, repository.policiesFor(projectId));

        List<SlaPolicyDtos.SlaCell> cells = new ArrayList<>(taskTypes.size() * levels.size());
        for (SlaMatrixRepository.TaskTypeRow taskType : taskTypes) {
            for (SlaMatrixRepository.LevelRow level : levels) {
                cells.add(ladder.resolve(taskType, level));
            }
        }
        return cells;
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /**
     * Replace this project's overrides with the ones supplied, in one
     * transaction.
     *
     * <p>Deactivate-then-upsert rather than a diff. The diff is the obvious
     * implementation and it needs a dynamic {@code IN} clause over composite
     * keys to find the rows to drop; this reaches the same state in two
     * statements plus one per row, inside a transaction, and reactivation is
     * free because the upsert already has to handle it.
     *
     * <p><b>An empty body is a legitimate request</b> and clears every override
     * — the project goes back to inheriting entirely. It is not treated as a
     * probable mistake, because the alternative is a screen where the last
     * override can be edited but not removed.
     *
     * @return the newly resolved grid, so the caller renders what it saved
     *         rather than what it sent — the two differ wherever a cell was
     *         cleared and now inherits
     */
    @Transactional
    List<SlaPolicyDtos.SlaCell> replace(long projectId, List<SlaPolicyDtos.SlaPolicyWrite> overrides) {
        requireProject(projectId);
        validate(overrides);

        repository.deactivateOverrides(projectId);
        for (SlaPolicyDtos.SlaPolicyWrite override : overrides) {
            repository.upsertOverride(projectId,
                    override.taskTypeId(),
                    override.level(),
                    override.responseHrs(),
                    override.resolutionHrs(),
                    override.l1(),
                    override.l2());
        }
        return matrix(projectId);
    }

    // ------------------------------------------------------------------
    // The rules
    // ------------------------------------------------------------------

    /**
     * Four refusals, none of them enforced by the schema.
     *
     * <p>All four are collected before anything is written, and they are checked
     * in this order for one reason: the whole body is one transaction, so a row
     * refused halfway through would roll back the rows before it and the caller
     * would be told about the twelfth cell of a save that also silently did not
     * apply the first eleven.
     */
    private void validate(List<SlaPolicyDtos.SlaPolicyWrite> overrides) {
        Set<SlaPolicyDtos.SlaCell.Key> seen = new HashSet<>();

        for (SlaPolicyDtos.SlaPolicyWrite override : overrides) {
            // The same cell twice. The upsert would apply them in order and keep
            // the last, so the caller would be told the save succeeded and one
            // of the two figures they entered would be gone. `uq_sla_policies`
            // cannot catch it — both rows have the same key, which is precisely
            // why `ON DUPLICATE KEY UPDATE` swallows it.
            if (!seen.add(override.key())) {
                throw new SlaValidationException("taskTypeId",
                        "task type " + override.taskTypeId() + " is listed twice at " + override.level());
            }

            // A real task type. Existence, not activity — see
            // SlaMatrixRepository.taskTypeExists.
            if (!repository.taskTypeExists(override.taskTypeId())) {
                throw new SlaValidationException("taskTypeId",
                        "no such task type: " + override.taskTypeId());
            }

            // A real level. Checked against `priorities` rather than an enum,
            // because S-12 lets an Admin add one without a release — the same
            // reason `tickets.level` is a VARCHAR and not a foreign key.
            if (!repository.levelExists(override.level())) {
                throw new SlaValidationException("level", "no such level: " + override.level());
            }

            // A response target later than the resolution target is not a
            // policy, it is a transposition. Nothing downstream would reject it:
            // the scanner would warn about a first response that is overdue
            // after the ticket was already due to be closed.
            if (override.responseHrs() != null
                    && override.responseHrs().compareTo(override.resolutionHrs()) > 0) {
                throw new SlaValidationException("responseHrs",
                        "response target (" + override.responseHrs()
                                + "h) cannot be longer than the resolution target ("
                                + override.resolutionHrs() + "h)");
            }
        }
    }

    /** 404 for an unknown project, checked first on both operations. */
    private void requireProject(long projectId) {
        if (!repository.projectExists(projectId)) {
            throw new NoSuchProjectException();
        }
    }

    // ------------------------------------------------------------------
    // The ladder
    // ------------------------------------------------------------------

    /**
     * Blueprint §6's resolution ladder over one project's policy rows, held in
     * memory.
     *
     * <p>The three policy rungs are indexed once at construction rather than
     * scanned per cell: forty-four cells over a list that already contains every
     * org-wide row in the system is the kind of nested loop that is invisible
     * until an organisation has a few hundred policies.
     *
     * <p>Rungs 4 and 5 are not policy rows at all. See
     * {@code SlaResolution.Source} for why the ladder does not stop at rung 3
     * the way {@code SlaPolicyRepository}'s javadoc does — in short, "no SLA" is
     * the right answer for a scanner and the wrong one for a screen, and a
     * project on its first day would otherwise show forty-four blank cells.
     */
    private static final class Ladder {

        private final Map<SlaPolicyDtos.SlaCell.Key, SlaMatrixRepository.PolicyRow> rung1 = new LinkedHashMap<>();
        private final Map<String, SlaMatrixRepository.PolicyRow> rung2 = new LinkedHashMap<>();
        private final Map<String, SlaMatrixRepository.PolicyRow> rung3 = new LinkedHashMap<>();

        Ladder(long projectId, List<SlaMatrixRepository.PolicyRow> rows) {
            // Rows arrive ordered by id, and `putIfAbsent` is what makes "first
            // wins" mean the same thing here as the `ORDER BY id ... LIMIT 1`
            // the repository's rung-2 and rung-3 queries use. `put` would mean
            // last-wins, which is a different row and no more defensible — the
            // point is that the two implementations pick the *same* one.
            for (SlaMatrixRepository.PolicyRow row : rows) {
                boolean thisProject = row.projectId() != null && row.projectId() == projectId;
                if (thisProject && row.taskTypeId() != null) {
                    rung1.putIfAbsent(new SlaPolicyDtos.SlaCell.Key(row.taskTypeId(), row.level()), row);
                } else if (thisProject) {
                    rung2.putIfAbsent(row.level(), row);
                } else if (row.projectId() == null && row.taskTypeId() == null) {
                    rung3.putIfAbsent(row.level(), row);
                }
                // An org-wide row *with* a task type is deliberately unhandled.
                // The table permits one and §6's ladder has no rung for it, so
                // it answers nothing here — exactly as it answers nothing in
                // `SlaPolicyRepository`, whose rung-3 query also requires
                // `task_type_id IS NULL`. Silently ranking it somewhere would
                // make this screen disagree with the planned close date.
            }
        }

        SlaPolicyDtos.SlaCell resolve(SlaMatrixRepository.TaskTypeRow taskType,
                                      SlaMatrixRepository.LevelRow level) {

            SlaMatrixRepository.PolicyRow own = rung1.get(
                    new SlaPolicyDtos.SlaCell.Key(taskType.id(), level.code()));
            if (own != null) {
                return fromPolicy(taskType, level, own, SlaPolicyDtos.Source.PROJECT_TASK_TYPE);
            }

            SlaMatrixRepository.PolicyRow projectDefault = rung2.get(level.code());
            if (projectDefault != null) {
                return fromPolicy(taskType, level, projectDefault, SlaPolicyDtos.Source.PROJECT_LEVEL);
            }

            SlaMatrixRepository.PolicyRow orgDefault = rung3.get(level.code());
            if (orgDefault != null) {
                return fromPolicy(taskType, level, orgDefault, SlaPolicyDtos.Source.ORG_DEFAULT);
            }

            if (isPositive(level.defaultSlaHours())) {
                return fromDefault(taskType, level, SlaPolicyDtos.Source.PRIORITY_DEFAULT,
                        level.defaultSlaHours());
            }
            if (isPositive(taskType.defaultSlaHours())) {
                return fromDefault(taskType, level, SlaPolicyDtos.Source.TASK_TYPE_DEFAULT,
                        taskType.defaultSlaHours());
            }
            return fromDefault(taskType, level, SlaPolicyDtos.Source.NONE, null);
        }

        private static SlaPolicyDtos.SlaCell fromPolicy(SlaMatrixRepository.TaskTypeRow taskType,
                                                        SlaMatrixRepository.LevelRow level,
                                                        SlaMatrixRepository.PolicyRow row,
                                                        SlaPolicyDtos.Source source) {
            return new SlaPolicyDtos.SlaCell(
                    taskType.id(), taskType.code(), taskType.name(), level.code(),
                    row.responseHrs(), row.resolutionHrs(),
                    row.escalateToL1(), row.escalateToL2(),
                    source, source == SlaPolicyDtos.Source.PROJECT_TASK_TYPE);
        }

        /**
         * A master default carries no response target and no escalation flags.
         *
         * <p>The two masters hold one figure each. Inventing a response target
         * as a fraction of it would put a number on the screen that no
         * administrator ever typed — {@code SlaResolution.fromDefault} makes the
         * same point. The flags fall back to A-007's column defaults, which is
         * also what {@code EscalationPolicies.DEFAULT_POLICY} does when the
         * matrix says nothing: L1 on, L2 off, so waking somebody's manager's
         * manager stays a decision an organisation makes rather than one it
         * receives.
         */
        private static SlaPolicyDtos.SlaCell fromDefault(SlaMatrixRepository.TaskTypeRow taskType,
                                                         SlaMatrixRepository.LevelRow level,
                                                         SlaPolicyDtos.Source source,
                                                         BigDecimal resolutionHrs) {
            return new SlaPolicyDtos.SlaCell(
                    taskType.id(), taskType.code(), taskType.name(), level.code(),
                    null, resolutionHrs, true, false, source, false);
        }

        private static boolean isPositive(BigDecimal hours) {
            return hours != null && hours.signum() > 0;
        }
    }

    // ------------------------------------------------------------------
    // Refusals
    // ------------------------------------------------------------------

    /** 404 — the project in the path. */
    static class NoSuchProjectException extends RuntimeException {
    }

    /**
     * 400, keyed on the field, so the message lands on the cell rather than in a
     * banner. Same shape B-017's {@code MemberValidationException} uses and the
     * contract's {@code ValidationFailed} describes.
     */
    static class SlaValidationException extends RuntimeException {

        private final String field;

        SlaValidationException(String field, String message) {
            super(message);
            this.field = field;
        }

        String field() {
            return this.field;
        }
    }
}
