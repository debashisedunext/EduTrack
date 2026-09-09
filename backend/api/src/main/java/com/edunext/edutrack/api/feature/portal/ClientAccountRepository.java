package com.edunext.edutrack.api.feature.portal;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * A-125 · reads and writes {@code client_accounts} for the login path.
 *
 * <p>{@code AuthUserRepository}'s shape and, where the rules are the same, its
 * rules verbatim. Where they differ it is said out loud below rather than left
 * for a reader to spot by diffing the two.
 */
@Repository
class ClientAccountRepository {

    /**
     * <p><b>No {@code is_active} predicate, and this is the important line in
     * the file.</b> Filtering deactivated accounts out in SQL would return "no
     * such account" without doing any hashing work, which is a timing oracle —
     * the same one {@code AuthUserRepository.FIND_BY_USERNAME} exists to avoid.
     * The row is fetched, the hash is verified, and only then is {@code active}
     * consulted.
     *
     * <p>It matters more here than it does for staff. A portal login is
     * reachable from the internet by anyone who can guess a username, and the
     * population of usernames is small and enumerable — one per client. An
     * attacker who can tell "this account exists but is switched off" from
     * "this account does not exist" learns the client list.
     *
     * <p>No join, unlike the staff query. That one joins {@code roles} because
     * every consumer switches on the role code; a client account has no role,
     * so there is nothing to join and nothing to widen the row with.
     */
    private static final String FIND_BY_USERNAME = """
            SELECT id, username, password_hash, client_id, ob_client_id,
                   display_name, email, is_active, must_change_password,
                   failed_attempts, locked_until, last_login_at
              FROM client_accounts
             WHERE username = ?
            """;

    /**
     * The same projection by surrogate id, for a caller already authenticated
     * once and renewing.
     *
     * <p>Separate statement rather than a shared one with a swapped predicate,
     * for {@code AuthUserRepository}'s reason: the two are governed by different
     * rules. That one must not filter on {@code is_active}; this one has no
     * password step to protect and still does not filter, because a refresh
     * against a deactivated account has to be distinguishable from a refresh
     * against a deleted one in the log line that records it.
     */
    private static final String FIND_BY_ID = """
            SELECT id, username, password_hash, client_id, ob_client_id,
                   display_name, email, is_active, must_change_password,
                   failed_attempts, locked_until, last_login_at
              FROM client_accounts
             WHERE id = ?
            """;

    /**
     * A-021's counter, incremented on a failed verification.
     *
     * <p>Unconditional rather than guarded by the current value, so two
     * simultaneous wrong guesses both count. A read-modify-write would let a
     * pair of concurrent attempts record one failure between them, which is
     * exactly the shape an attacker parallelises into.
     */
    private static final String INCREMENT_FAILED_ATTEMPTS = """
            UPDATE client_accounts
               SET failed_attempts = failed_attempts + 1
             WHERE id = ?
            """;

    /**
     * Applying the lock resets the counter, so {@code failed_attempts} always
     * means "failures within the current window" rather than "failures ever".
     * {@code AuthUserRepository} records the same invariant on the staff table.
     */
    private static final String APPLY_LOCK = """
            UPDATE client_accounts
               SET locked_until = ?, failed_attempts = 0
             WHERE id = ?
            """;

    private static final String RECORD_SUCCESS = """
            UPDATE client_accounts
               SET last_login_at = ?, failed_attempts = 0, locked_until = NULL
             WHERE id = ?
            """;

    /**
     * Clearing {@code must_change_password} and setting the new hash is one
     * statement, not two.
     *
     * <p>Two would have a window in which the password is changed and the flag
     * still says it is not — a client who closed the tab there would be asked
     * to change a password they had already changed, and would be asked for the
     * old one they no longer have.
     */
    private static final String SET_PASSWORD = """
            UPDATE client_accounts
               SET password_hash = ?, must_change_password = 0
             WHERE id = ?
            """;

    /**
     * B-126 · the OB-05 panel's read — one client's portal login, if it has one.
     *
     * <p>No {@code is_active} predicate here either, but for a different reason
     * from the two statements above: the panel's whole job is to show a
     * disabled login and offer to re-enable it. Filtering would make a
     * deactivated account indistinguishable from none, and the operator would
     * create a second one — which {@code uq_client_accounts_ob_client} then
     * refuses, on a screen that had just told them there was no account.
     */
    private static final String FIND_BY_OB_CLIENT = """
            SELECT id, username, password_hash, client_id, ob_client_id,
                   display_name, email, is_active, must_change_password,
                   failed_attempts, locked_until, last_login_at
              FROM client_accounts
             WHERE ob_client_id = ?
            """;

