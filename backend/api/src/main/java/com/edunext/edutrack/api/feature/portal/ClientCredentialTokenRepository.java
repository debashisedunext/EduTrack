package com.edunext.edutrack.api.feature.portal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * B-126 · {@code client_credential_tokens} — the single-use link that carries a
 * portal login.
 *
 * <p>A-027's {@code PasswordResetTokenRepository} deliberately, down to the
 * method names: hash never plaintext, {@code used_at} as the single-use
 * mechanism, expiry judged on redemption rather than by a sweep. Two token
 * stores that behave differently are two sets of rules for one idea, and the
 * one nobody re-read is the one that lets a spent link work twice.
 *
 * <p>It is a separate table rather than a widened {@code password_reset_tokens}
 * for the reason the migration header gives at length: that table's foreign key
 * is {@code REFERENCES users (id)}, and a client account is deliberately not a
 * user.
 */
@Repository
class ClientCredentialTokenRepository {

    private static final String INSERT = """
            INSERT INTO client_credential_tokens
                   (client_account_id, token_hash, purpose, expires_at, created_by)
            VALUES (?, ?, ?, ?, ?)
            """;

    /**
     * Retires every outstanding token for an account.
     *
     * <p>Run before a new one is minted. A reset that left the previous link
     * alive would mean a mail sitting in an inbox from three months ago is a
     * second way in, and the person who asked for the reset has no idea it is
     * still there.
     *
     * <p>{@code used_at} rather than a delete, so the audit question "how many
     * links were issued for this account" stays answerable.
     */
    private static final String RETIRE_OUTSTANDING = """
            UPDATE client_credential_tokens
               SET used_at = ?
             WHERE client_account_id = ?
               AND used_at IS NULL
            """;

    /**
     * When the most recent link was issued, for the panel's "credential sent"
     * line. Issued rather than delivered — the outbox owns delivery, and a
     * panel claiming a mail arrived would be asserting something this table
     * cannot see.
     */
    private static final String LAST_ISSUED_AT = """
            SELECT MAX(created_at)
              FROM client_credential_tokens
             WHERE client_account_id = ?
            """;

    /**
     * A-130 · the redemption lookup.
     *
     * <p><b>No expiry or {@code used_at} predicate here, deliberately.</b> The
     * obvious query filters both in SQL and returns nothing for a spent link,
     * an expired one and a token that never existed — one answer for three
     * situations, which reads as tidy until somebody follows a link that
     * expired yesterday and is told it is invalid. The row is fetched and the
     * service decides, so "this link has expired, ask for another" can be said
     * without ever saying "that token is real".
     *
     * <p>The distinction stays inside the service: {@code PortalAuthExceptions}
     * answers 410 for all three. What the row buys is a truthful log line and
     * the freedom to change that answer later without changing this query.
     */
    private static final String FIND_BY_HASH = """
            SELECT id, client_account_id, purpose, expires_at, used_at
              FROM client_credential_tokens
             WHERE token_hash = ?
            """;

    /**
     * Spends a link, and <b>only if it has not already been spent</b>.
     *
     * <p>The {@code used_at IS NULL} predicate is the single-use mechanism, not
     * a nicety: two redemptions arriving together both read a null and both
     * proceed, and without it both would set a password. The update that
     * changes no row is the one that loses, and the service refuses it.
     */
    private static final String MARK_USED = """
            UPDATE client_credential_tokens
               SET used_at = ?
             WHERE id = ?
               AND used_at IS NULL
            """;

    private final JdbcClient jdbc;

    ClientCredentialTokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<CredentialTokenRow> findByHash(String tokenHash) {
        return jdbc.sql(FIND_BY_HASH)
                .param(tokenHash)
                .query((rs, rowNum) -> new CredentialTokenRow(
                        rs.getLong("id"),
                        rs.getLong("client_account_id"),
                        rs.getString("purpose"),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("used_at") == null ? null : rs.getTimestamp("used_at").toInstant()))
                .optional();
    }

    /** True when this call is the one that spent the link. */
    boolean markUsed(long tokenId, Instant at) {
        return jdbc.sql(MARK_USED)
                .param(Timestamp.from(at))
                .param(tokenId)
                .update() == 1;
    }

    void insert(long clientAccountId, String tokenHash, String purpose,
                Instant expiresAt, Long createdBy) {
        jdbc.sql(INSERT)
                .param(clientAccountId)
                .param(tokenHash)
                .param(purpose)
                .param(Timestamp.from(expiresAt))
                .param(createdBy)
                .update();
    }

    Optional<Instant> lastIssuedAt(long clientAccountId) {
        return jdbc.sql(LAST_ISSUED_AT)
                .param(clientAccountId)
                .query(Timestamp.class)
                .optional()
                .map(Timestamp::toInstant);
    }

    int retireOutstanding(long clientAccountId, Instant at) {
        return jdbc.sql(RETIRE_OUTSTANDING)
                .param(Timestamp.from(at))
                .param(clientAccountId)
                .update();
    }
}
