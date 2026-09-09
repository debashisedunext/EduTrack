package com.edunext.edutrack.api.feature.portal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * A-130 · verifies a portal login.
 *
 * <p>{@code AuthenticationService}'s three properties, held here for the same
 * reasons and restated because this surface is the more exposed of the two —
 * it is reachable from the public internet by anyone who can guess a username,
 * and {@code PortalUsernames} derives those from the client's own name.
 *
 * <ol>
 *   <li><b>Every outcome costs one Argon2id verification.</b> Returning early
 *       for an unknown username answers "no such login" in microseconds against
 *       the ~175 ms a real verification takes, which is measurable across the
 *       internet and turns this form into a list of which customers have portal
 *       access. The decoy hash below closes that gap.</li>
 *   <li><b>Every failure is the same failure.</b> Unknown account, wrong
 *       password and deactivated account all raise
 *       {@link PortalAuthExceptions.InvalidPortalCredentials}, which has no room
 *       to carry a reason.</li>
 *   <li><b>The password never leaves this method.</b> Not into a log, not into
 *       an exception, not into the returned row.</li>
 * </ol>
 *
 * <h2>The lock is checked after the hash, never before</h2>
 *
 * <p>Deliberate, and it must not be "optimised". Checking first would make a
 * locked account answer faster than an unlocked one — the same timing oracle
 * the decoy closes — and would report {@code account-locked} to somebody who
 * never proved they knew the password, which confirms the account exists.
 * {@code AuthenticationService} carries this note too; both are the same rule.
 *
 * <h2>What this class does not do</h2>
 *
 * <p><b>No refresh token.</b> A portal session lasts one access-token lifetime
 * and then requires signing in again. Rotation, reuse detection and the
 * {@code HttpOnly} cookie are a surface of their own, and the store behind them
 * is keyed on {@code users} ids in a single Redis keyspace — sharing it needs a
 * namespace decision that would be made silently and badly inside a login task.
 * Named here rather than discovered later; it is the first thing A-130's
 * follow-up owes.
 */
@Service
class PortalAuthService {

    private static final Logger log = LoggerFactory.getLogger(PortalAuthService.class);

    /** The staff policy, deliberately identical — see {@code LoginAttemptRecorder}. */
    static final int MAX_FAILED_ATTEMPTS = 5;
    static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private final ClientAccountRepository accounts;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    /**
     * A real Argon2id hash of a value nobody knows, verified against whenever
     * the username does not exist.
     *
     * <p>Computed once per process rather than hardcoded, for
     * {@code AuthenticationService}'s reasons: a literal would need
     * regenerating by hand if the parameters changed, and a malformed one would
     * be rejected in microseconds — reintroducing the very timing difference it
     * exists to remove, silently, and only for unknown usernames.
     */
    private final String decoyHash;

    PortalAuthService(ClientAccountRepository accounts, PasswordEncoder passwordEncoder, Clock clock) {
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.decoyHash = passwordEncoder.encode(randomSecret());
    }

    /**
     * @throws PortalAuthExceptions.InvalidPortalCredentials on every failure,
     *         without saying which
     * @throws PortalAuthExceptions.PortalAccountLocked only after the password
     *         has verified
     */
    @Transactional
    ClientAccountRow authenticate(String username, String rawPassword) {
        ClientAccountRow account = accounts.findByUsername(normalise(username)).orElse(null);

        // Both branches run the full KDF. Do not "optimise" this into an early
        // return when `account` is null — that is the enumeration oracle.
        String hashToVerify = account != null ? account.passwordHash() : decoyHash;
        boolean passwordMatches = passwordEncoder.matches(rawPassword, hashToVerify);

        if (account == null || !passwordMatches || !account.active()) {
            if (account != null && !passwordMatches) {
                // Only a real account with a wrong password counts towards a
                // lock. A deactivated one is not counted towards a lock it can
                // never benefit from, and an unknown username has no row.
                recordFailure(account);
            }
            // DEBUG and without the username: an INFO record of every failed
            // attempt writes a list of valid portal usernames — and eventually
            // a mistyped password — into a file with far wider read access than
            // client_accounts.
            log.debug("Portal login rejected");
            throw new PortalAuthExceptions.InvalidPortalCredentials();
        }

        Instant now = clock.instant();
        if (account.lockedUntil() != null && account.lockedUntil().isAfter(now)) {
            throw new PortalAuthExceptions.PortalAccountLocked(account.lockedUntil());
        }

        accounts.recordSuccessfulLogin(account.id(), now);
        return account;
    }

    private void recordFailure(ClientAccountRow account) {
        accounts.incrementFailedAttempts(account.id());
        if (account.failedAttempts() + 1 >= MAX_FAILED_ATTEMPTS) {
            accounts.applyLock(account.id(), clock.instant().plus(LOCK_DURATION));
            // Worth a line, unlike the refusal above: a lock is a thing somebody
            // will ring up about, and the id is not a credential.
            log.info("portal: account {} locked after {} failed attempts",
                    account.id(), MAX_FAILED_ATTEMPTS);
        }
    }

    /**
     * Usernames are matched case-insensitively but stored as minted. Trimming
     * here rather than in the controller so every caller gets it — a form that
     * posts a trailing space from an autofill is the ordinary case, not an
     * attack.
     */
    private static String normalise(String username) {
        return username == null ? "" : username.trim();
    }

    private static String randomSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
