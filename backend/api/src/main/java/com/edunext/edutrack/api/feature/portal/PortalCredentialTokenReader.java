package com.edunext.edutrack.api.feature.portal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/**
 * C-121 · the redemption lookup against {@code client_credential_tokens}.
 *
 * <p>{@link ClientCredentialTokenRepository} — B-126's own class, in this same
 * package — mints and retires tokens but was never asked to look one back up
 * by hash, because B-126 built the issuing half only ("the page the link
 * lands on does not exist yet", its own class javadoc says). That is this
 * task's page, so this is the missing read.
 *
 * <p>Deliberately a new, small class rather than a method added to {@link
 * ClientCredentialTokenRepository}: that file is one this task was told to
 * read and reuse, not edit — B-126 is another developer's already-reviewed
 * work, and CLAUDE.md's ownership rule treats "add a method to a file another
 * stream wrote" the same as any other cross-boundary edit. The two classes
 * read and write the same table by the same column names; nothing here
 * invents a second convention for it.
 *
 * <p>{@code PasswordResetTokenRepository}'s shape one module over: the lookup
 * is by hash and deliberately unfiltered on {@code used_at}/{@code
 * expires_at}, so the caller can tell "already used" from "expired" from
 * "never existed" for its own logging even though all three collapse to one
 * refusal for the caller. {@code markUsed} is the race arbiter — {@code
 * UPDATE ... WHERE used_at IS NULL} — so two tabs redeeming the same link at
 * once cannot both win.
 */
@Repository
class PortalCredentialTokenReader {

    private static final String FIND_BY_HASH = """
            SELECT id, client_account_id, purpose, expires_at, used_at
              FROM client_credential_tokens
             WHERE token_hash = ?
            """;

    private static final String MARK_USED = """
            UPDATE client_credential_tokens
               SET used_at = ?
             WHERE id = ? AND used_at IS NULL
            """;

    private final JdbcClient jdbc;

    PortalCredentialTokenReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Row> findByHash(String tokenHash) {
        return jdbc.sql(FIND_BY_HASH)
                .param(tokenHash)
                .query((rs, n) -> new Row(
                        rs.getLong("id"),
                        rs.getLong("client_account_id"),
                        rs.getString("purpose"),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("used_at") == null ? null : rs.getTimestamp("used_at").toInstant()))
                .optional();
    }

    /** @return true if this call redeemed the token; false if it was already spent. */
    boolean markUsed(long tokenId, Instant now) {
        return jdbc.sql(MARK_USED)
                .param(java.sql.Timestamp.from(now))
                .param(tokenId)
                .update() == 1;
    }

    record Row(long id, long clientAccountId, String purpose, Instant expiresAt, Instant usedAt) {

        boolean isUsed() {
            return usedAt != null;
        }

        boolean isExpiredAt(Instant now) {
            return !expiresAt.isAfter(now);
        }
    }
}
