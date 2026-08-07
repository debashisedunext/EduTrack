package com.edunext.edutrack.api.feature.auth;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * A-020 · the read side of authentication.
 *
 * <p>Feature-packaged (CLAUDE.md, TEAM-PLAN.md §6): the auth feature owns its
 * own repository rather than sharing a global one, so Stream B's Resource
 * Master and this class can evolve without touching the same file. The two
 * read the same tables and that is fine — what must not be shared is the
 * mapping, because this one exists to serve a security decision and theirs
 * exists to serve a CRUD screen.
 *
 * <p>Plain SQL through {@link JdbcClient} rather than JPA, because no entities
 * exist yet — B-005 owns them and has not started. See {@link AuthUserRow}.
 */
@Repository
class AuthUserRepository {

    /**
     * Joined to {@code roles} because the role <i>code</i> (ADMIN, DEVELOPER, …)
     * is what every downstream consumer switches on — A-034's ScopeResolver,
     * A-022's {@code role} claim — never the surrogate id.
     *
     * <p>No {@code is_active} predicate here on purpose. Filtering inactive
     * users out in SQL would return "no such user" without doing any hashing
     * work, which is the timing oracle this task exists to close. The row is
     * fetched, the hash is verified, and only then is {@code active} consulted.
     */
    private static final String FIND_BY_USERNAME = """
            SELECT u.id, u.username, u.email, u.full_name, u.password_hash,
                   u.role_id, r.code AS role_code, u.timezone,
                   u.is_active, u.must_change_password
              FROM users u
              JOIN roles r ON r.id = u.role_id
             WHERE u.username = ?
            """;

    /** The §2 permission matrix for one role — the {@code permissions[]} of §10.1. */
    private static final String PERMISSIONS_FOR_ROLE = """
            SELECT p.code
              FROM permissions p
              JOIN role_permissions rp ON rp.permission_id = p.id
             WHERE rp.role_id = ?
             ORDER BY p.code
            """;

    /**
     * The PM/Support row scope of §10.2, read from the index
     * {@code ix_project_members_user} that A-003 created for exactly this.
     */
    private static final String PROJECT_IDS_FOR_USER = """
            SELECT pm.project_id
              FROM project_members pm
             WHERE pm.user_id = ? AND pm.is_active = 1
             ORDER BY pm.project_id
            """;

    /** Direct reportees only. §2's manager scope is one level, not the whole tree. */
    private static final String REPORTEE_IDS_FOR_USER = """
            SELECT u.id
              FROM users u
             WHERE u.reporting_manager_id = ? AND u.is_active = 1
             ORDER BY u.id
            """;

    private static final RowMapper<AuthUserRow> ROW_MAPPER = (rs, rowNum) -> new AuthUserRow(
            rs.getLong("id"),
            rs.getString("username"),
            rs.getString("email"),
            rs.getString("full_name"),
            rs.getString("password_hash"),
            rs.getString("role_code"),
            rs.getInt("role_id"),
            rs.getString("timezone"),
            rs.getBoolean("is_active"),
            rs.getBoolean("must_change_password"));

    private final JdbcClient jdbc;

    AuthUserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * {@code users.username} is unique and the column collation is
     * {@code utf8mb4_0900_ai_ci}, so this is case- and accent-insensitive at
     * the database — matching what a person typing their own name expects,
     * without a {@code LOWER()} that would discard the index.
     */
    Optional<AuthUserRow> findByUsername(String username) {
        return jdbc.sql(FIND_BY_USERNAME).param(username).query(ROW_MAPPER).optional();
    }

    List<String> findPermissionCodesByRoleId(int roleId) {
        return jdbc.sql(PERMISSIONS_FOR_ROLE).param(roleId).query(String.class).list();
    }

    List<Long> findProjectIdsByUserId(long userId) {
        return jdbc.sql(PROJECT_IDS_FOR_USER).param(userId).query(Long.class).list();
    }

    List<Long> findReporteeIdsByManagerId(long managerId) {
        return jdbc.sql(REPORTEE_IDS_FOR_USER).param(managerId).query(Long.class).list();
    }
}
