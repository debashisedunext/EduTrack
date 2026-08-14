package com.edunext.edutrack.api.feature.masters.projects;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

/**
 * B-018 · the S-10 SLA tab's reads and writes over {@code sla_policies}.
 *
 * <p><b>Named {@code SlaMatrixRepository} and not {@code SlaPolicyRepository}
 * for the reason {@link ProjectMasterRepository} and
 * {@link ProjectTeamRepository} both carry.</b> Spring derives a bean name from
 * the simple class name, and
 * {@link com.edunext.edutrack.domain.masters.SlaPolicyRepository} already holds
 * that one — a second would fail context startup with a
 * {@code BeanDefinitionOverrideException} that takes out every
 * {@code @SpringBootTest} in the module and names neither the feature nor the
 * task. Third time the lesson has applied; first time nobody lost an afternoon
 * to it.
 *
 * <h2>Three reads, not forty-four</h2>
 *
 * <p>The grid is eleven task types by four levels. Resolving each cell through
 * the §6 ladder in SQL is up to five statements a cell — two hundred and twenty
 * round trips for one page load. So this class reads the three <em>inputs</em>
 * the ladder needs, all of them small and all of them bounded by master data,
 * and {@link SlaMatrixService} walks the rungs in memory. The ladder is then
 * written down in Java twice — here and in C-012's
 * {@code PlannedCloseDateService} — which is a real cost, paid deliberately and
 * covered by {@code SlaLadderAgreementTest} rather than by hoping.
 *
 * <h2>This is the second writer of this table</h2>
 *
 * <p>{@code ReferenceDataFixture} seeds four org-wide rows under the
 * {@code fixtures} profile. The two cannot collide: those carry
 * {@code project_id IS NULL} and everything written here carries a project id
 * and a task type, and {@link #DEACTIVATE_OVERRIDES} is scoped to exactly that
 * pair.
 */
@Repository
class SlaMatrixRepository {

    private static final String PROJECT_EXISTS = "SELECT 1 FROM projects p WHERE p.id = ?";

    /**
     * The grid's rows.
     *
     * <p>Active only, in {@code seq} order — this is a picker, not a history
     * grid, and a deactivated task type is one an administrator has retired. It
     * differs from B-064's rule for {@code /masters/modules} ("every row
     * including deactivated ones") and the difference is the screen: that one
     * has to render the name of a module an old ticket was raised against, and
     * this one is asking what a <em>new</em> ticket would be measured by.
     *
     * <p>{@code default_sla_hours} comes along because it is rung 5 of the
     * ladder — see {@link SlaMatrixService}.
     */
    private static final String ACTIVE_TASK_TYPES = """
            SELECT tt.id, tt.code, tt.name, tt.default_sla_hours
              FROM task_types tt
             WHERE tt.is_active = 1
             ORDER BY tt.seq, tt.id
            """;

    /**
     * The grid's columns, and rung 4.
     *
     * <p>Read from {@code priorities} rather than hardcoded to the four of
     * {@code Level}, because S-12 lets an Admin add a level without a release —
     * the same reason {@code tickets.level} is a {@code VARCHAR} and not an FK.
     * A fifth level added tomorrow gets a column here on the next page load.
     */
    private static final String ACTIVE_LEVELS = """
            SELECT p.code, p.default_sla_hours
              FROM priorities p
             WHERE p.is_active = 1
             ORDER BY p.seq, p.id
            """;

    /**
     * Every policy row that could answer a cell of <em>this</em> project's grid:
     * its own overrides, its project-level defaults, and the org-wide ones.
     *
     * <p>{@code is_active = 1} throughout — a deactivated policy is not a silent
     * fall-through to a broader one, it is a policy somebody switched off, and
     * the next most specific active row is the right answer. Stream D's
     * {@code EscalationPolicies} says the same thing about the same table.
     *
     * <p>Ordered by id so that rungs 2 and 3, which {@code uq_sla_policies}
     * cannot make unique (MySQL treats NULLs in a unique index as distinct),
     * resolve to the same row on every read rather than to whatever the
     * optimiser hands back today. {@code SlaPolicyRepository}'s javadoc makes
     * the same concession, and tightening it is a schema change and Stream A's
     * call.
     */
    private static final String POLICIES_FOR = """
            SELECT sp.id, sp.project_id, sp.task_type_id, sp.level,
                   sp.response_hrs, sp.resolution_hrs,
                   sp.escalate_to_l1, sp.escalate_to_l2
              FROM sla_policies sp
             WHERE sp.is_active = 1
               AND (sp.project_id = ? OR sp.project_id IS NULL)
             ORDER BY sp.id
            """;

    /**
     * Clear this project's overrides ahead of the replace.
     *
     * <p><b>Deactivate, never delete.</b> {@code clients.sla_policy_id} is a
     * foreign key into this table with no cascade, so a {@code DELETE} either
     * fails on a constraint naming a MySQL index or — worse, if somebody
     * "fixes" it with a cascade — silently unsets a client's SLA policy from a
     * project screen. {@code PlannedCloseDatePreview.slaPolicyId} also puts row
     * ids on the wire. And {@code is_active = 0} is what the resolution ladder
     * already reads, so a removed override falls through to the next rung
     * instead of leaving a hole.
     *
     * <p><b>{@code task_type_id IS NOT NULL} is the important half of the
     * predicate.</b> A project-level default — rung 2, one row covering every
     * task type at a level — has no cell in a task type × level grid, so a
     * replace that dropped it would delete configuration through a screen that
     * never displayed it. B-007's corpus has one on PAY, which is what makes
     * this a live case rather than a hypothetical.
     */
    private static final String DEACTIVATE_OVERRIDES = """
            UPDATE sla_policies
               SET is_active = 0
             WHERE project_id = ? AND task_type_id IS NOT NULL AND is_active = 1
            """;

