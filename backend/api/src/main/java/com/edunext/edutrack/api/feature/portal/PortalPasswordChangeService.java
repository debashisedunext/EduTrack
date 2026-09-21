package com.edunext.edutrack.api.feature.portal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Changing your own portal password — and the only thing that ever clears
 * {@code client_accounts.must_change_password}.
 *
 * <p>The counterpart of {@code PasswordChangeService} one principal type over,
 * and its order of operations is copied deliberately rather than reinvented:
 *
 * <ol>
 *   <li><b>Whose password is decided by the token, never by the body.</b> An
 *       {@code accountId} field on the request would be an endpoint for
 *       re-passwording strangers.</li>
 *   <li><b>Re-read the row.</b> The token is up to one access-token lifetime
 *       old; an account deactivated in that window must not be able to set a
 *       new password and walk back in.</li>
 *   <li><b>Verify {@code currentPassword} before anything about the new one.</b>
 *       Validating the replacement first would let somebody holding a borrowed
 *       token probe the password policy, and would report a policy error on a
 *       request that was never going to be honoured — which reads as "you
 *       nearly had it".</li>
 *   <li><b>Refuse an unchanged password.</b> This is the whole point of a
 *       forced change: the temporary password was read off a staff screen and
 *       possibly said out loud, so keeping it is precisely the outcome the
 *       change exists to prevent.</li>
 *   <li><b>Write</b>, clearing the flag in the same statement.</li>
 * </ol>
 *
 * <h2>Why this hands back a session instead of 204</h2>
 *
 * <p>{@code PasswordChangeService} answers 204 and pushes staff through
 * {@code POST /auth/refresh}, whose successor token carries no must-change
 * claim. <b>The portal has no refresh route</b> — a portal session is one
 * access token with no rotation — so the same 204 would leave the client
 * holding a token that {@code PortalPasswordChangeGate} goes on refusing for up
 * to fifteen minutes. They would change their password successfully and be
 * locked out of the portal by the flag they had just cleared, with the only
 * remedy being to sign in again using a password the form had just told them
 * was replaced.
 *
 * <p>So the new token is minted here, from the row as it now stands, and the
 * client carries on. That is not a way around the gate: it is issued only after
 * the change has been written, so its claim is absent because the fact it
 * reports is absent.
 *
 * <h2>Two gaps, both named rather than hidden</h2>
 *
 * <p><b>No reuse history.</b> {@link PortalPasswordRules} explains why: there
 * is no {@code client_password_history} table, and inventing one here would be
 * schema by side effect. The replacement must differ from the current password
 * and satisfy the strength policy, and that is all.
 *
 * <p><b>No rate limit on {@code currentPassword} guesses.</b> Staff's path has
 * {@code PasswordChangeRateLimiter}, keyed on a {@code users} id and budgeted
 * against {@code /me/password}; pointing it here needs a second budget keyed on
 * an account id rather than a second caller. The exposure it would bound —
 * somebody holding a stolen portal token brute-forcing the current password —
 * is real and is not closed here. Deliberately <b>not</b> charged to
 * {@code client_accounts.failed_attempts} either, for
 * {@code PasswordChangeService}'s reason: an attacker with a token could
 * otherwise spend five wrong guesses locking the real client out of the login
 * screen, turning a protective control into a denial of service delivered on
 * demand.
 */
@Service
class PortalPasswordChangeService {

    private static final Logger log = LoggerFactory.getLogger(PortalPasswordChangeService.class);

    private final ClientAccountRepository accounts;
    private final PortalPasswordRules passwordRules;
    private final PasswordEncoder passwordEncoder;
    private final ClientAccessTokenIssuer tokens;

    PortalPasswordChangeService(ClientAccountRepository accounts,
                                PortalPasswordRules passwordRules,
                                PasswordEncoder passwordEncoder,
                                ClientAccessTokenIssuer tokens) {
        this.accounts = accounts;
        this.passwordRules = passwordRules;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
    }

    /**
     * @param accountId the caller's own {@code client_accounts.id}, taken from
     *                  the verified token by the controller and from nowhere
     *                  else
     * @return a fresh session, minted from the row after the change
     * @throws PortalAuthExceptions.InvalidPortalCredentials the account is gone
     *         or deactivated, or {@code currentPassword} is wrong. One refusal
     *         for all three, matching the login surface's own rule
     * @throws PortalAuthExceptions.PortalPasswordUnchanged  the replacement is
     *         the current password
     * @throws PortalAuthExceptions.WeakPortalPassword       the replacement
     *         does not meet the policy
     */
    @Transactional
    PortalAuthDtos.LoginResponse change(long accountId, String currentPassword, String newPassword) {
        ClientAccountRow account = accounts.findById(accountId).orElse(null);
        if (account == null || !account.active()) {
            log.info("portal: password change refused for account {} — {}",
                    accountId, account == null ? "no such account" : "account deactivated");
            throw new PortalAuthExceptions.InvalidPortalCredentials();
        }

        if (!passwordEncoder.matches(currentPassword, account.passwordHash())) {
            // Not counted towards the login lockout — see the class javadoc for
            // why counting it there would hand an attacker a denial of service
            // against the real client.
            log.info("portal: password change refused for account {} — current password incorrect", accountId);
            throw new PortalAuthExceptions.InvalidPortalCredentials();
        }

        /*
          A plain equality check, not a second `matches()`. `currentPassword`
          has just been proved to be this account's real password, so comparing
          the two plaintexts answers the same question as hashing the new one
          against the stored digest, for one fewer Argon2id run at 64 MB.
          Constant time is not required: both operands are the caller's own
          input, so there is no secret here they do not already hold.
        */
        if (newPassword != null && newPassword.equals(currentPassword)) {
            throw new PortalAuthExceptions.PortalPasswordUnchanged();
        }

        // After the two cheap checks above, so a caller who cannot prove who
        // they are never reaches the policy and never learns it.
        passwordRules.enforce(newPassword);

        accounts.setPassword(account.id(), passwordEncoder.encode(newPassword));
        log.info("portal: account {} changed its password; must_change_password cleared", accountId);

        /*
          Re-read rather than reusing `account`. That row was loaded before the
          UPDATE and still says `must_change_password = 1`; minting a token from
          it would stamp the claim the change just cleared, and the client would
          be refused by the gate holding the only token they have. The successor
          has to be built from the state that now exists.
        */
        ClientAccountRow changed = accounts.findById(accountId)
                .orElseThrow(PortalAuthExceptions.InvalidPortalCredentials::new);

        ClientAccessTokenIssuer.Minted minted = tokens.issue(changed);
        return new PortalAuthDtos.LoginResponse(minted.value(), minted.expiresInSeconds(),
                changed.mustChangePassword(), PortalAuthDtos.Client.of(changed));
    }
}