    /**
     * B-126 · creating a login for an onboarding client.
     *
     * <p>{@code client_id} is deliberately left NULL. The two masters are not
     * linked — V20260905_1630's header is explicit that nothing in the schema
     * says {@code clients.id 5} and {@code ob_clients.id 9} are the same
     * company — so guessing a ticketing client here would be inventing a
     * correspondence nobody established, on the table where that correspondence
     * is supposed to eventually live.
     *
     * <p>{@code must_change_password} is not named: the column defaults to 1,
     * and A-125's own comment says defaulting rather than relying on every
     * creator to remember is the point.
     */
    private static final String INSERT = """
            INSERT INTO client_accounts
                   (username, password_hash, ob_client_id, display_name, email, created_by)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    /**
     * B-126 · enable or disable, as a stated value rather than a toggle.
     *
     * <p>Disabling deliberately does not touch {@code password_hash} or the
     * lockout counters. A-125's migration makes the case one table over — "a
     * deactivated login preserves what it signed" — and the same holds here:
     * re-enabling has to restore the account that existed, not a blank one.
     */
    private static final String SET_ACTIVE = """
            UPDATE client_accounts
               SET is_active = ?
             WHERE id = ?
            """;

    private static final RowMapper<ClientAccountRow> MAPPER = ClientAccountRepository::map;

    private final JdbcClient jdbc;

    ClientAccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<ClientAccountRow> findByUsername(String username) {
        return jdbc.sql(FIND_BY_USERNAME).param(username).query(MAPPER).optional();
    }

    Optional<ClientAccountRow> findById(long id) {
        return jdbc.sql(FIND_BY_ID).param(id).query(MAPPER).optional();
    }

    void incrementFailedAttempts(long id) {
        jdbc.sql(INCREMENT_FAILED_ATTEMPTS).param(id).update();
    }

    void applyLock(long id, Instant until) {
        jdbc.sql(APPLY_LOCK).param(Timestamp.from(until)).param(id).update();
    }

    void recordSuccessfulLogin(long id, Instant at) {
        jdbc.sql(RECORD_SUCCESS).param(Timestamp.from(at)).param(id).update();
    }

    void setPassword(long id, String argon2idHash) {
        jdbc.sql(SET_PASSWORD).param(argon2idHash).param(id).update();
    }

    /**
     * {@code username} is unique, so a lookup answers at most one row — but
     * that is the schema's guarantee and not this method's, which is why the
     * query returns an {@code Optional} rather than asserting it.
     */
    Optional<ClientAccountRow> findByObClientId(long obClientId) {
        return jdbc.sql(FIND_BY_OB_CLIENT).param(obClientId).query(MAPPER).optional();
    }

    /**
     * @return the new account's id, from {@code LAST_INSERT_ID()} rather than a
     *         second lookup by username — the row is unique on it, but a
     *         re-read would be a second statement that can see a different
     *         row if anything else has raced.
     */
    long insert(String username, String passwordHash, long obClientId,
                String displayName, String email, Long createdBy) {
        jdbc.sql(INSERT)
                .param(username)
                .param(passwordHash)
                .param(obClientId)
                .param(displayName)
                .param(email)
                .param(createdBy)
                .update();
        return jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
    }

    void setActive(long id, boolean active) {
        jdbc.sql(SET_ACTIVE).param(active).param(id).update();
    }

    boolean usernameExists(String username) {
        return jdbc.sql("SELECT 1 FROM client_accounts WHERE username = ?")
                .param(username)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    private static ClientAccountRow map(ResultSet rs, int rowNum) throws SQLException {
        return new ClientAccountRow(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("password_hash"),
                nullableLong(rs, "client_id"),
                nullableLong(rs, "ob_client_id"),
                rs.getString("display_name"),
                rs.getString("email"),
                rs.getBoolean("is_active"),
                rs.getBoolean("must_change_password"),
                rs.getInt("failed_attempts"),
                instant(rs, "locked_until"),
                instant(rs, "last_login_at"));
    }

    /**
     * {@code getLong} answers 0 for SQL NULL, and 0 is not a client id anybody
     * owns — but it is a value {@code equals} would compare, so the two client
     * columns have to come back as real nulls. This is the difference between
     * "this login owns no ticketing client" and "this login owns client 0".
     */
    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
