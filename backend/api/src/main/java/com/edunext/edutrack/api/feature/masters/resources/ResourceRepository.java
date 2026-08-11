package com.edunext.edutrack.api.feature.masters.resources;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * B-010 · the read and bulk-write side of the Resource Master (S-07).
 *
 * <p>Feature-packaged, for the reason {@code AuthUserRepository} states from the
 * other side: both classes read {@code users}, and that is fine. What must not
 * be shared is the mapping — that one exists to serve a security decision and
 * this one exists to serve a grid, and merging them would mean a column added
 * for a screen travelling into the authentication path.
 *
 * <p>Plain SQL through {@link JdbcClient} rather than the JPA entities B-005
 * built, deliberately. The grid needs ten columns drawn from four tables per
 * row; loading {@code User} entities would hang a lazy {@code role} proxy off
 * every row, resolve {@code reportingManagerId} with one query per row, and
 * fetch a password hash the screen must never see. {@link com.edunext.edutrack.domain.identity.User}
 * says the same thing from the entity side about why the manager edge is a
 * scalar.
 *
 * <h2>Three queries per page, whatever the page size</h2>
 *
 * The page itself, then the projects for its ids, then the open-ticket counts
 * for its ids. Not one query — a {@code GROUP_CONCAT} of projects would cap
 * silently at {@code group_concat_max_len} and a correlated count subquery runs
 * per row — and emphatically not one plus two-per-row, which is the N+1 this
 * split exists to prevent.
 */
@Repository
class ResourceRepository {

    /**
     * The grid projection.
     *
     * <p>{@code LEFT JOIN users m} resolves the reporting manager's name in the
     * same pass. {@code JOIN roles r} is inner because {@code users.role_id} is
     * {@code NOT NULL} (A-003 made it so precisely to keep scope defined), while
     * the manager's role join is left, because most people have a manager and
     * some do not.
     *
     * <p>No {@code is_active} predicate baked in. S-07's status filter has three
     * positions and the screen's purpose includes reactivating people, so
     * "unfiltered" must mean both.
     *
     * <p>{@code %s} is filled from {@link #whereClause} — assembled from a fixed
     * set of literal fragments, never from caller input. Every value is bound.
     */
    private static final String PAGE = """
            SELECT u.id, u.emp_code, u.username, u.email, u.full_name,
                   r.code AS role_code,
                   u.department, u.designation,
                   u.reporting_manager_id, m.full_name AS manager_name, mr.code AS manager_role,
                   u.is_active, u.last_login_at, u.created_at
              FROM users u
              JOIN roles r  ON r.id  = u.role_id
              LEFT JOIN users m  ON m.id  = u.reporting_manager_id
              LEFT JOIN roles mr ON mr.id = m.role_id
             WHERE %s
             ORDER BY u.full_name, u.id
             LIMIT ?
            """;

    private static final String COUNT = """
            SELECT COUNT(*)
              FROM users u
              JOIN roles r ON r.id = u.role_id
             WHERE %s
            """;

    /**
     * Projects for a page of resources, in one round trip.
     *
     * <p>{@code pm.is_active = 1} because a former team member is not on the
     * team; the row is kept rather than deleted so the historical assignment
     * survives, which is the same reason resources are deactivated and not
     * deleted.
     */
    private static final String PROJECTS_FOR = """
            SELECT pm.user_id, p.id, p.project_code, p.name, p.colour_tag
              FROM project_members pm
              JOIN projects p ON p.id = pm.project_id
             WHERE pm.user_id IN (%s) AND pm.is_active = 1
             ORDER BY pm.user_id, p.project_code
            """;

    /**
     * Open tickets per assignee, for one page of resources.
     *
     * <p><b>This is not the live {@code COUNT(*)} CLAUDE.md bans.</b> That rule
     * is about dashboard tiles, which count the whole ticket table on every
     * render and must read the pre-aggregated summaries instead. This counts at
     * most 200 assignees' worth of rows through {@code ix_tickets_assignee_status},
     * once per page — and it has to be current, because the number decides
     * whether deactivating somebody is allowed. A five-minute-old summary
     * saying zero would let live work be orphaned.
     *
     * <p>"Open" is {@code statuses.is_open}, not a hardcoded list. The status
     * vocabulary is master data an Admin extends (S-13); a literal
     * {@code <> 'CLOSED'} here would silently miscount the first status added
     * after this shipped.
     */
    private static final String OPEN_TICKETS_FOR = """
            SELECT t.assigned_to, COUNT(*) AS open_count
              FROM tickets t
              JOIN statuses s ON s.code = t.status
             WHERE t.assigned_to IN (%s) AND s.is_open = 1
             GROUP BY t.assigned_to
            """;

