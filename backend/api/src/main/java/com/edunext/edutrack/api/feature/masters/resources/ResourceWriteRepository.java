package com.edunext.edutrack.api.feature.masters.resources;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * B-011 · the write side of the Resource Master (S-08), and the detail read the
 * form loads.
 *
 * <p>Separate from {@link ResourceRepository} rather than added to it. That
 * class is a grid projection — three queries that between them answer "who is
 * on this page"; this one is a row's full state and the statements that change
 * it. Merging them would put an {@code INSERT} into the class the export
 * streams five thousand rows through, and would make the grid's careful
 * ten-column projection sit next to a {@code SELECT} that reads everything.
 *
 * <p>{@link JdbcClient}, for the reason {@link ResourceRepository} states: the
 * B-005 entities would hang a lazy {@code role} proxy off the row and fetch a
 * password hash this screen must never see.
 */
@Repository
class ResourceWriteRepository {

    /**
     * The form's read.
     *
     * <p>Reads {@code users} whole apart from the columns that are nobody's
     * business on a master screen — {@code password_hash},
     * {@code failed_attempts}, {@code locked_until}, {@code password_changed_at}
     * and A-029's {@code totp_secret}, {@code totp_enabled} and
     * {@code totp_confirmed_at}.
     *
     * <p><b>Listing the columns rather than {@code SELECT *} is what keeps that
     * true, and it has already earned its keep once.</b> A-029 added the three
     * TOTP columns to {@code users} while B-011 was in flight — including a
     * shared secret — and this projection needed no change to keep them out of a
     * response the whole organisation may read. A {@code SELECT *} would have
     * started returning an authenticator seed on the next rebase, with nothing
     * failing to say so.
     */
    private static final String FIND_DETAIL = """
            SELECT u.id, u.emp_code, u.username, u.email, u.full_name, u.mobile,
                   r.code AS role_code,
                   u.department, u.designation, u.location,
                   u.reporting_manager_id, m.full_name AS manager_name, mr.code AS manager_role,
                   u.is_active, u.must_change_password,
                   u.date_of_joining, u.avatar_url, u.timezone,
                   u.daily_capacity_hrs, u.weekly_off, u.skills,
                   u.last_login_at, u.created_at
              FROM users u
              JOIN roles r  ON r.id  = u.role_id
              LEFT JOIN users m  ON m.id  = u.reporting_manager_id
              LEFT JOIN roles mr ON mr.id = m.role_id
             WHERE u.id = ?
            """;

    /**
     * Memberships with their per-project role, for the form.
     *
     * <p>Distinct from {@code ResourceRepository.PROJECTS_FOR}, which serves the
     * grid: that one is batched across a page and returns labels, this one is
     * one resource and returns the role. Same table, different question.
     */
    private static final String ASSIGNMENTS_FOR = """
            SELECT pm.project_id, pm.role_in_project,
                   p.project_code, p.name, p.colour_tag
              FROM project_members pm
              JOIN projects p ON p.id = pm.project_id
             WHERE pm.user_id = ? AND pm.is_active = 1
             ORDER BY p.project_code
            """;

