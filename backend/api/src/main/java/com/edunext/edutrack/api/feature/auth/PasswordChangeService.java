package com.edunext.edutrack.api.feature.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * A-026 · setting your own password, and the only way
 * {@code must_change_password} is ever cleared. Blueprint §10.1, screen S-03.
 *
 * <h2>Order of operations, and why it is this order</h2>
 *
 * <ol>
 *   <li><b>Verify the bearer.</b> Whose password is being changed is decided by a
 *       signature, never by anything in the body — a {@code userId} field on the
 *       request would be an endpoint for re-passwording strangers.</li>
 *   <li><b>Re-read the row.</b> The token is up to fifteen minutes old; an
 *       account deactivated in that window must not be able to set a new
 *       password and walk back in. Same reasoning as
 *       {@link AuthenticationService#resolveActiveUser}.</li>
 *   <li><b>Verify {@code currentPassword} before anything else about the new
 *       one.</b> A wrong current password is a 401 whatever the new one looks
 *       like. Validating the replacement first would let someone holding a
 *       borrowed token probe the password policy, and — worse — would report a
 *       policy error on a request that was never going to be honoured, which
 *       reads as "you nearly had it".</li>
 *   <li><b>Reject an unchanged password.</b> See
 *       {@link PasswordUnchangedException} — this is the whole point of a forced
 *       change.</li>
 *   <li><b>Write.</b> Hash and store, clearing the flag in the same statement.</li>
 *   <li><b>Revoke the token that did it.</b> See below.</li>
 * </ol>
 *
 * <h2>Why a successful change revokes the caller's own access token</h2>
 *
 * <p>The token in the caller's hand still carries
 * {@code mustChangePassword: true} — claims are minted once and never mutate.
 * Left alone, {@link PasswordChangeGate} would keep refusing that token for up to
 * fifteen minutes: the user changes their password, gets a 204, and is then
 * locked out of the application by the very flag they just cleared. Blacklisting
 * the {@code jti} pushes the client through {@code POST /auth/refresh}, which
 * re-reads the row and mints a successor with no claim — a single extra round
 * trip that the frontend's 401 interceptor already performs for expiry.
 *
 * <p><b>It ends this session's token, not every session.</b> The contract's
 * {@code /me/password} promises only "Changed"; it is {@code /auth/reset-password}
 * (A-027) that promises "all sessions revoked", and it needs an index from user
 * to token families that does not exist yet. Revoking the others here would also
 * be surprising: changing your password from your laptop is not a reason to sign
 * your phone out mid-call. What that does leave open — a session on another
 * device continuing on the old password — is stated in the backlog rather than
 * hidden here, and closes with A-027.
 *
 * <h2>Two gaps, both named on purpose</h2>
 *
 * <p><b>No composition policy and no reuse history.</b> The replacement is bounded
 * 8–128 by {@link ChangePasswordRequest} and must differ from the current one, and
 * that is all. Upper/lower/digit/symbol and no-reuse-of-last-3 are A-028, which
 * has to create the history table first.
 *
 * <p><b>No rate limit on {@code currentPassword} guesses.</b> A-021's counter
 * guards the login form and is deliberately <i>not</i> incremented here: someone
 * holding a stolen access token could otherwise spend five wrong guesses locking
 * the real user out of the login screen, turning a protective control into a
 * denial of service delivered by an attacker. The exposure that leaves — an
 * attacker with a valid token brute-forcing the current password to achieve full
 * takeover — belongs with A-074's rate limiting, alongside the two other holes
 * {@link AuthenticationService} lists.
 */
@Service
class PasswordChangeService {

    private static final Logger log = LoggerFactory.getLogger(PasswordChangeService.class);

    private final AccessTokenVerifier accessTokens;
    private final AuthUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenBlacklist blacklist;
    private final PasswordPolicy passwordPolicy;

    PasswordChangeService(AccessTokenVerifier accessTokens,
                          AuthUserRepository users,
                          PasswordEncoder passwordEncoder,
                          AccessTokenBlacklist blacklist,
                          PasswordPolicy passwordPolicy) {
        this.accessTokens = accessTokens;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.blacklist = blacklist;
        this.passwordPolicy = passwordPolicy;
    }

    /**
     * @param authorizationHeader the raw {@code Authorization} header, or null
     * @throws InvalidAccessTokenException    no usable token, or the account is
     *                                        gone or deactivated
     * @throws InvalidCurrentPasswordException {@code currentPassword} is wrong
     * @throws PasswordUnchangedException     the replacement is the current one
     * @throws PasswordReusedException        A-028 · the replacement is one of
     *                                        the last few this account used
     */
    @Transactional
    void change(String authorizationHeader, ChangePasswordRequest request) {
        Jwt accessToken = accessTokens.verify(authorizationHeader);
        long userId = accessTokens.userIdOf(accessToken);

        AuthUserRow user = users.findById(userId).orElse(null);
        if (user == null || !user.active()) {
            log.info("auth: password change refused for user {} — {}",
                    userId, user == null ? "no such user" : "account deactivated");
            throw new InvalidAccessTokenException();
        }

        if (!passwordEncoder.matches(request.currentPassword(), user.passwordHash())) {
            // Not counted towards A-021's lockout — see the class javadoc for
            // why that would hand an attacker a denial of service.
            log.info("auth: password change refused for user {} — current password incorrect", userId);
            throw new InvalidCurrentPasswordException();
        }

        // A plain equality check, not a second `matches()`. `currentPassword` has
        // just been proved to be this user's real password, so comparing the two
        // plaintexts answers the same question as hashing the new one against the
        // stored digest — for one fewer Argon2id run at 64 MB. Constant time is
        // not required: both operands are the caller's own input, so there is no
        // secret here they do not already hold.
        if (request.newPassword().equals(request.currentPassword())) {
            throw new PasswordUnchangedException();
        }

        // A-028 · blueprint §10.3's no-reuse rule. Runs after the two cheap
        // checks above and after `currentPassword` has verified, so a caller who
        // cannot prove who they are never triggers three Argon2id verifications
        // at 64 MB each — the composition rules are already enforced by
        // @ValidPassword before this method is entered.
        passwordPolicy.enforceNotReused(userId, request.newPassword());

        // A-028 · the outgoing hash joins history BEFORE the row is overwritten.
        // Read from `user`, which still holds the pre-change value; taking it
        // from the database after the UPDATE would file the new password as
        // though it were retired, and the window would be permanently off by one.
        passwordPolicy.recordRetired(userId, user.passwordHash());

        int updated = users.updatePassword(userId, passwordEncoder.encode(request.newPassword()));
        if (updated != 1) {
            // The row was read a few milliseconds ago, so this is a delete racing
            // the change. Refused rather than reported as success, because a 204
            // here would tell the user their password is now something it is not.
            log.warn("auth: password change for user {} updated {} rows", userId, updated);
            throw new InvalidAccessTokenException();
        }

        revokeTheTokenThatDidThis(accessToken);

        log.info("auth: user {} changed their password; must_change_password cleared", userId);
    }

    /**
     * <b>Degrades rather than fails.</b> Redis being unreachable must not turn
     * away a password change that has already been written — the same posture
     * {@code RefreshTokenIssuer} takes when the store is down at login, and the
     * alternative is a cache outage that blocks users from rotating credentials,
     * which is precisely when they are most likely to be rotating them.
     *
     * <p>The cost of the fallback is bounded and small: the caller keeps a token
     * carrying a stale {@code mustChangePassword} claim until it expires, so
     * under A-032 they see up to fifteen minutes of 403s — or none at all if they
     * simply sign in again. Nothing is left insecure; the change is committed.
     */
    private void revokeTheTokenThatDidThis(Jwt accessToken) {
        Instant expiresAt = accessToken.getExpiresAt();
        if (expiresAt == null) {
            // `exp` is required by the decoder's validators, so this is the type
            // system being nullable rather than a reachable state. A conservative
            // window, never "skip" — the same fallback LogoutService takes.
            expiresAt = Instant.now().plus(Duration.ofHours(1));
        }
        try {
            blacklist.revoke(accessToken.getId(), expiresAt);
        } catch (DataAccessException e) {
            log.warn("auth: password changed but the access token {} could not be revoked — "
                    + "its stale must-change claim stands until it expires", accessToken.getId(), e);
        }
    }
}