    /** The bulk write. Narrow on purpose — only the flag moves. */
    private static final String SET_ACTIVE = """
            UPDATE users SET is_active = ? WHERE id = ?
            """;

    private static final String FIND_STATUS = """
            SELECT u.id, u.full_name, u.is_active FROM users u WHERE u.id = ?
            """;

    private final JdbcClient jdbc;

    ResourceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /**
     * One page in {@code (full_name, id)} order, resuming after {@code cursor}.
     *
     * @param limit how many rows to return; the caller asks for one more than it
     *              needs to learn whether a further page exists
     */
    List<ResourceRow> page(ResourceFilter filter, ResourceCursor cursor, int limit) {
        List<Object> params = new ArrayList<>();
        String where = whereClause(filter, cursor, params);
        params.add(limit);

        return bind(PAGE.formatted(where), params).query(ROW_MAPPER).list();
    }

    /**
     * How many resources match, ignoring the cursor.
     *
     * <p>Cheap enough to include in {@code meta.totalCount}: {@code users} is
     * bounded by headcount, not by ticket volume, which is the distinction the
     * contract's "present only where a count is cheap" draws.
     */
    long count(ResourceFilter filter) {
        List<Object> params = new ArrayList<>();
        String where = whereClause(filter, null, params);

        Long total = bind(COUNT.formatted(where), params).query(Long.class).single();
        return total == null ? 0L : total;
    }

    /** Projects per resource, for the ids on one page. Empty in, empty out. */
    Map<Long, List<ResourceDtos.ProjectRef>> projectsFor(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        List<MembershipRow> rows = bind(
                PROJECTS_FOR.formatted(placeholders(userIds.size())), List.copyOf(userIds))
                .query(MEMBERSHIP_MAPPER)
                .list();

        Map<Long, List<ResourceDtos.ProjectRef>> byUser = new LinkedHashMap<>();
        for (MembershipRow row : rows) {
            byUser.computeIfAbsent(row.userId(), k -> new ArrayList<>()).add(row.project());
        }
        return byUser;
    }

    /**
     * Open-ticket counts per resource, for the ids on one page.
     *
     * <p>A resource with no open tickets has no row in the {@code GROUP BY}
     * result. The map is filled with zeros for those, because the caller is
     * asking "how many" and "absent" is not an answer it can render.
     */
    Map<Long, Integer> openTicketCounts(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        List<AssigneeCount> rows = bind(
                OPEN_TICKETS_FOR.formatted(placeholders(userIds.size())), List.copyOf(userIds))
                .query(ASSIGNEE_COUNT_MAPPER)
                .list();

        Map<Long, Integer> counts = new HashMap<>();
        rows.forEach(row -> counts.put(row.userId(), row.openCount()));

        Map<Long, Integer> filled = new LinkedHashMap<>();
        userIds.forEach(id -> filled.put(id, counts.getOrDefault(id, 0)));
        return filled;
    }

    // ------------------------------------------------------------------
    // Bulk write
    // ------------------------------------------------------------------

    Optional<ResourceStatus> findStatus(long userId) {
        return jdbc.sql(FIND_STATUS)
                .param(userId)
                .query((rs, rowNum) -> new ResourceStatus(
                        rs.getLong("id"), rs.getString("full_name"), rs.getBoolean("is_active")))
                .optional();
    }

