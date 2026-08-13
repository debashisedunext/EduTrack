package com.edunext.edutrack.api.feature.masters.projects;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * B-016 · the Project Master's reads and writes (S-10).
 *
 * <p><b>Named {@code ProjectMasterRepository} and not {@code ProjectRepository},
 * because the obvious name does not work.</b> Spring derives a bean name from
 * the simple class name, so a second {@code ProjectRepository} — in any package
 * — collides with {@link
 * com.edunext.edutrack.domain.identity.ProjectRepository}, the Spring Data
 * interface over the B-005 entity. The failure is a
 * {@code BeanDefinitionOverrideException} at context startup, which takes out
 * every {@code @SpringBootTest} in the module at once and names neither the
 * feature nor the task that introduced it. {@code ResourceRepository} gets away
 * with the short name only because {@code domain.identity} happens to have no
 * {@code ResourceRepository} to collide with.
 *
 * <p>The two still do different jobs, which is why both exist: that one hands
 * back a {@code Project} entity to anything that needs the object, this one is a
 * screen's projection and the statements that change it.
 *
 * <p>Plain SQL through {@link JdbcClient} rather than those entities,
 * deliberately, and for a reason specific to this table: the grid needs the
 * manager's name and role, which the entity models as a bare {@code managerId}
 * scalar (per {@code domain.identity.package-info}'s rule that actor references
 * are not associations). Loading entities would mean a second query per row to
 * name a manager the projection can join in the same pass — and would put a
 * managed {@code Project} in a transaction that also writes, which
 * {@code Project.ticketSeq}'s javadoc explains at length is how a ticket-ID
 * allocation gets silently reverted.
 *
 * <p><b>Nothing here reads or writes {@code ticket_seq} except to report it.</b>
 * Every statement below names its columns; a {@code SELECT *} would be harmless
 * and an {@code UPDATE} that did not name columns would not be.
 */
@Repository
class ProjectMasterRepository {

    /**
     * The grid projection: one row per project with its manager named.
     *
     * <p>{@code LEFT JOIN users m} because {@code manager_id} is NULLable in the
     * schema. S-10 requires a manager and {@link ProjectService} refuses a write
     * without one, but rows predating that rule exist in principle and a project
     * must not vanish from its own master screen for want of a manager.
     *
     * <p>{@code %s} is filled from {@link #whereClause} — assembled from a fixed
     * set of literal fragments, never from caller input. Every value is bound.
     */
    private static final String PAGE = """
            SELECT p.id, p.project_code, p.name, p.client_name,
                   p.manager_id, m.full_name AS manager_name, mr.code AS manager_role,
                   p.colour_tag, p.start_date, p.target_end_date,
                   p.status, p.auto_assign_rule
              FROM projects p
              LEFT JOIN users m  ON m.id  = p.manager_id
              LEFT JOIN roles mr ON mr.id = m.role_id
             WHERE %s
             ORDER BY p.name, p.id
             LIMIT ?
            """;

    /**
     * The edit form's read. Adds {@code description} and {@code ticket_seq},
     * which the grid has no use for — the first is a paragraph nobody reads in a
     * table cell, and the second is only meaningful to the form's decision about
     * whether the code input is editable.
     */
    private static final String FIND_DETAIL = """
            SELECT p.id, p.project_code, p.name, p.client_name, p.description,
                   p.manager_id, m.full_name AS manager_name, mr.code AS manager_role,
                   p.colour_tag, p.start_date, p.target_end_date,
                   p.status, p.auto_assign_rule, p.ticket_seq
              FROM projects p
              LEFT JOIN users m  ON m.id  = p.manager_id
              LEFT JOIN roles mr ON mr.id = m.role_id
             WHERE p.id = ?
            """;

