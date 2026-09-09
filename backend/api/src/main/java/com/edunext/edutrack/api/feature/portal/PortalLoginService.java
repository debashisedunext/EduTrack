package com.edunext.edutrack.api.feature.portal;

import com.edunext.edutrack.api.feature.auth.Digests;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * C-121 · CP-01's whole backend: verify credentials, redeem a credential
 * link, set the first real password.
 *
 * <h2>The ambiguity this resolves, and how</h2>
 *
 * <p>The task line only says "portal login + forced password change". Reading
 * {@code client_accounts.password_hash NOT NULL} beside {@code
 * ClientCredentialTokens}' javadoc — "an unguessable password with no
 * plaintext anywhere... the link is the only way in" — settles it: a newly
 * created or reset account has a password <b>nobody knows, including the
 * client</b>, so the first entry can only ever be link-based. {@link #redeem}
 * is therefore treated as a login in its own right (it authenticates and
 * mints a session), not a prelude to one — there is no password to type yet.
 * {@link #login} is what an <em>ordinary</em> subsequent sign-in uses, once
 * {@link #setPassword} has cleared {@code must_change_password}. Recorded
 * here because the task text left it to judgement rather than stating it.
 *
 * <h2>Same login-timing discipline as the staff path</h2>
 *
 * <p>{@code AuthenticationService}'s three properties, restated for a
 * different table: every outcome costs one Argon2id verification (the decoy
 * hash), every failure looks the same to the caller, the password never
 * leaves this method.
 */
@Service
class PortalLoginService {

    private static final Logger log = LoggerFactory.getLogger(PortalLoginService.class);

    private final ClientAccountRepository accounts;
    private final PortalCredentialTokenReader tokens;
    private final PasswordEncoder passwordEncoder;
    private final PortalAccessTokenIssuer accessTokens;
    private final PortalRefreshTokenIssuer refreshTokens;
    private final PortalRefreshTokenStore refreshTokenStore;
    private final PortalLoginAttemptRecorder attempts;
    private final Clock clock;

    /** A real Argon2id hash of a value nobody knows — {@code AuthenticationService}'s decoy, one table over. */
    private final String decoyHash;

    /**
     * {@code @Autowired} is not decorative — {@code ObClientWriteService}'s own
     * note, proved again here: two constructors and no annotation is not an
     * ambiguity Spring resolves, it is a context that fails to start with "No
     * default constructor found".
     */
    @Autowired
    PortalLoginService(ClientAccountRepository accounts,
                       PortalCredentialTokenReader tokens,
                       PasswordEncoder passwordEncoder,
                       PortalAccessTokenIssuer accessTokens,
                       PortalRefreshTokenIssuer refreshTokens,
                       PortalRefreshTokenStore refreshTokenStore,
                       PortalLoginAttemptRecorder attempts) {
        this(accounts, tokens, passwordEncoder, accessTokens, refreshTokens, refreshTokenStore, attempts,
                Clock.systemUTC());
    }

    /** Test seam — a fixed clock. */
    PortalLoginService(ClientAccountRepository accounts,
                       PortalCredentialTokenReader tokens,
                       PasswordEncoder passwordEncoder,
                       PortalAccessTokenIssuer accessTokens,
                       PortalRefreshTokenIssuer refreshTokens,
                       PortalRefreshTokenStore refreshTokenStore,
                       PortalLoginAttemptRecorder attempts,
                       Clock clock) {
        this.accounts = accounts;
        this.tokens = tokens;
        this.passwordEncoder = passwordEncoder;
        this.accessTokens = accessTokens;
        this.refreshTokens = refreshTokens;
        this.refreshTokenStore = refreshTokenStore;
        this.attempts = attempts;
        this.clock = clock;
        this.decoyHash = passwordEncoder.encode(randomSecret());
    }

    record Signed(ClientAccountRow account, PortalAccessToken accessToken, Optional<ResponseCookie> refreshCookie) {
    }

    /**
     * Ordinary username+password sign-in, for an account that has already
     * set its own password.
     *
     * @throws PortalInvalidCredentialsException unknown username, wrong
     *         password or a deactivated account — one refusal for all three
     * @throws PortalAccountLockedException reachable only once the password
     *         has already verified
     */
    @Transactional
    Signed login(String username, String rawPassword) {
        ClientAccountRow account = accounts.findByUsername(username).orElse(null);

        // Both branches pay the same Argon2id verify. Do not shortcut this
        // when `account` is null — that is the enumeration oracle A-020
        // closes for staff and is closed here for the same reason.
        String hashToVerify = account != null ? account.passwordHash() : decoyHash;
        boolean matches = passwordEncoder.matches(rawPassword, hashToVerify);

        if (account == null || !matches || !account.active()) {
            if (account != null && !matches) {
                // Committed in its own transaction via PortalLoginAttemptRecorder
                // — see that class's javadoc for why this must not be a private
                // method or an increment inside this @Transactional method: both
                // would be undone by the throw two lines below.
                attempts.recordFailure(account, clock.instant());
            }
            log.debug("portal auth: login rejected");
            throw new PortalInvalidCredentialsException();
        }

        if (account.lockedUntil() != null && account.lockedUntil().isAfter(clock.instant())) {
            throw new PortalAccountLockedException(account.lockedUntil());
        }

        Instant now = clock.instant();
        accounts.recordSuccessfulLogin(account.id(), now);
        return sign(account);
    }

    /**
     * Redeems a one-time credential link — the newly-created and reset-
     * password paths' shared entry point, per this class's own note on why
     * there is no other way in for either.
     *
     * @throws PortalInvalidCredentialTokenException unknown, expired or
     *         already-used token, or one whose account has since been
     *         deactivated — one refusal for all of them, {@code
     *         ResetPasswordService}'s reasoning: an unauthenticated caller
     *         must not be able to tell which reason it was
     */
    @Transactional
    Signed redeem(String rawToken) {
        Instant now = clock.instant();
        PortalCredentialTokenReader.Row row = tokens.findByHash(Digests.sha256Hex(rawToken))
                .orElseThrow(PortalInvalidCredentialTokenException::new);

        if (row.isUsed() || row.isExpiredAt(now)) {
            throw new PortalInvalidCredentialTokenException();
        }

        ClientAccountRow account = accounts.findById(row.clientAccountId()).orElse(null);
        if (account == null || !account.active()) {
            throw new PortalInvalidCredentialTokenException();
        }

        // The race arbiter: `UPDATE ... WHERE used_at IS NULL`. Two tabs
        // redeeming the same link at once can only ever have one winner.
        if (!tokens.markUsed(row.id(), now)) {
            log.warn("portal auth: credential token for account {} lost the redemption race", account.id());
            throw new PortalInvalidCredentialTokenException();
        }

        accounts.recordSuccessfulLogin(account.id(), now);
        return sign(account);
    }

    /**
     * Sets the client's own password, clearing {@code must_change_password}.
     *
     * <p>No current-password check — {@link PortalAuthDtos.PortalSetPasswordRequest}'s
     * own note explains why one is not askable. The caller is already
     * authenticated as this account by a CLIENT access token; that is the
     * proof this operation asks for.
     */
    @Transactional
    void setPassword(long accountId, String newPassword) {
        accounts.setPassword(accountId, passwordEncoder.encode(newPassword));
    }

    /**
     * A-024's shape, one principal type and one simplification over — see
     * {@link PortalRefreshTokenStore}'s class note on what is not reproduced.
     *
     * <p>Re-reads the account rather than trusting anything cached, so a
     * deactivation between logins takes effect at the next refresh —
     * {@code AuthenticationService#resolveActiveUser}'s own reasoning.
     *
     * @throws PortalInvalidRefreshTokenException missing, unknown, expired,
     *         or the account has since been deactivated
     */
    @Transactional
    Signed refresh(String refreshTokenValue) {
        if (refreshTokenValue == null || refreshTokenValue.isBlank()) {
            throw new PortalInvalidRefreshTokenException();
        }
        StoredPortalRefreshToken stored = refreshTokenStore.find(refreshTokenValue)
                .orElseThrow(PortalInvalidRefreshTokenException::new);

        // Claim first: two simultaneous refreshes on one cookie must not both
        // succeed. The loser sees no live token and is refused.
        if (!refreshTokenStore.claim(refreshTokenValue)) {
            throw new PortalInvalidRefreshTokenException();
        }

        ClientAccountRow account = accounts.findById(stored.clientAccountId()).orElse(null);
        if (account == null || !account.active()) {
            throw new PortalInvalidRefreshTokenException();
        }

        return new Signed(account, accessTokens.issue(account), Optional.of(refreshTokens.rotate(stored)));
    }

    /** Ends this session's refresh token. Idempotent — an absent or already-consumed cookie is not an error. */
    void logout(String refreshTokenValue) {
        if (refreshTokenValue != null && !refreshTokenValue.isBlank()) {
            refreshTokenStore.discard(refreshTokenValue);
        }
    }

    // ── plumbing ─────────────────────────────────────────────────────────

    private Signed sign(ClientAccountRow account) {
        return new Signed(account, accessTokens.issue(account), refreshTokens.issue(account.id()));
    }

    private static String randomSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
