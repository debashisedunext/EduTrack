package com.edunext.edutrack.api.feature.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A-026 · what changing a password must and must not do.
 *
 * <p>The assertions that matter most here are the ones about what does <b>not</b>
 * happen. A service that writes the new hash before verifying the old password
 * passes every happy-path test, works perfectly in manual use, and turns a
 * fifteen-minute stolen token into permanent account takeover.
 */
class PasswordChangeServiceTest {

    private static final long USER_ID = 42L;
    private static final String HEADER = "Bearer header.payload.signature";
    private static final String JTI = "f47ac10b-58cc-4372-a567-0e02b2c3d479";
    private static final Instant EXPIRES_AT = Instant.now().plus(Duration.ofMinutes(15));

    private static final String CURRENT = "Temp-Password-1!";
    private static final String CURRENT_HASH = "{argon2id}$hash-of-the-current-password";
    private static final String NEW = "Chosen-By-The-User-9!";
    private static final String NEW_HASH = "{argon2id}$hash-of-the-new-password";

    private JwtDecoder jwtDecoder;
    private AuthUserRepository users;
    private PasswordEncoder passwordEncoder;
    private AccessTokenBlacklist blacklist;
    private PasswordChangeService service;

    @BeforeEach
    void setUp() {
        jwtDecoder = mock(JwtDecoder.class);
        users = mock(AuthUserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        blacklist = mock(AccessTokenBlacklist.class);
        service = new PasswordChangeService(
                new AccessTokenVerifier(jwtDecoder), users, passwordEncoder, blacklist);
    }

    private void givenAValidSession() {
        when(jwtDecoder.decode(anyString())).thenReturn(jwt());
        when(users.findById(USER_ID)).thenReturn(Optional.of(row(true, true)));
        when(passwordEncoder.matches(CURRENT, CURRENT_HASH)).thenReturn(true);
        when(passwordEncoder.encode(NEW)).thenReturn(NEW_HASH);
        when(users.updatePassword(anyLong(), anyString())).thenReturn(1);
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("header.payload.signature")
                .header("alg", "HS256")
                .subject(String.valueOf(USER_ID))
                .jti(JTI)
                .issuedAt(EXPIRES_AT.minus(Duration.ofMinutes(15)))
                .expiresAt(EXPIRES_AT)
                .claim("role", "DEVELOPER")
                .claim(AccessTokenIssuer.MUST_CHANGE_PASSWORD_CLAIM, true)
                .build();
    }

    private static AuthUserRow row(boolean active, boolean mustChangePassword) {
        return new AuthUserRow(USER_ID, "asha.rao", "asha.rao@edunext.test", "Asha Rao",
                CURRENT_HASH, "DEVELOPER", 3, "Asia/Kolkata",
                active, mustChangePassword, 0, null);
    }

    private static ChangePasswordRequest request(String current, String replacement) {
        return new ChangePasswordRequest(current, replacement);
    }

    // ── the happy path ──────────────────────────────────────────────────────

    @Test
    @DisplayName("the new password is hashed and stored, and the flag is cleared with it")
    void storesTheHashAndClearsTheFlag() {
        givenAValidSession();

        service.change(HEADER, request(CURRENT, NEW));

        // updatePassword is the statement that does both — one statement, so
        // there is no state in which the password changed and the flag did not.
        verify(users).updatePassword(USER_ID, NEW_HASH);
    }

    /**
     * The single most important negative in this class: a plaintext password must
     * never reach the repository. A repository that accepted one is one refactor
     * away from logging one.
     */
    @Test
    @DisplayName("the plaintext never reaches the repository")
    void onlyAHashIsPersisted() {
        givenAValidSession();

        service.change(HEADER, request(CURRENT, NEW));

        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(users).updatePassword(eq(USER_ID), stored.capture());
        assertThat(stored.getValue()).isEqualTo(NEW_HASH).isNotEqualTo(NEW);
    }

    /**
     * Whose password changes is decided by the signature on the token, never by
     * anything the caller could put in the body.
     */
    @Test
    @DisplayName("the user is taken from the token's subject")
    void theSubjectDecidesWhosePasswordChanges() {
        givenAValidSession();

        service.change(HEADER, request(CURRENT, NEW));

        verify(users).findById(USER_ID);
        verify(users).updatePassword(eq(USER_ID), anyString());
    }

    // ── refusals ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("no Authorization header is refused, and writes nothing")
    void aMissingHeaderIsRefused() {
        assertThatExceptionOfType(InvalidAccessTokenException.class)
                .isThrownBy(() -> service.change(null, request(CURRENT, NEW)));

        verify(users, never()).updatePassword(anyLong(), anyString());
    }

    /**
     * Accepting an unverified token would let anyone forge a {@code sub} and set
     * a stranger's password — an account-takeover endpoint.
     */
    @Test
    @DisplayName("a forged token is refused, and writes nothing")
    void aForgedTokenIsRefused() {
        when(jwtDecoder.decode(anyString())).thenThrow(new BadJwtException("bad signature"));

        assertThatExceptionOfType(InvalidAccessTokenException.class)
                .isThrownBy(() -> service.change(HEADER, request(CURRENT, NEW)));

        verify(users, never()).updatePassword(anyLong(), anyString());
    }

    /**
     * The token can be up to fifteen minutes old. An account deactivated in that
     * window must not be able to set a new password and walk back in.
     */
    @Test
    @DisplayName("a deactivated account cannot set a new password")
    void aDeactivatedAccountIsRefused() {
        when(jwtDecoder.decode(anyString())).thenReturn(jwt());
        when(users.findById(USER_ID)).thenReturn(Optional.of(row(false, true)));

        assertThatExceptionOfType(InvalidAccessTokenException.class)
                .isThrownBy(() -> service.change(HEADER, request(CURRENT, NEW)));

        verify(users, never()).updatePassword(anyLong(), anyString());
    }

    @Test
    @DisplayName("a token for a user who no longer exists is refused")
    void aVanishedUserIsRefused() {
        when(jwtDecoder.decode(anyString())).thenReturn(jwt());
        when(users.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatExceptionOfType(InvalidAccessTokenException.class)
                .isThrownBy(() -> service.change(HEADER, request(CURRENT, NEW)));
    }

    @Test
    @DisplayName("a wrong current password is refused, and writes nothing")
    void aWrongCurrentPasswordIsRefused() {
        when(jwtDecoder.decode(anyString())).thenReturn(jwt());
        when(users.findById(USER_ID)).thenReturn(Optional.of(row(true, true)));
        when(passwordEncoder.matches(anyString(), eq(CURRENT_HASH))).thenReturn(false);

        assertThatExceptionOfType(InvalidCurrentPasswordException.class)
                .isThrownBy(() -> service.change(HEADER, request("not-the-password", NEW)));

        verify(users, never()).updatePassword(anyLong(), anyString());
        verify(passwordEncoder, never()).encode(anyString());
    }

    /**
     * A-021's lockout guards the login form. Counting failures here would let
     * anyone holding a stolen access token spend five wrong guesses locking the
     * real user out of the login screen — a denial of service delivered by the
     * control meant to protect them.
     */
    @Test
    @DisplayName("a wrong current password does NOT count towards the login lockout")
    void aWrongCurrentPasswordDoesNotFeedTheLockout() {
        when(jwtDecoder.decode(anyString())).thenReturn(jwt());
        when(users.findById(USER_ID)).thenReturn(Optional.of(row(true, true)));
        when(passwordEncoder.matches(anyString(), eq(CURRENT_HASH))).thenReturn(false);

        assertThatExceptionOfType(InvalidCurrentPasswordException.class)
                .isThrownBy(() -> service.change(HEADER, request("wrong", NEW)));

        verify(users, never()).incrementFailedAttempts(anyLong());
        verify(users, never()).applyLock(anyLong(), any());
    }

    /**
     * Without this the forced change is theatre: the flag clears while the
     * administrator-generated password that was emailed in plain text stays live,
     * and the account reads as remediated everywhere.
     */
    @Test
    @DisplayName("submitting the current password back is refused, and writes nothing")
    void anUnchangedPasswordIsRefused() {
        givenAValidSession();

        assertThatExceptionOfType(PasswordUnchangedException.class)
                .isThrownBy(() -> service.change(HEADER, request(CURRENT, CURRENT)));

        verify(users, never()).updatePassword(anyLong(), anyString());
    }

    /**
     * Order matters. A wrong current password is a 401 whatever the replacement
     * looks like — reporting "unchanged" first would confirm to someone holding a
     * borrowed token that their guess was right.
     */
    @Test
    @DisplayName("a wrong current password is reported even when the two fields match")
    void theCurrentPasswordIsCheckedBeforeTheReplacement() {
        when(jwtDecoder.decode(anyString())).thenReturn(jwt());
        when(users.findById(USER_ID)).thenReturn(Optional.of(row(true, true)));
        when(passwordEncoder.matches(anyString(), eq(CURRENT_HASH))).thenReturn(false);

        assertThatExceptionOfType(InvalidCurrentPasswordException.class)
                .isThrownBy(() -> service.change(HEADER, request("a-guess", "a-guess")));
    }

    /**
     * A delete racing the change. Reporting success would tell the user their
     * password is now something it is not.
     */
    @Test
    @DisplayName("a write that updates no row is refused, not reported as success")
    void aNoOpWriteIsRefused() {
        givenAValidSession();
        when(users.updatePassword(anyLong(), anyString())).thenReturn(0);

        assertThatExceptionOfType(InvalidAccessTokenException.class)
                .isThrownBy(() -> service.change(HEADER, request(CURRENT, NEW)));
    }

    // ── the token that did it ───────────────────────────────────────────────

    /**
     * Claims are minted once and never mutate, so the caller's token still says
     * {@code mustChangePassword}. Left alive, the gate would refuse it for up to
     * fifteen more minutes — the user changes their password successfully and is
     * locked out by the flag they just cleared.
     */
    @Test
    @DisplayName("a successful change revokes the access token that made it")
    void revokesTheTokenThatDidIt() {
        givenAValidSession();

        service.change(HEADER, request(CURRENT, NEW));

        verify(blacklist).revoke(JTI, EXPIRES_AT);
    }

    @Test
    @DisplayName("a refused change revokes nothing")
    void aRefusedChangeRevokesNothing() {
        when(jwtDecoder.decode(anyString())).thenReturn(jwt());
        when(users.findById(USER_ID)).thenReturn(Optional.of(row(true, true)));
        when(passwordEncoder.matches(anyString(), eq(CURRENT_HASH))).thenReturn(false);

        assertThatExceptionOfType(InvalidCurrentPasswordException.class)
                .isThrownBy(() -> service.change(HEADER, request("wrong", NEW)));

        verify(blacklist, never()).revoke(anyString(), any());
    }

    /**
     * Redis being down must not turn away a password change — that is precisely
     * when people are most likely to be rotating credentials. The committed
     * change stands; the cost is a stale claim on one token until it expires.
     */
    @Test
    @DisplayName("an unreachable blacklist does not fail a change that was already written")
    void anUnreachableBlacklistDegrades() {
        givenAValidSession();
        doThrow(new QueryTimeoutException("redis is down"))
                .when(blacklist).revoke(anyString(), any());

        assertThatNoException().isThrownBy(() -> service.change(HEADER, request(CURRENT, NEW)));

        verify(users).updatePassword(USER_ID, NEW_HASH);
    }
}
