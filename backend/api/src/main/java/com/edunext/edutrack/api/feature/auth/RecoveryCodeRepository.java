package com.edunext.edutrack.api.feature.auth;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * A-029 · {@code totp_recovery_codes}. The way back in when the authenticator
 * is gone.
 *
 * <p>Every method speaks in hashes. A recovery code's plaintext exists exactly
 * once — in the list shown to the user at enrolment — and is unrecoverable
 * afterwards, which is the same posture {@link PasswordResetTokenRepository}
 * takes for reset tokens and {@code AuthUserRepository} for passwords.
 */
@Repository
class RecoveryCodeRepository {

    private static final String INSERT = """
            INSERT INTO totp_recovery_codes (user_id, code_hash) VALUES (?, ?)
            """;

    /**
     * The redemption read. Unused codes only — a spent one can never match
     * again, so there is nothing to gain by verifying against it and one
     * Argon2id verification to lose.
     *
     * <p>{@code id} comes back with the hash because redemption has to mark
     * <i>that specific row</i> used, and matching by hash afterwards would mean
     * a second scan.
     */
    private static final String UNUSED_FOR_USER = """
            SELECT rc.id, rc.code_hash
              FROM totp_recovery_codes rc
             WHERE rc.user_id = ? AND rc.used_at IS NULL
             ORDER BY rc.id
            """;

    /**
     * Spends one code, and only if it is still unspent.
     *
     * <p>{@code AND used_at IS NULL} is the arbiter rather than a prior read:
     * two requests presenting the same code at once would both see it unused,
     * and a read-then-write would let both through. Exactly one caller can
     * affect the row. Same shape as {@code PasswordResetTokenRepository#markUsed}.
     */
    private static final String MARK_USED = """
            UPDATE totp_recovery_codes
               SET used_at = ?
             WHERE id = ? AND used_at IS NULL
            """;

    /**
     * A-029 · enrolment and re-generation both start from a clean set.
     *
     * <p>Deleted rather than marked used, unlike a redemption. A code that was
     * never presented is not evidence of anything, and keeping every superseded
     * set would mean the table grows by ten on each re-enrolment while holding
     * hashes nobody can ever redeem.
     */
    private static final String DELETE_ALL_FOR_USER = """
            DELETE FROM totp_recovery_codes WHERE user_id = ?
            """;

    private static final String COUNT_UNUSED = """
            SELECT COUNT(*) FROM totp_recovery_codes WHERE user_id = ? AND used_at IS NULL
            """;

    private static final RowMapper<StoredRecoveryCode> ROW_MAPPER = (rs, rowNum) ->
            new StoredRecoveryCode(rs.getLong("id"), rs.getString("code_hash"));

    private final JdbcClient jdbc;

    RecoveryCodeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @param codeHash Argon2id. Never the code itself. */
    void insert(long userId, String codeHash) {
        jdbc.sql(INSERT).param(userId).param(codeHash).update();
    }

    List<StoredRecoveryCode> findUnused(long userId) {
        return jdbc.sql(UNUSED_FOR_USER).param(userId).query(ROW_MAPPER).list();
    }

    /** @return true if this call spent the code; false if something else already had */
    boolean markUsed(long codeId, Instant now) {
        return jdbc.sql(MARK_USED)
                .param(LocalDateTime.ofInstant(now, ZoneOffset.UTC))
                .param(codeId)
                .update() == 1;
    }

    void deleteAllFor(long userId) {
        jdbc.sql(DELETE_ALL_FOR_USER).param(userId).update();
    }

    /** For the settings screen — "you have 7 recovery codes left". */
    int countUnused(long userId) {
        Integer count = jdbc.sql(COUNT_UNUSED).param(userId).query(Integer.class).single();
        return count == null ? 0 : count;
    }

    /**
     * One unredeemed code as redemption needs it.
     *
     * <p>The plaintext is not a field, for {@link PasswordResetTokenRow}'s
     * reason: it was never in the database and must not enter an object that
     * gets logged.
     */
    record StoredRecoveryCode(long id, String codeHash) {
    }
}
