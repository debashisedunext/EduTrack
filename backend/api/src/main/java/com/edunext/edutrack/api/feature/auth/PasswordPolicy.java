package com.edunext.edutrack.api.feature.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * A-028 · the stateful half of blueprint §10.3 — the rules that need the
 * account, not just the request.
 *
 * <p>Composition and length are <b>not</b> here: they are decidable from the
 * submitted string alone, so they live in {@link ValidPassword} and
 * {@code @Size} where springdoc and orval can see them (PLAN.md §2.2, D-4). By
 * the time any method on this class runs, those have already passed — a request
 * that fails them is refused by the framework before a controller is entered.
 *
 * <h2>Why one component and not a check in each service</h2>
 *
 * <p>There are two ways to set a password — {@link PasswordChangeService}
 * (A-026, self-service) and {@link ResetPasswordService} (A-027, forgotten) —
 * and Stream B's Resource Master will add a third when an admin creates a user.
 * A rule copied into each is a rule that will eventually be enforced in two of
 * three places, and the gap will be in whichever path was written last. Both
 * existing callers go through {@link #enforceNotReused} and
 * {@link #recordRetired}, and the third has one obvious thing to call.
 *
 * <h2>The cost, and why the order of checks matters</h2>
 *
 * <p>{@link #enforceNotReused} runs one Argon2id verification per stored hash —
 * at §10.3's parameters that is ~175 ms and 64 MB each, so a depth of three is
 * roughly half a second and 192 MB of transient allocation. That is affordable
 * because setting a password is rare, and it is why this check runs <b>after</b>
 * the free ones: a caller sending {@code "abc"} is refused by {@code @Size}
 * without touching the database, and a caller who gets their own current
 * password wrong is refused by {@link PasswordChangeService} before reaching
 * here. Nothing on the login path ever calls this class.
 */
@Component
class PasswordPolicy {

    private static final Logger log = LoggerFactory.getLogger(PasswordPolicy.class);

    private final PasswordHistoryRepository history;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicyProperties properties;

    PasswordPolicy(PasswordHistoryRepository history,
                   PasswordEncoder passwordEncoder,
                   PasswordPolicyProperties properties) {
        this.history = history;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    /**
     * Blueprint §10.3's "no reuse of last 3".
     *
     * <p><b>Every candidate is compared against every stored hash, with no early
     * exit on a match.</b> The obvious loop returns as soon as one matches, and
     * that is a timing oracle: a caller could tell "matched the most recent" from
     * "matched the oldest" by how long the refusal took, which leaks the shape of
     * someone's password history. Because the loop always runs to completion, a
     * refusal costs exactly what an acceptance costs.
     *
     * @throws PasswordReusedException if the candidate matches any hash in the
     *                                 window
     */
    void enforceNotReused(long userId, String candidatePassword) {
        if (!properties.historyEnforced()) {
            return;
        }

        List<String> recent = history.findRecentHashes(userId, properties.historyDepth());
        if (recent.isEmpty()) {
            return;
        }

        boolean reused = false;
        for (String previousHash : recent) {
            // Deliberately not short-circuiting — see the javadoc.
            reused |= passwordEncoder.matches(candidatePassword, previousHash);
        }

        if (reused) {
            // No hash, no position in the window, no timestamp — see
            // PasswordReusedException for why the user is told none of it, and
            // the log is not the place to write down which of someone's old
            // passwords they just retyped.
            log.info("auth: password change refused for user {} — matches one of the last {} passwords",
                    userId, properties.historyDepth());
            throw new PasswordReusedException();
        }
    }

    /**
     * Files the hash that has just stopped being current, and drops anything
     * that has fallen out of the window.
     *
     * <p><b>Takes the OLD hash, not the new one.</b> History is a record of
     * passwords that are no longer in use; the live one is already in
     * {@code users.password_hash} and does not need a second home. Storing the
     * new hash here instead would put the current password in the window and
     * quietly reduce the effective depth by one.
     *
     * <p>Pruning is best-effort and deliberately not allowed to fail the change:
     * the rows it removes are ones no query will ever read again, so leaving them
     * costs storage rather than correctness. A password change that rolled back
     * because a cleanup DELETE failed would be a far worse outcome than a table
     * that is larger than it needs to be.
     *
     * @param retiredHash the Argon2id hash being replaced
     */
    void recordRetired(long userId, String retiredHash) {
        if (!properties.historyEnforced()) {
            return;
        }

        history.insert(userId, retiredHash);

        try {
            int pruned = history.pruneBeyond(userId, properties.historyDepth());
            if (pruned > 0) {
                log.debug("auth: pruned {} password history row(s) for user {}", pruned, userId);
            }
        } catch (RuntimeException e) {
            log.warn("auth: could not prune password history for user {} — "
                    + "rows beyond the window remain, which affects storage but not the rule", userId, e);
        }
    }

    /**
     * A-028 · §10.3's <i>optional</i> 90-day expiry.
     *
     * <p>Off unless {@code edutrack.auth.password-policy.expiry-enabled} says
     * otherwise — {@link PasswordPolicyProperties} explains why that is the
     * recommended default rather than an oversight.
     *
     * <p><b>Read at login and answered from data already in hand</b>, so an
     * expired password costs no extra query and — importantly — no write. The
     * result is folded into the {@code mustChangePassword} that A-026 already
     * puts in the session body and the access token, so expiry reuses that whole
     * mechanism: {@code PasswordChangeGate} refuses everything but the
     * change-password route, and clearing the flag is the same
     * {@code PATCH /me/password} it always was. A second forced-change pathway
     * would be a second thing to keep in step.
     *
     * @param passwordChangedAt {@code users.password_changed_at}, never null —
     *                          the column is {@code NOT NULL} and backfilled
     */
    boolean isExpired(Instant passwordChangedAt) {
        if (!properties.expiryEnabled() || passwordChangedAt == null) {
            return false;
        }
        return passwordChangedAt.plus(properties.maxAge()).isBefore(Instant.now());
    }
}
