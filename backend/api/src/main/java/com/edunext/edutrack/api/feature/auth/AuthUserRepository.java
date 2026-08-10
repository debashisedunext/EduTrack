package com.edunext.edutrack.api.feature.auth;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
                   u.is_active, u.must_change_password,
                   u.failed_attempts, u.locked_until
              FROM users u
              JOIN roles r ON r.id = u.role_id
             WHERE u.username = ?
            """;

    /**
     * A-024 · the same projection reached by surrogate id, for a caller who has
     * already been authenticated once and is renewing.
     *
     * <p>Separate from {@link #FIND_BY_USERNAME} rather than a shared statement
     * with a swapped predicate, because the two are governed by different rules:
     * that one must not filter on {@code is_active} (doing so would answer
     * without hashing and reopen the timing oracle), while this one has no
     * password step to protect and could. It still does not filter, for a
     * different reason — {@link AuthenticationService#resolveActiveUser} needs to
     * tell "deactivated" from "deleted" in its log line, and a WHERE clause
     * throws that away.
     */
    private static final String FIND_BY_ID = """
            SELECT u.id, u.username, u.email, u.full_name, u.password_hash,
                   u.role_id, r.code AS role_code, u.timezone,
                   u.is_active, u.must_change_password,
                   u.failed_attempts, u.locked_until
              FROM users u
              JOIN roles r ON r.id = u.role_id
             WHERE u.id = ?
            """;

    /**
     * A-027 · reached by email, for the forgot-password flow.
     *
     * <p>Like {@link #FIND_BY_ID} and unlike {@link #FIND_BY_USERNAME}, this has
     * no password step whose timing needs protecting — but it also must not
     * filter on {@code is_active}, because {@link ForgotPasswordService} has to
     * tell "no such address" from "deactivated" to log them differently. Both
     * produce the same silent non-answer to the caller.
     *
     * <p>{@code users.email} is unique and the column collation is
     * {@code utf8mb4_0900_ai_ci}, so matching is case-insensitive at the
     * database — which is what someone typing their own address expects, and
     * avoids a {@code LOWER()} that would discard the unique index.
     */
    private static final String FIND_BY_EMAIL = """
            SELECT u.id, u.username, u.email, u.full_name, u.password_hash,
                   u.role_id, r.code AS role_code, u.timezone,
                   u.is_active, u.must_change_password,
                   u.failed_attempts, u.locked_until
              FROM users u
              JOIN roles r ON r.id = u.role_id
             WHERE u.email = ?
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

    /** A-021 · the failure counter, incremented atomically in the database. */
    private static final String INCREMENT_FAILED_ATTEMPTS = """
            UPDATE users SET failed_attempts = failed_attempts + 1 WHERE id = ?
            """;

    private static final String READ_FAILED_ATTEMPTS = """
            SELECT failed_attempts FROM users WHERE id = ?
            """;

    /**
     * A-021 · applying the lock also zeroes the counter, so the fifteen minutes
     * after a lock lapses start a fresh window of five rather than locking
     * again on the very next mistake.
     */
    private static final String APPLY_LOCK = """
            UPDATE users SET locked_until = ?, failed_attempts = 0 WHERE id = ?
            """;

    private static final String CLEAR_LOCKOUT_STATE = """
            UPDATE users SET failed_attempts = 0, locked_until = NULL WHERE id = ?
            """;

    /**
     * A-026 · the new password and the cleared flag in <b>one statement</b>.
     *
     * <p>Two updates would leave a window in which the password has changed and
     * the account is still flagged — the user would be sent back to the
     * change-password screen and told their new password is wrong, because it is
     * now the current one. Worse in the other order: the flag clears, the write
     * of the hash fails, and the temporary password is live on an account that no
     * longer demands rotation. One statement has neither state.
     *
     * <p>{@code updated_at} is not set here; the column carries
     * {@code ON UPDATE CURRENT_TIMESTAMP(6)} from A-003 and maintains itself.
     *
     * <p>Lockout state is deliberately left alone. Reaching this statement
     * requires a valid access token, which requires having logged in, which
     * requires not being locked — so there is nothing to clear, and zeroing the
     * counter here would let a token holder wipe the evidence of a guessing run
     * against the account.
     */
    private static final String UPDATE_PASSWORD = """
            UPDATE users
               SET password_hash = ?, must_change_password = 0
             WHERE id = ?
            """;

    /**
     * A-027 · the reset write. See {@link #updatePasswordAndClearLockout} for why
     * all four columns move together in a single statement.
     *
     * <p><b>Deliberately distinct from {@link #UPDATE_PASSWORD} above</b>, which
     * leaves lockout state alone. The two paths differ in exactly that: A-026's
     * self-service change is reached from inside a live session, so the caller
     * cannot be locked and zeroing the counter would only wipe evidence of a
     * guessing run. A reset is reached by someone who forgot their password, and
     * very often got here by failing five times — leaving them locked out of the
     * account they just recovered, while holding a password we know is correct,
     * makes the recovery useless for the next fifteen minutes.
     *
     * <p>{@code updated_at} is not set here; A-003 gave the column
     * {@code ON UPDATE CURRENT_TIMESTAMP(6)} and it maintains itself.
     */
    private static final String UPDATE_PASSWORD_AND_CLEAR_LOCKOUT = """
            UPDATE users
               SET password_hash = ?,
                   must_change_password = 0,
                   failed_attempts = 0,
                   locked_until = NULL
             WHERE id = ?
            """;

    /**
     * A-021 · who hears about a lockout. Blueprint §S-01 says "email to Admin"
     * without naming one, so every active Admin is told.
     *
     * <p>Returns empty until Stream B's B-001 seeds the six system roles — there
     * is no ADMIN role to match yet. The caller logs that case rather than
     * failing the login, because a lockout that cannot be announced must still
     * be a lockout.
     */
    private static final String ACTIVE_ADMINS = """
            SELECT u.id, u.email
              FROM users u
              JOIN roles r ON r.id = u.role_id
             WHERE r.code = 'ADMIN' AND u.is_active = 1 AND u.email IS NOT NULL
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
            rs.getBoolean("must_change_password"),
            rs.getInt("failed_attempts"),
            toInstant(rs.getObject("locked_until", LocalDateTime.class)));

    private static final RowMapper<AdminRecipient> ADMIN_MAPPER = (rs, rowNum) ->
            new AdminRecipient(rs.getLong("id"), rs.getString("email"));

    /**
     * {@code DATETIME(6)} carries no zone and PLAN.md §3.1 stores UTC, so the
     * column is read as a {@link LocalDateTime} and stamped UTC explicitly.
     * Reading it as a {@code Timestamp} would let the driver reinterpret it in
     * the JVM's default zone and shift every lock expiry by the offset.
     */
    private static Instant toInstant(LocalDateTime utc) {
        return utc == null ? null : utc.toInstant(ZoneOffset.UTC);
    }

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

    /** A-024 · the refresh path, which holds an id rather than a username. */
    Optional<AuthUserRow> findById(long userId) {
        return jdbc.sql(FIND_BY_ID).param(userId).query(ROW_MAPPER).optional();
    }

    /** A-027 · the forgot-password path, which holds only what was typed into the form. */
    Optional<AuthUserRow> findByEmail(String email) {
        return jdbc.sql(FIND_BY_EMAIL).param(email).query(ROW_MAPPER).optional();
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

    // ── A-021 · lockout state ───────────────────────────────────────────────

    /**
     * Increments in the database rather than writing back a count read earlier,
     * so two simultaneous wrong guesses cannot both read 3 and both write 4.
     *
     * @return the count after this failure
     */
    int incrementFailedAttempts(long userId) {
        jdbc.sql(INCREMENT_FAILED_ATTEMPTS).param(userId).update();
        Integer attempts = jdbc.sql(READ_FAILED_ATTEMPTS).param(userId).query(Integer.class).single();
        return attempts == null ? 0 : attempts;
    }

    void applyLock(long userId, Instant until) {
        jdbc.sql(APPLY_LOCK)
                .param(LocalDateTime.ofInstant(until, ZoneOffset.UTC))
                .param(userId)
                .update();
    }

    void clearLockoutState(long userId) {
        jdbc.sql(CLEAR_LOCKOUT_STATE).param(userId).update();
    }

    List<AdminRecipient> findActiveAdmins() {
        return jdbc.sql(ACTIVE_ADMINS).query(ADMIN_MAPPER).list();
    }

    // ── A-026 / A-027 · setting a password ──────────────────────────────────

    /**
     * The only write in the system that touches {@code password_hash}, and the
     * only one that clears {@code must_change_password}.
     *
     * @param newPasswordHash an <b>already-hashed</b> password. This class never
     *                        sees a plaintext and must not start to — hashing
     *                        belongs with the {@code PasswordEncoder} in
     *                        {@link PasswordChangeService}, and a repository that
     *                        accepted a raw password would be one refactor away
     *                        from logging one.
     * @return rows updated — 1, or 0 if the row vanished between the read and
     *         this write. The caller checks it rather than assuming, because a
     *         silent 0 reports success for a password that was never stored.
     */
    int updatePassword(long userId, String newPasswordHash) {
        return jdbc.sql(UPDATE_PASSWORD).param(newPasswordHash).param(userId).update();
    }

    /**
     * Writes a new password hash and clears {@code must_change_password} in
     * <b>one statement</b>.
     *
     * <p>Two statements would leave a window in which the password has changed
     * and the flag has not — the user would be sent to the forced-change screen
     * and told their brand-new password is wrong, because by then it is the
     * current one. The reverse order is worse: the flag clears, the hash write
     * fails, and a temporary password stays live on an account that no longer
     * demands rotation.
     *
     * <p>The flag is cleared because a reset <i>is</i> setting a password. An
     * account created with a temporary one and recovered through the forgot-password
     * flow has demonstrably been through a deliberate choice of password, which
     * is the whole thing {@code must_change_password} waits for.
     *
     * <p>Lockout state is cleared with it, unlike A-026's self-service change.
     * The cases differ: someone changing their password from inside a session
     * has by definition just logged in, so there is nothing to clear. Someone
     * resetting a forgotten one very often got here by failing five times, and
     * leaving them locked out of the account they just recovered — holding a
     * password we know is correct — makes the recovery useless for the next
     * fifteen minutes.
     *
     * @param newPasswordHash an <b>already-hashed</b> password. This class never
     *                        sees a plaintext and must not begin to.
     * @return rows updated — 1, or 0 if the row vanished between read and write
     */
    int updatePasswordAndClearLockout(long userId, String newPasswordHash) {
        return jdbc.sql(UPDATE_PASSWORD_AND_CLEAR_LOCKOUT)
                .param(newPasswordHash)
                .param(userId)
                .update();
    }
}