    /**
     * Write one override, or bring back one that a previous replace deactivated.
     *
     * <p>{@code uq_sla_policies (project_id, task_type_id, level)} makes this
     * the reactivate path as well as the insert path, which it has to be: rows
     * are deactivated rather than deleted, so a plain {@code INSERT} would
     * collide the first time anybody restored a cell they had cleared, and the
     * collision would read as a bug in the save button. Reactivating also keeps
     * the row id, which is the point of not deleting it.
     */
    private static final String UPSERT_OVERRIDE = """
            INSERT INTO sla_policies (project_id, task_type_id, level, response_hrs,
                                      resolution_hrs, escalate_to_l1, escalate_to_l2, is_active)
            VALUES (?, ?, ?, ?, ?, ?, ?, 1)
            ON DUPLICATE KEY UPDATE response_hrs   = VALUES(response_hrs),
                                    resolution_hrs = VALUES(resolution_hrs),
                                    escalate_to_l1 = VALUES(escalate_to_l1),
                                    escalate_to_l2 = VALUES(escalate_to_l2),
                                    is_active      = 1
            """;

    private static final String LEVEL_EXISTS =
            "SELECT 1 FROM priorities p WHERE p.code = ?";

    private static final String TASK_TYPE_EXISTS =
            "SELECT 1 FROM task_types tt WHERE tt.id = ?";

    private final JdbcClient jdbc;

    SlaMatrixRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    boolean projectExists(long projectId) {
        return jdbc.sql(PROJECT_EXISTS).param(projectId)
                .query(Integer.class).optional().isPresent();
    }

    /**
     * Existence, not activity.
     *
     * <p>A deactivated task type is refused nowhere: an override on one is
     * configuration somebody entered while it was live, and rejecting it would
     * make the row uneditable and undeletable through the only screen that can
     * see it. The <em>grid</em> only offers active types, which is the place
     * that decision belongs.
     */
    boolean taskTypeExists(int taskTypeId) {
        return jdbc.sql(TASK_TYPE_EXISTS).param(taskTypeId)
                .query(Integer.class).optional().isPresent();
    }

    boolean levelExists(String level) {
        return jdbc.sql(LEVEL_EXISTS).param(level)
                .query(Integer.class).optional().isPresent();
    }

    List<TaskTypeRow> activeTaskTypes() {
        return jdbc.sql(ACTIVE_TASK_TYPES)
                .query((rs, rowNum) -> new TaskTypeRow(
                        rs.getInt("id"),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getBigDecimal("default_sla_hours")))
                .list();
    }

    List<LevelRow> activeLevels() {
        return jdbc.sql(ACTIVE_LEVELS)
                .query((rs, rowNum) -> new LevelRow(
                        rs.getString("code"),
                        rs.getBigDecimal("default_sla_hours")))
                .list();
    }

    List<PolicyRow> policiesFor(long projectId) {
        return jdbc.sql(POLICIES_FOR).param(projectId)
                .query((rs, rowNum) -> new PolicyRow(
                        rs.getLong("id"),
                        rs.getObject("project_id", Long.class),
                        rs.getObject("task_type_id", Integer.class),
                        rs.getString("level"),
                        rs.getBigDecimal("response_hrs"),
                        rs.getBigDecimal("resolution_hrs"),
                        rs.getBoolean("escalate_to_l1"),
                        rs.getBoolean("escalate_to_l2")))
                .list();
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /** @return how many overrides were switched off */
    int deactivateOverrides(long projectId) {
        return jdbc.sql(DEACTIVATE_OVERRIDES).param(projectId).update();
    }

    /**
     * <b>Plain positional {@code param}, never {@code param(value, sqlType)}.</b>
     * B-017's note on {@code ProjectTeamRepository.upsert} applies unchanged:
     * {@code param(int, Object)} is index-then-value, so the typed overload
     * silently binds a value as a parameter index and the driver reports
     * "parameter index out of range" without naming the column or the call.
     */
    void upsertOverride(long projectId, int taskTypeId, String level,
                        BigDecimal responseHrs, BigDecimal resolutionHrs,
                        boolean escalateToL1, boolean escalateToL2) {

        jdbc.sql(UPSERT_OVERRIDE)
                .param(projectId)
                .param(taskTypeId)
                .param(level)
                .param(responseHrs)
                .param(resolutionHrs)
                .param(escalateToL1)
                .param(escalateToL2)
                .update();
    }

    // ------------------------------------------------------------------
    // Rows
    // ------------------------------------------------------------------

    /** @param defaultSlaHours rung 5 of the ladder — the S-11 master's default */
    record TaskTypeRow(int id, String code, String name, BigDecimal defaultSlaHours) {
    }

    /** @param defaultSlaHours rung 4 of the ladder — the S-12 master's default */
    record LevelRow(String code, BigDecimal defaultSlaHours) {
    }

    /**
     * One {@code sla_policies} row, layered exactly as the table is.
     *
     * @param projectId  null is the org-wide default
     * @param taskTypeId null means "any task type"
     */
    record PolicyRow(long id, Long projectId, Integer taskTypeId, String level,
                     BigDecimal responseHrs, BigDecimal resolutionHrs,
                     boolean escalateToL1, boolean escalateToL2) {
    }
}