    /**
     * {@code ?} for the excluded id rather than two versions of the statement:
     * on a create there is no id to exclude and {@code -1} excludes nothing.
     *
     * <p>Compared case-insensitively by the column's own collation
     * ({@code utf8mb4_0900_ai_ci}), which is what {@code uq_projects_code}
     * enforces — so {@code crm} and {@code CRM} collide here exactly as they
     * would at the index. The service upper-cases before it gets this far;
     * agreeing with the collation anyway means the check cannot disagree with
     * the constraint it exists to pre-empt.
     */
    private static final String CODE_TAKEN = """
            SELECT 1 FROM projects p WHERE p.id <> ? AND p.project_code = ?
            """;

    private static final String INSERT = """
            INSERT INTO projects (project_code, name, description, client_name, manager_id,
                                  start_date, target_end_date, status, colour_tag, auto_assign_rule)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    /**
     * Named columns, and {@code ticket_seq} is not among them.
     *
     * <p>That is the point of writing the {@code UPDATE} by hand rather than
     * letting Hibernate flush a dirty entity: Hibernate writes every updatable
     * column of a dirty row, so a transaction that renamed a project after a
     * concurrent ticket-ID allocation would flush the counter back to the value
     * it read before the increment — reusing an ID that then dies on
     * {@code uq_tickets_code}. {@code Project.ticketSeq} carries the full
     * account; this statement is the shape that makes it moot here.
     */
    private static final String UPDATE = """
            UPDATE projects
               SET project_code = ?, name = ?, description = ?, client_name = ?, manager_id = ?,
                   start_date = ?, target_end_date = ?, status = ?, colour_tag = ?,
                   auto_assign_rule = ?
             WHERE id = ?
            """;

    /**
     * The manager candidate: does the row exist, is it active, and what is its
     * role.
     *
     * <p>The role comes back so the refusal can name it, not so the service can
     * gate on it — see {@link ProjectService#validateManager}.
     */
    private static final String FIND_MANAGER = """
            SELECT u.id, u.full_name, u.is_active, r.code AS role_code
              FROM users u
              JOIN roles r ON r.id = u.role_id
             WHERE u.id = ?
            """;

    private final JdbcClient jdbc;

    ProjectMasterRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /**
     * One page of the grid.
     *
     * @param limit pass one more than the page size; the caller uses the extra
     *              row to decide {@code hasMore} without a second count
     */
    List<ProjectDtos.Project> page(ProjectFilter filter, ProjectCursor cursor, int limit) {
        List<Object> args = new ArrayList<>();
        String where = whereClause(filter, cursor, args);
        args.add(limit);

        return jdbc.sql(PAGE.formatted(where)).params(args)
                .query((rs, rowNum) -> new ProjectDtos.Project(
                        rs.getLong("id"),
                        rs.getString("project_code"),
                        rs.getString("name"),
                        rs.getString("client_name"),
                        managerRef(rs),
                        rs.getString("colour_tag"),
                        rs.getObject("start_date", LocalDate.class),
                        rs.getObject("target_end_date", LocalDate.class),
                        rs.getString("status"),
                        ProjectService.isActive(rs.getString("status")),
                        rs.getString("auto_assign_rule")))
                .list();
    }

    Optional<ProjectDtos.ProjectDetail> findDetail(long projectId) {
        return jdbc.sql(FIND_DETAIL).param(projectId)
                .query((rs, rowNum) -> new ProjectDtos.ProjectDetail(
                        rs.getLong("id"),
                        rs.getString("project_code"),
                        rs.getString("name"),
                        rs.getString("client_name"),
                        rs.getString("description"),
                        managerRef(rs),
                        rs.getString("colour_tag"),
                        rs.getObject("start_date", LocalDate.class),
                        rs.getObject("target_end_date", LocalDate.class),
                        rs.getString("status"),
                        ProjectService.isActive(rs.getString("status")),
                        rs.getString("auto_assign_rule"),
                        rs.getLong("ticket_seq")))
                .optional();
    }

    boolean codeTaken(String projectCode, long excludingId) {
        return jdbc.sql(CODE_TAKEN).param(excludingId).param(projectCode)
                .query(Integer.class).optional().isPresent();
    }

    Optional<ManagerCandidate> findManager(long userId) {
        return jdbc.sql(FIND_MANAGER).param(userId)
                .query((rs, rowNum) -> new ManagerCandidate(
                        rs.getLong("id"),
                        rs.getString("full_name"),
                        rs.getString("role_code"),
                        rs.getBoolean("is_active")))
                .optional();
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /** @return the generated id */
    long insert(ProjectRow row) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql(INSERT)
                .param(row.projectCode())
                .param(row.name())
                .param(row.description())
                .param(row.clientName())
                .param(row.managerId())
                .param(row.startDate())
                .param(row.endDate())
                .param(row.status())
                .param(row.colourTag())
                .param(row.autoAssignRule())
                .update(keys);

        Number id = keys.getKey();
        if (id == null) {
            throw new IllegalStateException("projects INSERT returned no generated key");
        }
        return id.longValue();
    }

    void update(long projectId, ProjectRow row) {
        jdbc.sql(UPDATE)
                .param(row.projectCode())
                .param(row.name())
                .param(row.description())
                .param(row.clientName())
                .param(row.managerId())
                .param(row.startDate())
                .param(row.endDate())
                .param(row.status())
                .param(row.colourTag())
                .param(row.autoAssignRule())
                .param(projectId)
                .update();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * Literal fragments only; every value is bound.
     *
     * <p>{@code 1 = 1} is the base so each filter appends uniformly. The keyset
     * predicate is last because it is the one that always reads oddly:
     * {@code (name, id) > (?, ?)} expressed the long way, because MySQL's row
     * constructor comparison does not use {@code ix_projects_name} on every
     * version and the expanded form does.
     */
    private static String whereClause(ProjectFilter filter, ProjectCursor cursor, List<Object> args) {
        StringBuilder where = new StringBuilder("1 = 1");

        if (filter.status() != null) {
            where.append(" AND p.status = ?");
            args.add(filter.status());
        }
        if (filter.isActive() != null) {
            // `isActive` is derived, so it filters on the column it is derived
            // from rather than on a column of its own — see ProjectService.
            where.append(filter.isActive() ? " AND p.status <> ?" : " AND p.status = ?");
            args.add(ProjectService.CLOSED);
        }
        if (filter.managerId() != null) {
            where.append(" AND p.manager_id = ?");
            args.add(filter.managerId());
        }
        if (filter.query() != null && !filter.query().isBlank()) {
            where.append(" AND (p.name LIKE ? OR p.project_code LIKE ?)");
            String like = "%" + filter.query().trim() + "%";
            args.add(like);
            args.add(like);
        }
        if (cursor != null) {
            where.append(" AND (p.name > ? OR (p.name = ? AND p.id > ?))");
            args.add(cursor.name());
            args.add(cursor.name());
            args.add(cursor.id());
        }
        return where.toString();
    }

    private static ProjectDtos.UserRef managerRef(ResultSet rs) throws SQLException {
        long managerId = rs.getLong("manager_id");
        if (rs.wasNull()) {
            return null;
        }
        return new ProjectDtos.UserRef(managerId,
                rs.getString("manager_name"), rs.getString("manager_role"));
    }

    /** The grid's filter set, from the query string. */
    record ProjectFilter(Boolean isActive, String status, Long managerId, String query) {
    }

    /** Everything a write puts on the row. Assembled by the service, never by a caller. */
    record ProjectRow(
            String projectCode,
            String name,
            String description,
            String clientName,
            Long managerId,
            LocalDate startDate,
            LocalDate endDate,
            String status,
            String colourTag,
            String autoAssignRule) {
    }

    record ManagerCandidate(long id, String displayName, String roleCode, boolean isActive) {
    }
}
