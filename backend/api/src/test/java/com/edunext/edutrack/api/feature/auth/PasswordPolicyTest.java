package com.edunext.edutrack.api.feature.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A-028 · the stateful half of blueprint §10.3 — no reuse of the last N, and the
 * optional expiry clock.
 *
 * <p>The composition rules are {@code PasswordComplexityValidatorTest}'s; by the
 * time anything here runs they have already passed.
 */
class PasswordPolicyTest {

    private static final long USER_ID = 42L;
    private static final String CANDIDATE = "Chosen-By-Me-9!";
    private static final String OLD_HASH_1 = "{argon2id}$most-recently-retired";
    private static final String OLD_HASH_2 = "{argon2id}$one-before-that";
    private static final String OLD_HASH_3 = "{argon2id}$three-changes-ago";

    private PasswordHistoryRepository history;
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        history = mock(PasswordHistoryRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
    }

    private PasswordPolicy policyWith(PasswordPolicyProperties properties) {
        return new PasswordPolicy(history, passwordEncoder, properties);
    }

    private PasswordPolicy defaultPolicy() {
        return policyWith(new PasswordPolicyProperties(null, null, null));
    }

    // ── the no-reuse rule ───────────────────────────────────────────────────

    @Test
    @DisplayName("a password matching a retired one is refused")
    void refusesAReusedPassword() {
        when(history.findRecentHashes(USER_ID, 3)).thenReturn(List.of(OLD_HASH_1, OLD_HASH_2));
        when(passwordEncoder.matches(CANDIDATE, OLD_HASH_2)).thenReturn(true);

        assertThatExceptionOfType(PasswordReusedException.class)
                .isThrownBy(() -> defaultPolicy().enforceNotReused(USER_ID, CANDIDATE));
    }

    @Test
    @DisplayName("a password matching nothing in the window is accepted")
    void acceptsAFreshPassword() {
        when(history.findRecentHashes(USER_ID, 3)).thenReturn(List.of(OLD_HASH_1, OLD_HASH_2, OLD_HASH_3));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        assertThatNoException()
                .isThrownBy(() -> defaultPolicy().enforceNotReused(USER_ID, CANDIDATE));
    }

