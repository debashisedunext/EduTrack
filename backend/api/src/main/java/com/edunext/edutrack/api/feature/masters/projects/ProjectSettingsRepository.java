package com.edunext.edutrack.api.feature.masters.projects;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * B-019 · the S-10 Settings tab's reads and writes.
 *
 * <p>Three settings across two tables: {@code projects.auto_assign_rule} and
 * {@code projects.mandatory_fields} on the row itself, and the allow-list in
 * {@code project_task_types}.
 *
 * <p><b>Named for the tab and not for a table</b>, like
 * {@link SlaMatrixRepository} and {@link ProjectTeamRepository} beside it —
 * Spring derives a bean name from the simple class name and a collision with a
 * {@code domain} repository takes out every {@code @SpringBootTest} in the
 * module with a message that names neither the feature nor the task. There is
 * no {@code ProjectSettingsRepository} in {@code domain} today; the naming holds
 * anyway, because the point of the convention is that nobody has to check.
 *
 * <h2>The JSON column is read and written as text</h2>
 *
 * <p>{@code mandatory_fields} is a MySQL {@code JSON} column and MySQL casts a
 * string literal into one, so a plain {@code String} parameter binds without a
 * driver-specific type. Jackson does the two conversions rather than string
 * concatenation: a field code is an enum name today and hand-rolled JSON is how
 * that stops being true the first time one of them needs escaping.
 */
@Repository
class ProjectSettingsRepository {

    private static final String PROJECT_EXISTS = "SELECT 1 FROM projects p WHERE p.id = ?";

    private static final String SETTINGS_ROW = """
            SELECT p.auto_assign_rule, p.mandatory_fields
              FROM projects p
             WHERE p.id = ?
            """;

    /**
     * Every active task type, plus any inactive one this project still allows.
     *
     * <p><b>The {@code LEFT JOIN} is what makes the second half work, and the
     * second half is the point.</b> The {@code PUT} is a wholesale replace
     * assembled from the rows this returns, so a retired task type that is
     * allowed and not rendered would be dropped by the next save through a
     * screen that never displayed it — exactly the deletion-by-omission
     * {@link SlaMatrixRepository#DEACTIVATE_OVERRIDES} guards against for
     * project-level defaults.
     *
     * <p>Ordered by the master's own {@code seq} so the checkbox list reads in
     * the order an administrator arranged the Task Type Master in, rather than
     * by id, which is insertion order and means nothing to anybody.
     */
    private static final String TASK_TYPES_FOR = """
            SELECT tt.id, tt.code, tt.name, tt.is_active,
                   ptt.project_id IS NOT NULL AS is_allowed
              FROM task_types tt
              LEFT JOIN project_task_types ptt
                     ON ptt.task_type_id = tt.id AND ptt.project_id = ?
             WHERE tt.is_active = 1 OR ptt.project_id IS NOT NULL
             ORDER BY tt.seq, tt.id
            """;

    private static final String UPDATE_SETTINGS = """
            UPDATE projects
               SET auto_assign_rule = ?, mandatory_fields = ?
             WHERE id = ?
            """;

    /**
     * Clear the allow-list ahead of the replace.
     *
     * <p><b>A real {@code DELETE}, where the SLA tab next door deactivates.</b>
     * The two are not inconsistent — the difference is what else points at the
     * row. {@code sla_policies} rows are referenced by {@code clients.sla_policy_id}
     * and their ids reach the wire through {@code PlannedCloseDatePreview}, so
     * removing one has consequences elsewhere and {@code is_active = 0} is what
     * the resolution ladder already reads. A {@code project_task_types} row is a
     * pure membership fact with no id, nothing referencing it and no
     * inheritance to fall through to; an {@code is_active} column on it would be
     * a second way of saying "not a member" that the next reader has to
     * remember to check.
     */
    private static final String DELETE_ALLOWED =
            "DELETE FROM project_task_types WHERE project_id = ?";

    private static final String INSERT_ALLOWED = """
            INSERT INTO project_task_types (project_id, task_type_id)
            VALUES (?, ?)
            """;

    private static final String TASK_TYPE_EXISTS =
            "SELECT 1 FROM task_types tt WHERE tt.id = ?";

    private static final TypeReference<List<String>> CODES = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    ProjectSettingsRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    boolean projectExists(long projectId) {
        return jdbc.sql(PROJECT_EXISTS).param(projectId)
                .query(Integer.class).optional().isPresent();
    }

    /**
     * Existence, not activity — {@link SlaMatrixRepository#taskTypeExists}'s
     * reason, unchanged. A project may keep allowing a task type an Admin has
     * since retired; refusing the id on write would make that row uneditable
     * and unremovable through the only screen that can see it.
     */
    boolean taskTypeExists(int taskTypeId) {
        return jdbc.sql(TASK_TYPE_EXISTS).param(taskTypeId)
                .query(Integer.class).optional().isPresent();
    }

