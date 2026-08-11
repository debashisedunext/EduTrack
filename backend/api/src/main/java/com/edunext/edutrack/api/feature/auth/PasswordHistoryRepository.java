package com.edunext.edutrack.api.feature.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * A-028 · {@code password_history}. Blueprint §10.3, "no reuse of last 3".
 *
 * <p>Plain SQL through {@link JdbcClient}, matching {@link AuthUserRepository}
 * and {@link PasswordResetTokenRepository} — B-005 owns the entity model and a
 * retired password hash is not part of it.
 */
@Repository
class PasswordHistoryRepository {

    /**
     * Records a password that has just stopped being current.
     *
     * <p>Called with the hash being <i>replaced</i>, not the new one — see
     * {@link PasswordPolicy#recordRetired}. The distinction matters: storing the
     * incoming hash would mean the live password also sits in history, and the
     * no-reuse check would then refuse the password the user is currently
     * using... which is correct behaviour reached by accident, and wrong the
     * moment the depth is raised, because the window would silently be N-1.
     */
    private static final String INSERT = """
            INSERT INTO password_history (user_id, password_hash) VALUES (?, ?)
            """;

    /**
     * The N most recently retired hashes for one user.
     *
     * <p>{@code ORDER BY retired_at DESC, id DESC} — the id is the tiebreaker,
     * and it is not decoration. {@code DATETIME(6)} is microsecond-precision, but
     * two rows written inside one transaction can still land on the same value,
     * and without a deterministic second key the LIMIT would pick between them
     * arbitrarily. On a depth-3 window that means an attacker-chosen ordering
     * could push a hash out of the window a change early.
     *
     * <p>Served by {@code ix_password_history_user (user_id, retired_at DESC)}.
     */
    private static final String RECENT_FOR_USER = """
            SELECT ph.password_hash
              FROM password_history ph
             WHERE ph.user_id = ?
             ORDER BY ph.retired_at DESC, ph.id DESC
             LIMIT ?
            """;

    /**
     * A-028 · housekeeping. Rows beyond the window can never be consulted again,
     * so they are storage cost and — since each is a real password hash —
     * needless exposure.
     *
     * <p>Expressed as "delete everything for this user except the newest N",
     * with the same ordering as the read so the two cannot disagree about which
     * rows the window holds. The nested SELECT is required: MySQL refuses a
     * LIMIT in a subquery of a DELETE against the same table, and the derived
     * table {@code keep} is what gets around it.
     */
    private static final String PRUNE_BEYOND_WINDOW = """
            DELETE FROM password_history
             WHERE user_id = ?
               AND id NOT IN (
                   SELECT id FROM (
                       SELECT ph.id
                         FROM password_history ph
                        WHERE ph.user_id = ?
                        ORDER BY ph.retired_at DESC, ph.id DESC
                        LIMIT ?
                   ) AS keep
               )
            """;

    private final JdbcClient jdbc;

    PasswordHistoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @param passwordHash the Argon2id hash being retired. Never a plaintext. */
    void insert(long userId, String passwordHash) {
        jdbc.sql(INSERT).param(userId).param(passwordHash).update();
    }

    List<String> findRecentHashes(long userId, int depth) {
        if (depth <= 0) {
            return List.of();
        }
        return jdbc.sql(RECENT_FOR_USER).param(userId).param(depth).query(String.class).list();
    }

    /** @return how many rows fell out of the window, for the log line only */
    int pruneBeyond(long userId, int depth) {
        if (depth <= 0) {
            // Depth 0 disables the rule; it does not mean "delete everything".
            // Someone re-enabling the policy tomorrow should still have the
            // history that was accumulated before it was switched off.
            return 0;
        }
        return jdbc.sql(PRUNE_BEYOND_WINDOW).param(userId).param(userId).param(depth).update();
    }
}
