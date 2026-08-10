package com.edunext.edutrack.api.feature.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * A-027 · {@code POST /auth/reset-password}. Blueprint §10.3, screen S-02.
 *
 * <h2>This is the one endpoint that hands over an account without a password</h2>
 *
 * <p>Everything else in the auth package verifies something the caller already
 * knew — a password, a signed token, a cookie this server issued. This verifies
 * only that the caller can read one mailbox. That makes it the highest-value
 * target in the feature, and it is why the checks below are ordered so that
 * every one of them runs before a single byte is written.
 *
 * <h2>Order of operations</h2>
 *
 * <ol>
 *   <li><b>Hash the presented token and look it up.</b> The lookup is by digest,
 *       so a database that has been read end to end still yields nothing
 *       redeemable.</li>
 *   <li><b>Judge expiry and prior use in Java, not in SQL.</b> The query is
 *       deliberately unfiltered — see
 *       {@code PasswordResetTokenRepository#FIND_BY_HASH} — so the three failure
 *       modes stay distinguishable for the log while collapsing into one
 *       response.</li>
 *   <li><b>Re-read the user, and refuse a deactivated one.</b> The token may be
 *       up to thirty minutes old, and an account disabled in that window must
 *       not be recoverable by the person who was just removed from it.</li>
 *   <li><b>Redeem the token conditionally.</b> {@code UPDATE … WHERE used_at IS
 *       NULL} is the arbiter, not a prior read — two clicks on the same link
 *       race, and exactly one may win.</li>
 *   <li><b>Write the password.</b> Only now, and only once the redemption has
 *       been won.</li>
 *   <li><b>Retire every other outstanding link</b> for that user.</li>
 *   <li><b>Revoke every session.</b></li>
 * </ol>
 *
 * <h2>Why redemption is marked before the password is written</h2>
 *
 * <p>Both happen in one transaction, so ordering cannot leave one without the
 * other. What it does decide is which way a <i>concurrent</i> pair of requests
 * fails. Claiming first means the loser of the race is refused before it can
 * hash anything, so two simultaneous clicks cannot both set a password — and if
 * they carried different passwords, cannot leave the account holding the one the
 * user did not expect. This is {@code RefreshRotationService}'s "claim, then
 * act" shape, in SQL.
 *
 * <h2>Revoking every session, and what that does not reach</h2>
 *
 * <p>The contract promises "Password changed; all sessions revoked", and it is
 * the promise that matters most here: a user resetting a forgotten password is
 * often doing it <i>because</i> they believe someone else is in the account.
 * Leaving that someone's seven-day refresh token alive would make the reset
 * cosmetic.
 *
 * <p>{@code RefreshTokenStore#revokeSessionsFor} ends every refresh token the
 * user held, on every device, through a cutoff rather than an index — see that
 * method for why. <b>What it cannot reach is an access token already minted.</b>
 * A JWT is valid on its signature alone, so an attacker mid-session keeps
 * working for up to the remaining fifteen minutes before their next refresh is
 * refused. That gap closes when A-032's filter chain consults A-025's blacklist;
 * it is stated here rather than left for someone to discover, because "all
 * sessions revoked" reads as instantaneous and is not.
 *
 * <h2>Not in this task</h2>
 *
 * <p>Password composition rules — upper, lower, digit, symbol — and the
 * no-reuse-of-the-last-three rule are <b>A-028</b>, which has to create the
 * password history table first. {@link ResetPasswordRequest} enforces the
 * contract's 8–128 bounds and nothing more, exactly as A-026's change-password
 * body does.
 */
@Service
class ResetPasswordService {

    private static final Logger log = LoggerFactory.getLogger(ResetPasswordService.class);

    private final PasswordResetTokenRepository tokens;
    private final AuthUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenStore refreshTokens;
    private final RefreshTokenProperties refreshProperties;

    ResetPasswordService(PasswordResetTokenRepository tokens,
                         AuthUserRepository users,
                         PasswordEncoder passwordEncoder,
                         RefreshTokenStore refreshTokens,
                         RefreshTokenProperties refreshProperties) {
        this.tokens = tokens;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokens = refreshTokens;
        this.refreshProperties = refreshProperties;
    }

    /**
     * @throws InvalidResetTokenException for an unknown, expired, already-used
     *                                    token, or one whose account has since
     *                                    been deactivated — deliberately one
     *                                    exception for all of them
     */
    @Transactional
    void reset(String token, String newPassword) {
        Instant now = Instant.now();

        PasswordResetTokenRow row = tokens.findByHash(Digests.sha256Hex(token))
                .orElseThrow(() -> {
                    log.info("auth: password reset refused — token not recognised");
                    return new InvalidResetTokenException();
                });

        if (row.isUsed()) {
            // The one case here worth a raised voice. A token is redeemed once
            // and the link then sits in a mailbox; a second presentation means
            // either the user double-clicked, or somebody else has the mail.
            // Logged with the user id so an investigation has a handle, never
            // with the token.
            log.warn("auth: SECURITY — password reset token for user {} presented after it was "
                            + "already redeemed at {}. Benign on a double-click; otherwise the "
                            + "reset mail has been read by someone else.",
                    row.userId(), row.usedAt());
            throw new InvalidResetTokenException();
        }

        if (row.isExpiredAt(now)) {
            log.info("auth: password reset refused for user {} — token expired at {}",
                    row.userId(), row.expiresAt());
            throw new InvalidResetTokenException();
        }

        AuthUserRow user = users.findById(row.userId()).orElse(null);
        if (user == null || !user.active()) {
            log.info("auth: password reset refused for user {} — {}",
                    row.userId(), user == null ? "no such user" : "account deactivated");
            throw new InvalidResetTokenException();
        }

        // The race arbiter. A concurrent click that already won this returns
        // false here and is refused, having written nothing.
        if (!tokens.markUsed(row.id(), now)) {
            log.warn("auth: password reset for user {} lost the redemption race — "
                    + "two requests presented the same token at once", row.userId());
            throw new InvalidResetTokenException();
        }

        int updated = users.updatePasswordAndClearLockout(row.userId(), passwordEncoder.encode(newPassword));
        if (updated != 1) {
            // The user existed a moment ago, so this is a delete racing the
            // reset. Raised rather than reported as success — a 204 here would
            // tell someone their password is now something it is not. The
            // transaction rolls back, including the redemption above.
            log.warn("auth: password reset for user {} updated {} rows", row.userId(), updated);
            throw new InvalidResetTokenException();
        }

        int retired = tokens.invalidateOutstandingFor(row.userId(), now);
        if (retired > 0) {
            log.info("auth: retired {} other outstanding reset link(s) for user {}", retired, row.userId());
        }

        revokeEverySession(row.userId(), now);

        log.info("auth: password reset completed for user {}; all sessions revoked", row.userId());
    }

    /**
     * <b>Degrades rather than fails, and that is a genuinely uncomfortable
     * call.</b>
     *
     * <p>The password has already been written by the time this runs. Letting a
     * Redis outage roll that back would leave the user unable to recover their
     * account at all — with a token now marked used, so they cannot even retry
     * without requesting a fresh mail. That is a hard failure of the recovery
     * path caused by a cache being unavailable.
     *
     * <p>The cost of continuing is bounded and, importantly, is not "the old
     * password still works": it does not, because the hash is already replaced.
     * What survives is any refresh token issued before the reset, for as long as
     * it lives. So a compromised session is not ended as promptly as the contract
     * implies, while the credential itself is genuinely rotated.
     *
     * <p>Logged at ERROR because it is the one degradation here that weakens a
     * security promise rather than an experience, and someone should see it.
     */
    private void revokeEverySession(long userId, Instant cutoff) {
        try {
            refreshTokens.revokeSessionsFor(userId, cutoff, refreshProperties.ttl());
        } catch (DataAccessException e) {
            log.error("auth: password for user {} was reset but their existing sessions could NOT be "
                            + "revoked — refresh tokens issued before {} remain usable until they expire. "
                            + "The password itself is changed.",
                    userId, cutoff, e);
        }
    }
}