    /**
     * Nineteen columns, nineteen placeholders.
     *
     * <p>{@code must_change_password} is bound rather than written as a literal
     * {@code 1}, even though {@link ResourceWriteService} only ever passes true.
     * A literal in the middle of a bind list is a placeholder count that no
     * longer matches the parameter count, and the driver reports it as
     * "parameter index out of range" against the column at the end — nowhere
     * near the one that is wrong.
     */
    private static final String INSERT = """
            INSERT INTO users (emp_code, username, email, password_hash, full_name, mobile,
                               role_id, reporting_manager_id, department, designation, location,
                               daily_capacity_hrs, timezone, is_active, must_change_password,
                               date_of_joining, avatar_url, weekly_off, skills)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    /**
     * Which of the three unique fields a candidate collides with, in one query.
     *
     * <p>Three separate {@code SELECT}s would answer the same question and would
     * report only the first collision, so an admin fixing a duplicate username
     * would submit again and discover the duplicate email. One pass names all
     * three at once, which is one round of correction rather than three.
     *
     * <p>{@code ?} for the excluded id rather than a conditional fragment: on a
     * create there is no id to exclude and {@code -1} excludes nothing, which is
     * simpler than two versions of the statement.
     */
    private static final String FIND_CONFLICTS = """
            SELECT u.username, u.email, u.emp_code
              FROM users u
             WHERE u.id <> ?
               AND (u.username = ? OR u.email = ? OR u.emp_code = ?)
            """;

    private static final String FIND_ROLE_ID = "SELECT r.id FROM roles r WHERE r.code = ?";

    private static final String EXISTS_USER = "SELECT 1 FROM users u WHERE u.id = ?";

    /**
     * B-012 · the reporting line above one resource, innermost first.
     *
     * <p>Walked in the database rather than in Java. The alternative is one
     * {@code SELECT} per level from a loop in the service, which is six round
     * trips to save a resource six levels down and gets slower the deeper the
     * organisation is — for a question the server can answer in one pass over
     * rows it already has in the buffer pool.
     *
     * <p><b>The depth bound is not a tuning knob, it is what makes this
     * terminate.</b> A recursive CTE over a table that already contains a cycle
     * has no base case: it recurses until {@code cte_max_recursion_depth}
     * (1,000 by default) and then fails the statement with error 3636, which
     * arrives as a 500 on somebody's ordinary save. This guard is the only
     * writer that refuses cycles, so it cannot assume the data has none —
     * anything that predates it, or a hand-run {@code UPDATE}, can. Binding the
     * cap means the walk stops on its own terms and the caller gets to decide
     * what a chain that did not terminate means.
     *
     * <p>{@code depth} comes back so the caller can tell "this organisation is
     * genuinely 64 deep" from "this chain never ended" — they are the same row
     * count and only one of them is a refusal the admin can act on.
     */
    private static final String MANAGER_CHAIN = """
            WITH RECURSIVE chain (id, reporting_manager_id, depth) AS (
                SELECT u.id, u.reporting_manager_id, 1
                  FROM users u
                 WHERE u.id = ?
                UNION ALL
                SELECT u.id, u.reporting_manager_id, c.depth + 1
                  FROM users u
                  JOIN chain c ON u.id = c.reporting_manager_id
                 WHERE c.depth < ?
            )
            SELECT id FROM chain ORDER BY depth
            """;

    private static final String EXISTING_PROJECT_IDS =
            "SELECT p.id FROM projects p WHERE p.id IN (%s)";

    /**
     * Memberships are deactivated, never deleted.
     *
     * <p>Same reason a resource is deactivated and not deleted: the row is the
     * record that somebody was on that project, and a ticket assigned to them
     * during that time still points at both. A {@code DELETE} here would make
     * historical project attribution depend on current team composition.
     */
    private static final String DEACTIVATE_MEMBERSHIPS = """
            UPDATE project_members SET is_active = 0
             WHERE user_id = ? AND is_active = 1
            """;

    /**
     * {@code uq_project_members (project_id, user_id)} makes this the reactivate
     * path as well as the insert path — somebody rejoining a project they left
     * updates the row that is already there rather than colliding with it.
     */
    private static final String UPSERT_MEMBERSHIP = """
            INSERT INTO project_members (project_id, user_id, role_in_project, is_active)
            VALUES (?, ?, ?, 1)
            ON DUPLICATE KEY UPDATE role_in_project = VALUES(role_in_project), is_active = 1
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    ResourceWriteRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    Optional<ResourceDtos.ResourceDetail> findDetail(long userId, int openTicketCount) {
        return jdbc.sql(FIND_DETAIL).param(userId)
                .query((rs, rowNum) -> {
                    List<AssignmentRow> memberships = assignmentsFor(userId);

                    ResourceDtos.UserRef manager = null;
                    long managerId = rs.getLong("reporting_manager_id");
                    if (!rs.wasNull()) {
                        manager = new ResourceDtos.UserRef(managerId,
                                rs.getString("manager_name"), rs.getString("manager_role"));
                    }

                    return new ResourceDtos.ResourceDetail(
                            rs.getLong("id"),
                            rs.getString("full_name"),
                            rs.getString("role_code"),
                            rs.getString("username"),
                            rs.getString("email"),
                            rs.getString("emp_code"),
                            rs.getString("department"),
                            rs.getString("designation"),
                            manager,
                            memberships.stream().map(a -> a.ref().id()).toList(),
                            memberships.stream().map(AssignmentRow::ref).toList(),
                            rs.getBoolean("is_active"),
                            openTicketCount,
                            toInstant(rs.getObject("last_login_at", LocalDateTime.class)),
                            toInstant(rs.getObject("created_at", LocalDateTime.class)),
                            rs.getString("mobile"),
                            rs.getString("avatar_url"),
                            rs.getObject("date_of_joining", LocalDate.class),
                            rs.getString("location"),
                            rs.getString("timezone"),
                            rs.getBigDecimal("daily_capacity_hrs"),
                            readIntArray(rs.getString("weekly_off")),
                            // An absent skills array reads as an empty list, not
                            // null: "no skills recorded" and "skills not
                            // applicable" are the same thing here, and the
                            // contract types it as a plain array.
                            orEmpty(readStringArray(rs.getString("skills"))),
                            memberships.stream().map(AssignmentRow::assignment).toList(),
                            rs.getBoolean("must_change_password"));
                })
                .optional();
    }

