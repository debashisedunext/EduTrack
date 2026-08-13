package com.edunext.edutrack.api.feature.masters.projects;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * B-017 · the S-10 Team tab's reads and writes over {@code project_members}.
 *
 * <p><b>Named {@code ProjectTeamRepository} for the reason
 * {@link ProjectMasterRepository}'s javadoc gives at length.</b> Spring derives
 * a bean name from the simple class name, and
 * {@link com.edunext.edutrack.domain.identity.ProjectMemberRepository} already
 * holds that one — a second would fail context startup with a
 * {@code BeanDefinitionOverrideException} that takes out every
 * {@code @SpringBootTest} in the module and names neither the feature nor the
 * task. B-016 lost an afternoon to exactly this with {@code ProjectRepository};
 * this is that lesson applied rather than relearned.
 *
 * <h2>This is the second writer of this table</h2>
 *
 * <p>{@code ResourceWriteRepository} (B-011) writes the same rows from the
 * resource form's side, and the two must not fight:
 *
 * <ul>
 *   <li>Its upsert names {@code role_in_project} and {@code is_active} and
 *       <b>not</b> {@code allocation_pct}, so a resource-form save preserves an
 *       allocation this screen set. {@link #UPDATE_MEMBER} is the mirror image —
 *       it never touches {@code is_active}, so an edit here cannot resurrect a
 *       membership the resource form removed.</li>
 *   <li>Both deactivate rather than delete, so
 *       {@code uq_project_members (project_id, user_id)} means there is one row
 *       per pair forever and every "add" is an upsert.</li>
 * </ul>
 */
@Repository
class ProjectTeamRepository {

    /**
     * The roster, with each member's open ticket count <b>on this project</b>.
     *
     * <p>One statement rather than the roster-then-counts pair B-010 uses on the
     * resource grid, because that grid pages and this list does not: the derived
     * table is already restricted to one project, so it is a small aggregate
     * joined to a small set rather than an unbounded one.
     *
     * <p><b>"Open" is {@code statuses.is_open}, never a literal
     * {@code <> 'CLOSED'}.</b> The status vocabulary is master data an Admin
     * extends through S-13, and a hardcoded list would silently miscount the
     * first status added after this shipped — which is the note
     * {@code ResourceRepository.OPEN_TICKETS_FOR} already carries, and the
     * count here decides whether a removal is refused.
     *
     * <p>Ordered by name with {@code user_id} as the tiebreak: two people called
     * Priya Sharma is a normal thing for an organisation to contain, and without
     * the second key their relative order is whatever the optimiser felt like
     * that run.
     */
    private static final String ROSTER = """
            SELECT pm.user_id, pm.role_in_project, pm.allocation_pct, pm.added_at,
                   u.full_name, u.email, u.is_active,
                   r.code AS role_code,
                   COALESCE(oc.open_count, 0) AS open_count
              FROM project_members pm
              JOIN users u ON u.id = pm.user_id
              JOIN roles r ON r.id = u.role_id
              LEFT JOIN (
                    SELECT t.assigned_to, COUNT(*) AS open_count
                      FROM tickets t
                      JOIN statuses s ON s.code = t.status
                     WHERE t.project_id = ? AND s.is_open = 1
                     GROUP BY t.assigned_to
              ) oc ON oc.assigned_to = pm.user_id
             WHERE pm.project_id = ? AND pm.is_active = 1
             ORDER BY u.full_name, pm.user_id
            """;

    /** One roster row, for the two writes to answer with. */
    private static final String FIND_MEMBER = """
            SELECT pm.user_id, pm.role_in_project, pm.allocation_pct, pm.added_at,
                   u.full_name, u.email, u.is_active,
                   r.code AS role_code,
                   (SELECT COUNT(*)
                      FROM tickets t
                      JOIN statuses s ON s.code = t.status
                     WHERE t.project_id = pm.project_id
                       AND t.assigned_to = pm.user_id
                       AND s.is_open = 1) AS open_count
              FROM project_members pm
              JOIN users u ON u.id = pm.user_id
              JOIN roles r ON r.id = u.role_id
             WHERE pm.project_id = ? AND pm.user_id = ? AND pm.is_active = 1
            """;

    /**
     * Does a membership row exist at all, and is it live.
     *
     * <p>Distinct from {@link #FIND_MEMBER}, which filters to active rows: the
     * add path has to tell "already on the team" (409) from "was removed and is
     * coming back" (reactivate), and a query that cannot see the deactivated row
     * reports both as "not a member".
     */
    private static final String MEMBERSHIP_STATE = """
            SELECT pm.is_active FROM project_members pm
             WHERE pm.project_id = ? AND pm.user_id = ?
            """;

    private static final String PROJECT_EXISTS = "SELECT 1 FROM projects p WHERE p.id = ?";

    /**
     * The candidate: does the resource exist, and can they be reached.
     *
     * <p>The role comes back so a refusal can name it, not so the service can
     * gate on it — {@link ProjectService#validateManager} makes the same point
     * about the project manager, and for the same reason: a hardcoded role set
     * is what B-015 removed from {@code ResourceController}.
     */
    private static final String FIND_CANDIDATE = """
            SELECT u.id, u.full_name, u.is_active, r.code AS role_code
              FROM users u
              JOIN roles r ON r.id = u.role_id
             WHERE u.id = ?
            """;

    /**
     * Insert, or bring a removed member back.
     *
     * <p>{@code uq_project_members} makes this the reactivate path as well as
     * the insert path, which it has to be: a removal deactivates the row, so a
     * plain {@code INSERT} would collide the first time anybody rejoined a
     * project and the collision would read as a bug in the remove button.
     *
     * <p>{@code allocation_pct} <b>is</b> named here, unlike in B-011's upsert,
     * because this screen has an input for it and the caller is stating a value.
     * That is the difference between the two writers: one is editing the field
     * and the other has never heard of it.
     */
    private static final String UPSERT = """
            INSERT INTO project_members (project_id, user_id, role_in_project, allocation_pct, is_active)
            VALUES (?, ?, ?, ?, 1)
            ON DUPLICATE KEY UPDATE role_in_project = VALUES(role_in_project),
                                    allocation_pct  = VALUES(allocation_pct),
                                    is_active       = 1
            """;

    /**
     * The inline edit. Two columns, and {@code is_active} is deliberately not
     * one of them.
     *
     * <p>An edit is not a re-add: if the resource form removed somebody while
     * this tab was open, a patch that also set {@code is_active = 1} would put
     * them silently back on the team as a side effect of changing a percentage.
     * The {@code AND is_active = 1} in the predicate is what turns that into the
     * 404 it should be.
     */
    private static final String UPDATE_MEMBER = """
            UPDATE project_members
               SET role_in_project = ?, allocation_pct = ?
             WHERE project_id = ? AND user_id = ? AND is_active = 1
            """;

    /**
     * Deactivate, never delete — B-011's reasoning, unchanged.
     *
     * <p>The row is the record that this person was on the project while the
     * tickets assigned to them then were being worked. A {@code DELETE} would
     * make historical project attribution depend on current team composition.
     */
    private static final String DEACTIVATE = """
            UPDATE project_members SET is_active = 0
             WHERE project_id = ? AND user_id = ? AND is_active = 1
            """;

    private final JdbcClient jdbc;

    ProjectTeamRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    boolean projectExists(long projectId) {
        return jdbc.sql(PROJECT_EXISTS).param(projectId)
                .query(Integer.class).optional().isPresent();
    }

    List<ProjectMemberDtos.TeamMember> roster(long projectId) {
        return jdbc.sql(ROSTER).param(projectId).param(projectId)
                .query(ProjectTeamRepository::toMember)
                .list();
    }

    Optional<ProjectMemberDtos.TeamMember> findMember(long projectId, long userId) {
        return jdbc.sql(FIND_MEMBER).param(projectId).param(userId)
                .query(ProjectTeamRepository::toMember)
                .optional();
    }

    /**
     * @return empty when there has never been a membership row for this pair;
     *         {@code true} when one exists and is live; {@code false} when one
     *         exists and was removed
     */
    Optional<Boolean> membershipState(long projectId, long userId) {
        return jdbc.sql(MEMBERSHIP_STATE).param(projectId).param(userId)
                .query(Boolean.class).optional();
    }

    Optional<Candidate> findCandidate(long userId) {
        return jdbc.sql(FIND_CANDIDATE).param(userId)
                .query((rs, rowNum) -> new Candidate(
                        rs.getLong("id"),
                        rs.getString("full_name"),
                        rs.getString("role_code"),
                        rs.getBoolean("is_active")))
                .optional();
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /**
     * <b>Plain positional {@code param}, never {@code param(value, sqlType)}.</b>
     *
     * <p>{@code JdbcClient.StatementSpec} overloads {@code param} on
     * {@code (int, Object)} as index-then-value as well as on
     * {@code (Object, int)}, and an {@code Integer} allocation binds to the
     * first: {@code param(40, Types.SMALLINT)} reads as "parameter 40". The
     * driver reports it as "parameter index out of range", or for an allocation
     * of zero as "invalid JDBC index: needs to start at 1" — neither of which
     * names the column or the call. Found the first time this ran against real
     * MySQL.
     */
    void upsert(long projectId, long userId, String projectRole, Integer allocationPct) {
        jdbc.sql(UPSERT)
                .param(projectId)
                .param(userId)
                .param(projectRole)
                .param(allocationPct)
                .update();
    }

    /** @return true when a live membership row was changed */
    boolean update(long projectId, long userId, String projectRole, Integer allocationPct) {
        return jdbc.sql(UPDATE_MEMBER)
                .param(projectRole)
                .param(allocationPct)
                .param(projectId)
                .param(userId)
                .update() > 0;
    }

    /** @return true when a live membership row was deactivated */
    boolean deactivate(long projectId, long userId) {
        return jdbc.sql(DEACTIVATE).param(projectId).param(userId).update() > 0;
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * {@code getObject(…, Integer.class)} rather than {@code getInt}, because
     * {@code getInt} answers 0 for a SQL NULL and 0% is a real allocation
     * somebody may have entered — "not stated" and "no capacity committed" are
     * different facts and the tab renders them differently.
     */
    private static ProjectMemberDtos.TeamMember toMember(ResultSet rs, int rowNum) throws SQLException {
        return new ProjectMemberDtos.TeamMember(
                rs.getLong("user_id"),
                rs.getString("full_name"),
                rs.getString("email"),
                rs.getString("role_code"),
                rs.getString("role_in_project"),
                rs.getObject("allocation_pct", Integer.class),
                rs.getBoolean("is_active"),
                rs.getInt("open_count"),
                toInstant(rs.getObject("added_at", LocalDateTime.class)));
    }

    /**
     * {@code DATETIME(6)} holds UTC with no offset, so the driver hands back a
     * {@code LocalDateTime} and it is stamped UTC here.
     *
     * <p>Not {@code getObject(…, Instant.class)}, which compiles, reads better
     * and throws {@code Conversion not supported for type java.time.Instant} at
     * runtime — Connector/J will not invent an offset for a column that carries
     * none. Same conversion and the same reason as
     * {@code ResourceRepository.toInstant}; CLAUDE.md's "UTC everywhere in
     * storage, timezone applied in the presentation layer" is what makes the
     * stamp correct rather than a guess.
     */
    private static Instant toInstant(LocalDateTime utc) {
        return utc == null ? null : utc.toInstant(ZoneOffset.UTC);
    }

    record Candidate(long id, String displayName, String roleCode, boolean isActive) {
    }
}
