package com.edunext.edutrack.api.feature.auth;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * A-027 · {@code password_reset_tokens}. Blueprint §10.3, screen S-02.
 *
 * <p><b>Every method here speaks in hashes, never in tokens.</b> The raw value
 * is hashed by {@link PasswordResetTokenIssuer} before it reaches this class, so
 * there is no code path — present or future — by which a live reset credential
 * can be written to the database or appear in a query log. That is the same
 * boundary {@code AuthUserRepository} keeps for passwords: the repository
 * persists a digest someone else derived.
 *
 * <p>Plain SQL through {@link JdbcClient} rather than JPA, matching
 * {@code AuthUserRepository} — B-005 owns the entity model and a reset token is
 * not part of it.
 */
@Repository
class PasswordResetTokenRepository {

    private static final String INSERT = """
            INSERT INTO password_reset_tokens (user_id, token_hash, expires_at)
            VALUES (?, ?, ?)
            """;

    /**
     * The redemption lookup. Deliberately <b>unfiltered</b> on {@code used_at}
     * and {@code expires_at}.
     *
     * <p>Filtering here would collapse "already redeemed", "expired" and "never
     * existed" into one empty result, and the service could then only answer
     * with a single generic refusal. That is the right answer to the
     * <i>caller</i> — see {@link InvalidResetTokenException} — but the service
     * still needs to tell the three apart to log them, because "this link was
     * redeemed twice" is a security event and "this link expired" is a Tuesday.
     * The row is fetched whole and judged in Java.
     */
    private static final String FIND_BY_HASH = """
            SELECT prt.id, prt.user_id, prt.token_hash, prt.expires_at, prt.used_at
              FROM password_reset_tokens prt
             WHERE prt.token_hash = ?
            """;

    /**
     * Marks one token redeemed, and <b>only if it is still unredeemed</b>.
     *
     * <p>{@code AND used_at IS NULL} is not belt-and-braces. Two requests
     * carrying the same emailed link can arrive together — a double-click, or a
     * mail client prefetching the URL — and a read-then-write would let both see
     * {@code used_at IS NULL} and both proceed. The predicate makes the update
     * itself the arbiter: exactly one caller can ever affect a row, and the
     * other sees zero rows and is refused. This is the same reasoning
     * {@code RefreshTokenStore#claim} applies to rotation, expressed in SQL
     * rather than in Redis.
     */
    private static final String MARK_USED = """
            UPDATE password_reset_tokens
               SET used_at = ?
             WHERE id = ? AND used_at IS NULL
            """;

    /**
     * A-027 · every other outstanding token for this user dies with the
     * redemption.
     *
     * <p>Someone who clicked "forgot password" three times has three live links
     * in their inbox. Redeeming one must retire the rest: a link that still
     * works after the password has already been changed is a second, unmonitored
     * way into the account, sitting in a mailbox that may itself be the thing
     * that was compromised.
     *
     * <p>Marked used rather than deleted, for the reason the migration gives —
     * the row is the evidence.
     */
    private static final String INVALIDATE_OUTSTANDING_FOR_USER = """
            UPDATE password_reset_tokens
               SET used_at = ?
             WHERE user_id = ? AND used_at IS NULL
            """;

    private static final RowMapper<PasswordResetTokenRow> ROW_MAPPER = (rs, rowNum) ->
            new PasswordResetTokenRow(
                    rs.getLong("id"),
                    rs.getLong("user_id"),
                    toInstant(rs.getObject("expires_at", LocalDateTime.class)),
                    toInstant(rs.getObject("used_at", LocalDateTime.class)));

    /**
     * {@code DATETIME(6)} carries no zone and PLAN.md §3.1 stores UTC, so the
     * column is read as a {@link LocalDateTime} and stamped UTC explicitly.
     * Reading it as a {@code Timestamp} would let the driver reinterpret it in
     * the JVM's default zone — which on a machine set to Asia/Kolkata shifts
     * every expiry by five and a half hours, in the direction that makes tokens
     * live longer.
     */
    private static Instant toInstant(LocalDateTime utc) {
        return utc == null ? null : utc.toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime toColumn(Instant utc) {
        return LocalDateTime.ofInstant(utc, ZoneOffset.UTC);
    }

    private final JdbcClient jdbc;

    PasswordResetTokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @param tokenHash SHA-256 hex of the token. Never the token itself. */
    void insert(long userId, String tokenHash, Instant expiresAt) {
        jdbc.sql(INSERT)
                .param(userId)
                .param(tokenHash)
                .param(toColumn(expiresAt))
                .update();
    }

    Optional<PasswordResetTokenRow> findByHash(String tokenHash) {
        return jdbc.sql(FIND_BY_HASH).param(tokenHash).query(ROW_MAPPER).optional();
    }

    /**
     * @return true if this call redeemed the token; false if it was already
     *         redeemed, which the caller must treat as a refusal rather than as
     *         a no-op
     */
    boolean markUsed(long tokenId, Instant now) {
        return jdbc.sql(MARK_USED).param(toColumn(now)).param(tokenId).update() == 1;
    }

    /** @return how many other live links were retired — for the log line, not for control flow */
    int invalidateOutstandingFor(long userId, Instant now) {
        return jdbc.sql(INVALIDATE_OUTSTANDING_FOR_USER).param(toColumn(now)).param(userId).update();
    }
}