    private List<AssignmentRow> assignmentsFor(long userId) {
        return jdbc.sql(ASSIGNMENTS_FOR).param(userId)
                .query((rs, rowNum) -> new AssignmentRow(
                        new ResourceDtos.ProjectRef(
                                rs.getLong("project_id"),
                                rs.getString("project_code"),
                                rs.getString("name"),
                                rs.getString("colour_tag")),
                        new ResourceDtos.ProjectAssignment(
                                rs.getLong("project_id"),
                                rs.getString("role_in_project"))))
                .list();
    }

    /** @return the codes that collide, so the caller can name all of them at once */
    Conflicts findConflicts(String username, String email, String empCode, Long excludeId) {
        List<Conflict> rows = jdbc.sql(FIND_CONFLICTS)
                .param(excludeId == null ? -1L : excludeId)
                .param(username).param(email).param(empCode)
                .query((rs, rowNum) -> new Conflict(
                        rs.getString("username"), rs.getString("email"), rs.getString("emp_code")))
                .list();

        boolean usernameTaken = false;
        boolean emailTaken = false;
        boolean empCodeTaken = false;
        for (Conflict row : rows) {
            // Compared in Java rather than by three COUNT expressions in SQL so
            // that the comparison uses the same collation the UNIQUE index does
            // — utf8mb4_0900_ai_ci, which is case-insensitive. A Java
            // equalsIgnoreCase would agree with it for ASCII and disagree for
            // accented characters; equality against what the database returned
            // for a case-insensitive predicate cannot disagree with it at all.
            usernameTaken |= username.equalsIgnoreCase(row.username());
            emailTaken |= email.equalsIgnoreCase(row.email());
            empCodeTaken |= empCode.equalsIgnoreCase(row.empCode());
        }
        return new Conflicts(usernameTaken, emailTaken, empCodeTaken);
    }

    Optional<Integer> findRoleId(String roleCode) {
        return jdbc.sql(FIND_ROLE_ID).param(roleCode).query(Integer.class).optional();
    }

    boolean userExists(long userId) {
        return jdbc.sql(EXISTS_USER).param(userId).query(Integer.class).optional().isPresent();
    }

    /**
     * B-012 · {@code startUserId} and everybody they report to, upward.
     *
     * <p>The first element is {@code startUserId} itself, so an empty list means
     * there is no such resource — which is why {@link ResourceWriteService} can
     * use this in place of an existence check rather than in addition to one.
     *
     * @param maxDepth how many levels to return before giving up. A list this
     *                 long has <b>not</b> been walked to a root, and the caller
     *                 must not read "the edited resource is not in it" as
     *                 "there is no cycle" — see {@link #MANAGER_CHAIN}.
     * @return ids from {@code startUserId} outward, innermost first
     */
    List<Long> managerChain(long startUserId, int maxDepth) {
        return jdbc.sql(MANAGER_CHAIN).param(startUserId).param(maxDepth).query(Long.class).list();
    }

    /** @return the subset of {@code projectIds} that exist, so the caller can name the rest */
    Set<Long> existingProjectIds(Collection<Long> projectIds) {
        if (projectIds.isEmpty()) {
            return Set.of();
        }
        List<Long> ids = List.copyOf(new LinkedHashSet<>(projectIds));
        JdbcClient.StatementSpec spec = jdbc.sql(
                EXISTING_PROJECT_IDS.formatted(String.join(", ", java.util.Collections.nCopies(ids.size(), "?"))));
        for (Long id : ids) {
            spec = spec.param(id);
        }
        return new LinkedHashSet<>(spec.query(Long.class).list());
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /** @return the generated id */
    long insert(NewResource resource) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql(INSERT)
                .param(resource.empCode())
                .param(resource.username())
                .param(resource.email())
                .param(resource.passwordHash())
                .param(resource.fullName())
                .param(resource.mobile())
                .param(resource.roleId())
                .param(resource.reportingManagerId())
                .param(resource.department())
                .param(resource.designation())
                .param(resource.location())
                .param(resource.dailyCapacityHrs())
                .param(resource.timezone())
                .param(resource.isActive())
                .param(resource.mustChangePassword())
                .param(resource.dateOfJoining())
                .param(resource.avatarUrl())
                .param(writeJson(resource.weeklyOff()))
                .param(writeJson(resource.skills()))
                .update(keys);

        Number id = keys.getKey();
        if (id == null) {
            throw new IllegalStateException("users INSERT returned no generated key");
        }
        return id.longValue();
    }

