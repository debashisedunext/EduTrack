package com.edunext.edutrack.api.feature.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A-027 · what redeeming a reset token must and must not do.
 *
 * <p>This is the one endpoint in the system that hands over an account without a
 * password, so the tests that matter are the ones proving it refuses: an expired
 * token, a spent token, a token for an account that has since been switched off,
 * and — the one a careless refactor breaks — a second request racing the first.
 */
class ResetPasswordServiceTest {

    private static final long USER_ID = 42L;
    private static final long TOKEN_ID = 7L;
    private static final String RAW_TOKEN = "a-token-value-of-at-least-32-characters";
    private static final String NEW_PASSWORD = "Chosen-By-The-User-9!";
    private static final String NEW_HASH = "{argon2id}$hash-of-the-new-password";

    private PasswordResetTokenRepository tokens;
    private AuthUserRepository users;
    private PasswordEncoder passwordEncoder;
    private RefreshTokenStore refreshTokens;
    private PasswordPolicy passwordPolicy;
    private ResetPasswordService service;

    @BeforeEach
    void setUp() {
        tokens = mock(PasswordResetTokenRepository.class);
        users = mock(AuthUserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        refreshTokens = mock(RefreshTokenStore.class);
        passwordPolicy = mock(PasswordPolicy.class);
        service = new ResetPasswordService(tokens, users, passwordEncoder, refreshTokens,
                new RefreshTokenProperties(Duration.ofDays(7), null, null, null), passwordPolicy);
    }

    private void givenARedeemableToken() {
        when(tokens.findByHash(anyString())).thenReturn(Optional.of(liveToken()));
        when(users.findById(USER_ID)).thenReturn(Optional.of(userRow(true)));
        when(tokens.markUsed(eq(TOKEN_ID), any())).thenReturn(true);
        when(passwordEncoder.encode(NEW_PASSWORD)).thenReturn(NEW_HASH);
        when(users.updatePasswordAndClearLockout(anyLong(), anyString())).thenReturn(1);
    }

    private static PasswordResetTokenRow liveToken() {
        return new PasswordResetTokenRow(
                TOKEN_ID, USER_ID, Instant.now().plus(Duration.ofMinutes(20)), null);
    }

    private static AuthUserRow userRow(boolean active) {
        return new AuthUserRow(USER_ID, "asha.rao", "asha.rao@edunext.test", "Asha Rao",
                "{argon2id}$old", "DEVELOPER", 3, "Asia/Kolkata",
                active, true, Instant.now(), 4, null);
    }

    // ── the happy path ──────────────────────────────────────────────────────

    @Test
    @DisplayName("a valid token sets the new password hash")
    void setsTheNewPassword() {
        givenARedeemableToken();

        service.reset(RAW_TOKEN, NEW_PASSWORD);

        verify(users).updatePasswordAndClearLockout(USER_ID, NEW_HASH);
    }

    @Test
    @DisplayName("the plaintext never reaches the repository")
    void onlyAHashIsPersisted() {
        givenARedeemableToken();

        service.reset(RAW_TOKEN, NEW_PASSWORD);

        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(users).updatePasswordAndClearLockout(eq(USER_ID), stored.capture());
        assertThat(stored.getValue()).isEqualTo(NEW_HASH).isNotEqualTo(NEW_PASSWORD);
    }

    /** The token is looked up by digest, so the raw value is never a query parameter. */
    @Test
    @DisplayName("the token is looked up by its SHA-256, not by its value")
    void looksUpByDigest() {
        givenARedeemableToken();

        service.reset(RAW_TOKEN, NEW_PASSWORD);

        verify(tokens).findByHash(Digests.sha256Hex(RAW_TOKEN));
        verify(tokens, never()).findByHash(RAW_TOKEN);
    }

    @Test
    @DisplayName("the token is marked used, and every other outstanding link is retired")
    void spendsTheTokenAndRetiresTheRest() {
        givenARedeemableToken();

        service.reset(RAW_TOKEN, NEW_PASSWORD);

        verify(tokens).markUsed(eq(TOKEN_ID), any(Instant.class));
        verify(tokens).invalidateOutstandingFor(eq(USER_ID), any(Instant.class));
    }

    /**
     * The contract's promise. A user resetting a forgotten password is often
     * doing it because they believe someone else is in the account; leaving that
     * someone's seven-day refresh token alive makes the reset cosmetic.
     */
    @Test
    @DisplayName("every session the user had is revoked")
    void revokesEverySession() {
        givenARedeemableToken();

        service.reset(RAW_TOKEN, NEW_PASSWORD);

        verify(refreshTokens).revokeSessionsFor(eq(USER_ID), any(Instant.class), eq(Duration.ofDays(7)));
    }

    /**
     * Claiming before writing is what makes two simultaneous clicks safe: the
     * loser is refused before it can hash anything, so the account cannot end up
     * holding a password the user did not expect.
     */
    @Test
    @DisplayName("the token is claimed before the password is written")
    void claimsBeforeWriting() {
        givenARedeemableToken();

        service.reset(RAW_TOKEN, NEW_PASSWORD);

        InOrder order = inOrder(tokens, users);
        order.verify(tokens).markUsed(eq(TOKEN_ID), any());
        order.verify(users).updatePasswordAndClearLockout(anyLong(), anyString());
    }

    // ── refusals ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an unknown token is refused and writes nothing")
    void anUnknownTokenIsRefused() {
        when(tokens.findByHash(anyString())).thenReturn(Optional.empty());

        assertThatExceptionOfType(InvalidResetTokenException.class)
                .isThrownBy(() -> service.reset(RAW_TOKEN, NEW_PASSWORD));

        verify(users, never()).updatePasswordAndClearLockout(anyLong(), anyString());
    }

    @Test
    @DisplayName("an expired token is refused and writes nothing")
    void anExpiredTokenIsRefused() {
        when(tokens.findByHash(anyString())).thenReturn(Optional.of(new PasswordResetTokenRow(
                TOKEN_ID, USER_ID, Instant.now().minus(Duration.ofMinutes(1)), null)));

        assertThatExceptionOfType(InvalidResetTokenException.class)
                .isThrownBy(() -> service.reset(RAW_TOKEN, NEW_PASSWORD));

        verify(tokens, never()).markUsed(anyLong(), any());
        verify(users, never()).updatePasswordAndClearLockout(anyLong(), anyString());
    }

    /**
     * Single use is the property that stops a link found in a mailbox weeks
     * later from opening the account a second time.
     */
    @Test
    @DisplayName("an already-redeemed token is refused and writes nothing")
    void aSpentTokenIsRefused() {
        when(tokens.findByHash(anyString())).thenReturn(Optional.of(new PasswordResetTokenRow(
                TOKEN_ID, USER_ID, Instant.now().plus(Duration.ofMinutes(20)),
                Instant.now().minus(Duration.ofMinutes(5)))));

        assertThatExceptionOfType(InvalidResetTokenException.class)
                .isThrownBy(() -> service.reset(RAW_TOKEN, NEW_PASSWORD));

        verify(users, never()).updatePasswordAndClearLockout(anyLong(), anyString());
    }

    /**
     * The token may be up to thirty minutes old. An account disabled inside that
     * window must not be recoverable by the person who was just removed from it.
     */
    @Test
    @DisplayName("a token for a deactivated account is refused")
    void aDeactivatedAccountIsRefused() {
        when(tokens.findByHash(anyString())).thenReturn(Optional.of(liveToken()));
        when(users.findById(USER_ID)).thenReturn(Optional.of(userRow(false)));

        assertThatExceptionOfType(InvalidResetTokenException.class)
                .isThrownBy(() -> service.reset(RAW_TOKEN, NEW_PASSWORD));

        verify(tokens, never()).markUsed(anyLong(), any());
        verify(users, never()).updatePasswordAndClearLockout(anyLong(), anyString());
    }

    @Test
    @DisplayName("a token for a user who no longer exists is refused")
    void aVanishedUserIsRefused() {
        when(tokens.findByHash(anyString())).thenReturn(Optional.of(liveToken()));
        when(users.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatExceptionOfType(InvalidResetTokenException.class)
                .isThrownBy(() -> service.reset(RAW_TOKEN, NEW_PASSWORD));
    }

    /**
     * Two clicks on the same emailed link, arriving together. The conditional
     * UPDATE is the arbiter — exactly one can win, and the loser must write
     * nothing rather than proceed on a token someone else just spent.
     */
    @Test
    @DisplayName("losing the redemption race is refused, and sets no password")
    void losingTheRaceIsRefused() {
        when(tokens.findByHash(anyString())).thenReturn(Optional.of(liveToken()));
        when(users.findById(USER_ID)).thenReturn(Optional.of(userRow(true)));
        when(tokens.markUsed(eq(TOKEN_ID), any())).thenReturn(false);

        assertThatExceptionOfType(InvalidResetTokenException.class)
                .isThrownBy(() -> service.reset(RAW_TOKEN, NEW_PASSWORD));

        verify(users, never()).updatePasswordAndClearLockout(anyLong(), anyString());
        verify(refreshTokens, never()).revokeSessionsFor(anyLong(), any(), any());
    }

    @Test
    @DisplayName("a write that updates no row is refused, not reported as success")
    void aNoOpWriteIsRefused() {
        givenARedeemableToken();
        when(users.updatePasswordAndClearLockout(anyLong(), anyString())).thenReturn(0);

        assertThatExceptionOfType(InvalidResetTokenException.class)
                .isThrownBy(() -> service.reset(RAW_TOKEN, NEW_PASSWORD));
    }

    /**
     * The password is already written by the time revocation runs. Rolling that
     * back over a cache outage would leave the user unable to recover at all —
     * with the token already spent, so they cannot even retry.
     */
    @Test
    @DisplayName("an unreachable session store does not undo a password that was already reset")
    void anUnreachableStoreDegrades() {
        givenARedeemableToken();
        doThrow(new QueryTimeoutException("redis is down"))
                .when(refreshTokens).revokeSessionsFor(anyLong(), any(), any());

        assertThatNoException().isThrownBy(() -> service.reset(RAW_TOKEN, NEW_PASSWORD));

        verify(users).updatePasswordAndClearLockout(USER_ID, NEW_HASH);
    }

    // ── A-028 · the password policy ─────────────────────────────────────────

    @Test
    @DisplayName("the no-reuse rule is enforced on the reset path too")
    void enforcesTheNoReuseRule() {
        givenARedeemableToken();

        service.reset(RAW_TOKEN, NEW_PASSWORD);

        verify(passwordPolicy).enforceNotReused(USER_ID, NEW_PASSWORD);
    }

    /**
     * <b>The token survives a policy refusal.</b> Checking after the claim would
     * be simpler to reason about and would burn the reset link every time someone
     * reached for an old favourite — leaving them refused, out of a valid link,
     * and needing a fresh mail to try again.
     */
    @Test
    @DisplayName("a reused password is refused WITHOUT spending the reset token")
    void aReusedPasswordDoesNotBurnTheToken() {
        givenARedeemableToken();
        doThrow(new PasswordReusedException())
                .when(passwordPolicy).enforceNotReused(anyLong(), anyString());

        assertThatExceptionOfType(PasswordReusedException.class)
                .isThrownBy(() -> service.reset(RAW_TOKEN, NEW_PASSWORD));

        verify(tokens, never()).markUsed(anyLong(), any());
        verify(users, never()).updatePasswordAndClearLockout(anyLong(), anyString());
        verify(refreshTokens, never()).revokeSessionsFor(anyLong(), any(), any());
    }

    /**
     * Recovering an account and changing a password from inside a session must
     * not differ in what they accept, or the weaker path becomes the way in.
     * This is the same assertion {@code PasswordChangeServiceTest} makes.
     */
    @Test
    @DisplayName("the hash being replaced is the one recorded as retired")
    void recordsTheOutgoingHashNotTheIncomingOne() {
        givenARedeemableToken();

        service.reset(RAW_TOKEN, NEW_PASSWORD);

        verify(passwordPolicy).recordRetired(USER_ID, "{argon2id}$old");
        verify(passwordPolicy, never()).recordRetired(USER_ID, NEW_HASH);
    }

    @Test
    @DisplayName("the retired hash is recorded before the row is overwritten")
    void recordsHistoryBeforeTheUpdate() {
        givenARedeemableToken();

        service.reset(RAW_TOKEN, NEW_PASSWORD);

        InOrder order = inOrder(passwordPolicy, users);
        order.verify(passwordPolicy).recordRetired(anyLong(), anyString());
        order.verify(users).updatePasswordAndClearLockout(anyLong(), anyString());
    }

    /**
     * An expired or already-spent token must be refused before the policy costs
     * three Argon2id verifications — this endpoint is unauthenticated, so an
     * attacker with a junk token must not be able to buy 192 MB of work with it.
     */
    @Test
    @DisplayName("an invalid token never reaches the expensive reuse check")
    void anInvalidTokenSkipsTheReuseCheck() {
        when(tokens.findByHash(anyString())).thenReturn(Optional.empty());

        assertThatExceptionOfType(InvalidResetTokenException.class)
                .isThrownBy(() -> service.reset(RAW_TOKEN, NEW_PASSWORD));

        verify(passwordPolicy, never()).enforceNotReused(anyLong(), anyString());
    }
}