    /** @return true when a row actually moved */
    boolean setActive(long userId, boolean isActive) {
        return jdbc.sql(SET_ACTIVE).param(isActive).param(userId).update() > 0;
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * Builds the {@code WHERE} from literal fragments, appending each bound value
     * to {@code params} in the order its placeholder appears.
     *
     * <p>The fragments are constants and the values are always bound — the
     * {@code %s} in {@link #PAGE} never receives caller input. The one place a
     * caller's text reaches SQL is the {@code LIKE} pattern, and that is a bound
     * parameter with its wildcards escaped by {@link #likePattern}.
     */
    private static String whereClause(ResourceFilter filter, ResourceCursor cursor, List<Object> params) {
        List<String> clauses = new ArrayList<>();
        clauses.add("1 = 1");

        if (filter.q() != null) {
            clauses.add("""
                    (u.full_name LIKE ? ESCAPE '\\\\'
                      OR u.username LIKE ? ESCAPE '\\\\'
                      OR u.email    LIKE ? ESCAPE '\\\\'
                      OR u.emp_code LIKE ? ESCAPE '\\\\')""");
            String pattern = likePattern(filter.q());
            params.add(pattern);
            params.add(pattern);
            params.add(pattern);
            params.add(pattern);
        }
        if (filter.role() != null) {
            clauses.add("r.code = ?");
            params.add(filter.role());
        }
        if (filter.projectId() != null) {
            // EXISTS rather than a join: a join would duplicate the user row per
            // matching membership, and uq_project_members makes that at most one
            // today — "at most one today" being exactly the kind of assumption
            // that quietly stops holding.
            clauses.add("""
                    EXISTS (SELECT 1 FROM project_members pm
                             WHERE pm.user_id = u.id AND pm.project_id = ? AND pm.is_active = 1)""");
            params.add(filter.projectId());
        }
        if (filter.managerId() != null) {
            clauses.add("u.reporting_manager_id = ?");
            params.add(filter.managerId());
        }
        if (filter.isActive() != null) {
            clauses.add("u.is_active = ?");
            params.add(filter.isActive());
        }
        if (cursor != null) {
            // The keyset predicate must mirror ORDER BY (full_name, id) exactly,
            // or the page boundary drifts and rows are skipped.
            clauses.add("(u.full_name > ? OR (u.full_name = ? AND u.id > ?))");
            params.add(cursor.fullName());
            params.add(cursor.fullName());
            params.add(cursor.id());
        }
        return String.join("\n   AND ", clauses);
    }

    /**
     * Escapes the caller's search term so a literal {@code %} matches a percent
     * sign rather than everything.
     *
     * <p>Order matters: the backslash is escaped first, or escaping {@code %}
     * would then have its own backslash escaped again.
     */
    private static String likePattern(String q) {
        String escaped = q.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private static String placeholders(int n) {
        return String.join(", ", java.util.Collections.nCopies(n, "?"));
    }

    private JdbcClient.StatementSpec bind(String sql, List<Object> params) {
        JdbcClient.StatementSpec spec = jdbc.sql(sql);
        for (Object param : params) {
            spec = spec.param(param);
        }
        return spec;
    }

    /**
     * {@code DATETIME(6)} carries no zone and PLAN.md §3.1 stores UTC, so the
     * column is read as a {@link LocalDateTime} and stamped UTC explicitly.
     * Reading it as a {@code Timestamp} would let the driver reinterpret it in
     * the JVM's default zone — the round-trip shift B-023 already hit once.
     */
    private static Instant toInstant(LocalDateTime utc) {
        return utc == null ? null : utc.toInstant(ZoneOffset.UTC);
    }

    private static final RowMapper<ResourceRow> ROW_MAPPER = (rs, rowNum) -> new ResourceRow(
            rs.getLong("id"),
            rs.getString("emp_code"),
            rs.getString("username"),
            rs.getString("email"),
            rs.getString("full_name"),
            rs.getString("role_code"),
            rs.getString("department"),
            rs.getString("designation"),
            managerRef(rs),
            rs.getBoolean("is_active"),
            toInstant(rs.getObject("last_login_at", LocalDateTime.class)),
            toInstant(rs.getObject("created_at", LocalDateTime.class)));

    private static final RowMapper<MembershipRow> MEMBERSHIP_MAPPER = (rs, rowNum) -> new MembershipRow(
            rs.getLong("user_id"),
            new ResourceDtos.ProjectRef(
                    rs.getLong("id"),
                    rs.getString("project_code"),
                    rs.getString("name"),
                    rs.getString("colour_tag")));

    private static final RowMapper<AssigneeCount> ASSIGNEE_COUNT_MAPPER = (rs, rowNum) ->
            new AssigneeCount(rs.getLong("assigned_to"), rs.getInt("open_count"));

    private static ResourceDtos.UserRef managerRef(ResultSet rs) throws SQLException {
        long managerId = rs.getLong("reporting_manager_id");
        if (rs.wasNull()) {
            return null;
        }
        return new ResourceDtos.UserRef(managerId, rs.getString("manager_name"), rs.getString("manager_role"));
    }

    /** One row of {@link #PAGE}, before projects and counts are attached. */
    record ResourceRow(
            long id,
            String empCode,
            String username,
            String email,
            String fullName,
            String roleCode,
            String department,
            String designation,
            ResourceDtos.UserRef reportingManager,
            boolean isActive,
            Instant lastLoginAt,
            Instant createdAt) {
    }

    /** The three columns the bulk status write needs to decide and to report. */
    record ResourceStatus(long id, String fullName, boolean isActive) {
    }

    /** One {@code project_members} row, before it is grouped by resource. */
    private record MembershipRow(long userId, ResourceDtos.ProjectRef project) {
    }

    private record AssigneeCount(long userId, int openCount) {
    }
}