    /**
     * Sets exactly the columns named in {@code changes}, and no others.
     *
     * <p>A fixed {@code UPDATE} listing every column would rewrite the ones the
     * caller did not send with the values it read a moment ago — which is
     * exactly the lost update {@code If-Match} exists to prevent, reintroduced
     * one column at a time and invisible to the precondition because the row's
     * tag was correct when the request was built.
     *
     * @param changes column name to bound value, insertion-ordered
     * @return true when a row moved
     */
    boolean update(long userId, LinkedHashMap<String, Object> changes) {
        if (changes.isEmpty()) {
            return userExists(userId);
        }
        // Keys are constants from ResourceWriteService, never caller input —
        // the values are always bound. Held by
        // ResourceWriteServiceTest.updateBindsEveryValue.
        String assignments = String.join(", ", changes.keySet().stream().map(c -> c + " = ?").toList());

        JdbcClient.StatementSpec spec = jdbc.sql("UPDATE users SET " + assignments + " WHERE id = ?");
        for (Object value : changes.values()) {
            spec = spec.param(value);
        }
        return spec.param(userId).update() > 0;
    }

    /**
     * Makes {@code assignments} the resource's complete membership set.
     *
     * <p>Deactivate-then-upsert rather than a diff. The diff is three queries
     * and a set comparison to save writing a handful of rows nobody is
     * contending for, and it gets the "same project, changed role" case wrong
     * unless it is written carefully — which is a bug that looks like nothing
     * happened.
     */
    void replaceMemberships(long userId, List<ResourceDtos.ProjectAssignment> assignments) {
        jdbc.sql(DEACTIVATE_MEMBERSHIPS).param(userId).update();

        for (ResourceDtos.ProjectAssignment assignment : assignments) {
            jdbc.sql(UPSERT_MEMBERSHIP)
                    .param(assignment.projectId())
                    .param(userId)
                    .param(assignment.roleInProject())
                    .update();
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * MySQL's {@code JSON} type takes a JSON document as a string parameter.
     *
     * <p>Null in, null out — the column is nullable and {@code weekly_off} being
     * null is the meaningful value "inherit the org working week", not an
     * absence to be normalised to {@code []}.
     */
    String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return json.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("could not serialise " + value + " for a JSON column", e);
        }
    }

    private List<Integer> readIntArray(String document) {
        return readJson(document, new TypeReference<List<Integer>>() { });
    }

    private List<String> readStringArray(String document) {
        return readJson(document, new TypeReference<List<String>>() { });
    }

    private <T> T readJson(String document, TypeReference<T> type) {
        if (document == null) {
            return null;
        }
        try {
            return json.readValue(document, type);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // The CHECK constraints make this unreachable for anything this
            // application wrote. It stays a hard failure rather than a
            // swallowed null so that a row hand-edited into an illegal shape
            // surfaces as an error naming the row, not as a form quietly
            // missing a field.
            throw new IllegalStateException("malformed JSON in a users column: " + document, e);
        }
    }

    private static <T> List<T> orEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static Instant toInstant(LocalDateTime utc) {
        return utc == null ? null : utc.toInstant(ZoneOffset.UTC);
    }

    /** Everything the {@code INSERT} binds, already defaulted and resolved. */
    record NewResource(
            String empCode,
            String username,
            String email,
            String passwordHash,
            String fullName,
            String mobile,
            int roleId,
            Long reportingManagerId,
            String department,
            String designation,
            String location,
            BigDecimal dailyCapacityHrs,
            String timezone,
            boolean isActive,
            boolean mustChangePassword,
            LocalDate dateOfJoining,
            String avatarUrl,
            List<Integer> weeklyOff,
            List<String> skills) {
    }

    /** Which of the three unique fields are already taken. */
    record Conflicts(boolean username, boolean email, boolean employeeCode) {

        boolean any() {
            return username || email || employeeCode;
        }

        /** Field-keyed, matching the 400 shape so the form highlights the inputs. */
        Map<String, String> asFieldErrors() {
            var errors = new LinkedHashMap<String, String>();
            if (username) {
                errors.put("username", "that username is already taken");
            }
            if (email) {
                errors.put("email", "that email address is already registered");
            }
            if (employeeCode) {
                errors.put("employeeCode", "that employee code is already in use");
            }
            return errors;
        }
    }

    private record Conflict(String username, String email, String empCode) {
    }

    private record AssignmentRow(ResourceDtos.ProjectRef ref, ResourceDtos.ProjectAssignment assignment) {
    }
}