    /**
     * The two columns on the project row.
     *
     * <p>{@code mandatory_fields} is decoded here rather than in the service so
     * that <b>{@code NULL} and {@code []} arrive as the same thing</b>: the
     * column is nullable, every row that predates this feature holds
     * {@code NULL}, and the screen writes {@code []} when the last box is
     * unticked. A caller that had to tell them apart would eventually get it
     * wrong in one of the two places that read it.
     *
     * <p>Codes are returned as raw strings, not as
     * {@link ProjectSettingsDtos.TicketField}. An unrecognised value stored by a
     * later version of the vocabulary — or by hand — must not make the whole
     * settings read throw; {@link ProjectSettingsService} decides what to do
     * with one, and can only do that if it is handed the string.
     */
    SettingsRow settings(long projectId) {
        return jdbc.sql(SETTINGS_ROW).param(projectId)
                .query((rs, rowNum) -> new SettingsRow(
                        rs.getString("auto_assign_rule"),
                        decode(rs.getString("mandatory_fields"))))
                .single();
    }

    List<TaskTypeRow> taskTypesFor(long projectId) {
        return jdbc.sql(TASK_TYPES_FOR).param(projectId)
                .query((rs, rowNum) -> new TaskTypeRow(
                        rs.getInt("id"),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getBoolean("is_active"),
                        rs.getBoolean("is_allowed")))
                .list();
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /**
     * <b>Plain positional {@code param}, never {@code param(value, sqlType)}.</b>
     * B-017's note on {@code ProjectTeamRepository.upsert} and B-018's on
     * {@code SlaMatrixRepository.upsertOverride} both apply: {@code param(int,
     * Object)} is index-then-value, so the typed overload silently binds a value
     * as a parameter index and the driver reports "parameter index out of
     * range" without naming the column or the call.
     */
    void updateSettings(long projectId, String autoAssignRule, List<String> mandatoryFields) {
        jdbc.sql(UPDATE_SETTINGS)
                .param(autoAssignRule)
                .param(encode(mandatoryFields))
                .param(projectId)
                .update();
    }

    /** @return how many allow-list rows were removed */
    int clearAllowedTaskTypes(long projectId) {
        return jdbc.sql(DELETE_ALLOWED).param(projectId).update();
    }

    void allowTaskType(long projectId, int taskTypeId) {
        jdbc.sql(INSERT_ALLOWED).param(projectId).param(taskTypeId).update();
    }

    // ------------------------------------------------------------------
    // JSON
    // ------------------------------------------------------------------

    /**
     * {@code null} for an empty list, so an unconfigured project keeps a
     * {@code NULL} column rather than acquiring a {@code []} that says the same
     * thing in a second way. The read collapses both, so nothing downstream can
     * tell — this is only about not rewriting every row of {@code projects} to
     * record a decision nobody made.
     */
    private String encode(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return null;
        }
        try {
            return json.writeValueAsString(codes);
        } catch (Exception e) {
            // Unreachable: the input is a list of enum names. Wrapped rather
            // than swallowed so that if it ever does happen the write fails
            // loudly instead of storing NULL and reading back as "requires
            // nothing", which is the one wrong answer that looks like a
            // legitimate state.
            throw new IllegalStateException("mandatory fields could not be encoded", e);
        }
    }

    private List<String> decode(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            List<String> codes = json.readValue(raw, CODES);
            return codes == null ? List.of() : codes;
        } catch (Exception e) {
            // A malformed document cannot make the Settings tab unopenable —
            // that would leave the only screen that can repair it behind the
            // failure. `ck_projects_mandatory_fields` makes this unreachable
            // through the database; a restore from a dump taken before that
            // constraint existed is the case it is here for.
            return List.of();
        }
    }

    // ------------------------------------------------------------------
    // Rows
    // ------------------------------------------------------------------

    /**
     * @param autoAssignRule   one of {@code ROUND_ROBIN|LEAST_LOADED|MANUAL},
     *                         constrained by {@code ck_projects_auto_assign_rule}
     * @param mandatoryFields  never null — see {@link #settings}
     */
    record SettingsRow(String autoAssignRule, List<String> mandatoryFields) {
    }

    /**
     * @param isAllowed derived from the {@code LEFT JOIN} — this project has a
     *                  {@code project_task_types} row for the type. Meaningless
     *                  on its own: when <em>no</em> row in the list is allowed,
     *                  the project is unrestricted and every active type is
     *                  permitted. {@link ProjectSettingsService} is where that
     *                  is resolved
     */
    record TaskTypeRow(int id, String code, String name, boolean isActive, boolean isAllowed) {
    }
}