    @Test
    @DisplayName("a user with no history is accepted without a comparison")
    void aUserWithNoHistoryIsAccepted() {
        when(history.findRecentHashes(USER_ID, 3)).thenReturn(List.of());

        assertThatNoException()
                .isThrownBy(() -> defaultPolicy().enforceNotReused(USER_ID, CANDIDATE));

        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    /** §10.3 says three. The depth is what bounds both the rule and its cost. */
    @Test
    @DisplayName("exactly historyDepth hashes are requested — three by default")
    void asksForExactlyTheConfiguredDepth() {
        when(history.findRecentHashes(anyLong(), anyInt())).thenReturn(List.of());

        defaultPolicy().enforceNotReused(USER_ID, CANDIDATE);

        verify(history).findRecentHashes(USER_ID, 3);
    }

    /**
     * <b>The timing property.</b> Returning as soon as one hash matches would let
     * a caller tell "matched the most recent" from "matched the oldest" by how
     * long the refusal took, leaking the shape of someone's password history. The
     * loop runs to completion either way.
     */
    @Test
    @DisplayName("every stored hash is compared even after a match is found")
    void doesNotShortCircuitOnAMatch() {
        when(history.findRecentHashes(USER_ID, 3))
                .thenReturn(List.of(OLD_HASH_1, OLD_HASH_2, OLD_HASH_3));
        // The FIRST one matches — a short-circuiting loop would stop here.
        when(passwordEncoder.matches(CANDIDATE, OLD_HASH_1)).thenReturn(true);

        assertThatExceptionOfType(PasswordReusedException.class)
                .isThrownBy(() -> defaultPolicy().enforceNotReused(USER_ID, CANDIDATE));

        verify(passwordEncoder).matches(CANDIDATE, OLD_HASH_1);
        verify(passwordEncoder).matches(CANDIDATE, OLD_HASH_2);
        verify(passwordEncoder).matches(CANDIDATE, OLD_HASH_3);
    }

    /**
     * Depth 0 is the honest way to express "we do not want this rule", rather
     * than leaving it configured and silently unenforced.
     */
    @Test
    @DisplayName("depth 0 disables the rule and touches neither the database nor the encoder")
    void depthZeroDisablesTheRule() {
        PasswordPolicy policy = policyWith(new PasswordPolicyProperties(0, null, null));

        assertThatNoException().isThrownBy(() -> policy.enforceNotReused(USER_ID, CANDIDATE));

        verify(history, never()).findRecentHashes(anyLong(), anyInt());
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    @DisplayName("a larger depth is honoured")
    void honoursALargerDepth() {
        PasswordPolicy policy = policyWith(new PasswordPolicyProperties(10, null, null));
        when(history.findRecentHashes(anyLong(), anyInt())).thenReturn(List.of());

        policy.enforceNotReused(USER_ID, CANDIDATE);

        verify(history).findRecentHashes(USER_ID, 10);
    }

    // ── recording a retired password ────────────────────────────────────────

    @Test
    @DisplayName("the retired hash is filed and the window is pruned")
    void recordsAndPrunes() {
        defaultPolicy().recordRetired(USER_ID, OLD_HASH_1);

        verify(history).insert(USER_ID, OLD_HASH_1);
        verify(history).pruneBeyond(USER_ID, 3);
    }

    @Test
    @DisplayName("depth 0 records nothing")
    void depthZeroRecordsNothing() {
        policyWith(new PasswordPolicyProperties(0, null, null)).recordRetired(USER_ID, OLD_HASH_1);

        verify(history, never()).insert(anyLong(), anyString());
    }

    /**
     * The rows pruning removes can never be read again, so failing the password
     * change over a cleanup DELETE would trade a correctness-neutral storage cost
     * for a user who cannot change their password.
     */
    @Test
    @DisplayName("a failed prune does not fail the password change")
    void aFailedPruneIsNotFatal() {
        doThrow(new org.springframework.dao.QueryTimeoutException("busy"))
                .when(history).pruneBeyond(anyLong(), anyInt());

        assertThatNoException()
                .isThrownBy(() -> defaultPolicy().recordRetired(USER_ID, OLD_HASH_1));

        verify(history).insert(USER_ID, OLD_HASH_1);
    }

    // ── the optional expiry ─────────────────────────────────────────────────

    /**
     * The default, and a deliberate recommendation — see
     * {@link PasswordPolicyProperties}. A password set two years ago is not
     * expired unless somebody switched the rule on.
     */
    @Test
    @DisplayName("expiry is off by default, however old the password is")
    void expiryIsOffByDefault() {
        Instant ancient = Instant.now().minus(Duration.ofDays(1000));

        assertThat(defaultPolicy().isExpired(ancient)).isFalse();
    }

    @Test
    @DisplayName("with expiry on, a password older than maxAge is expired")
    void anOldPasswordExpiresWhenEnabled() {
        PasswordPolicy policy = policyWith(new PasswordPolicyProperties(null, true, Duration.ofDays(90)));

        assertThat(policy.isExpired(Instant.now().minus(Duration.ofDays(91)))).isTrue();
    }

    @Test
    @DisplayName("with expiry on, a password inside maxAge is not expired")
    void arecentPasswordIsFreshWhenEnabled() {
        PasswordPolicy policy = policyWith(new PasswordPolicyProperties(null, true, Duration.ofDays(90)));

        assertThat(policy.isExpired(Instant.now().minus(Duration.ofDays(89)))).isFalse();
    }

    /**
     * The column is NOT NULL and backfilled, so this is defensive rather than
     * reachable — but the safe reading of "I do not know when this changed" is
     * not to lock the user out.
     */
    @Test
    @DisplayName("a null timestamp is never treated as expired")
    void aNullTimestampIsNotExpired() {
        PasswordPolicy policy = policyWith(new PasswordPolicyProperties(null, true, Duration.ofDays(90)));

        assertThat(policy.isExpired(null)).isFalse();
    }

    // ── the properties themselves ───────────────────────────────────────────

    @Test
    @DisplayName("defaults are §10.3's: depth 3, expiry off, 90 days")
    void defaultsMatchTheBlueprint() {
        PasswordPolicyProperties properties = new PasswordPolicyProperties(null, null, null);

        assertThat(properties.historyDepth()).isEqualTo(3);
        assertThat(properties.expiryEnabled()).isFalse();
        assertThat(properties.maxAge()).isEqualTo(Duration.ofDays(90));
        assertThat(properties.historyEnforced()).isTrue();
    }

    @Test
    @DisplayName("a negative depth fails startup rather than behaving as zero")
    void aNegativeDepthIsRejected() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new PasswordPolicyProperties(-1, null, null));
    }

    /**
     * A zero maxAge expires every password the instant it is set, which is an
     * unbreakable change-password loop for every user at once. Better to refuse
     * to start than to serve that.
     */
    @Test
    @DisplayName("a non-positive maxAge fails startup")
    void aZeroMaxAgeIsRejected() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new PasswordPolicyProperties(null, true, Duration.ZERO));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new PasswordPolicyProperties(null, true, Duration.ofDays(-1)));
    }

    /**
     * Cost is linear in the depth — each entry is an Argon2id verification at
     * 64 MB. Ten is somebody's deliberate choice to pay for ten.
     */
    @Test
    @DisplayName("the cost of the rule is exactly one verification per stored hash")
    void costIsOneVerificationPerStoredHash() {
        when(history.findRecentHashes(USER_ID, 3))
                .thenReturn(List.of(OLD_HASH_1, OLD_HASH_2, OLD_HASH_3));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        defaultPolicy().enforceNotReused(USER_ID, CANDIDATE);

        verify(passwordEncoder, times(3)).matches(eq(CANDIDATE), anyString());
    }
}
