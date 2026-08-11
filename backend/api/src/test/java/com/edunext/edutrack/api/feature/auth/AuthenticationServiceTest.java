package com.edunext.edutrack.api.feature.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A-020 · the two properties blueprint §10.1 asks for, asserted directly:
 * every failure looks the same, and every failure <i>costs</i> the same.
 *
 * <p>The encoder is a mock rather than the real Argon2id one. That is not to
 * make the test fast — it is because the property under test is <b>whether the
 * KDF is invoked at all</b> on each path, which a mock can witness and a real
 * encoder cannot. {@link PasswordHashingTest} covers the real algorithm.
 *
 * <p>Timing is asserted structurally rather than with a stopwatch: a wall-clock
 * comparison of two code paths is the classic flaky test — it fails on a loaded
 * CI runner and passes on a laptop, so it gets muted, and then the property is
 * unguarded. "The same work happens on both paths" is the same guarantee,
 * deterministically.
 */
class AuthenticationServiceTest {

    private static final String DECOY_HASH = "$argon2id$v=19$m=65536,t=3,p=1$decoy";
    private static final String STORED_HASH = "$argon2id$v=19$m=65536,t=3,p=1$stored";

    private AuthUserRepository users;
    private PasswordEncoder encoder;
    private LoginAttemptRecorder attempts;
    private PasswordPolicy passwordPolicy;
    private TotpService twoFactor;
    private AuthenticationService service;

    @BeforeEach
    void setUp() {
        users = mock(AuthUserRepository.class);
        encoder = mock(PasswordEncoder.class);
        attempts = mock(LoginAttemptRecorder.class);
        when(encoder.encode(anyString())).thenReturn(DECOY_HASH);
        // A-028 · mocked, so isExpired answers false — the same thing the real
        // policy does while §10.3's optional expiry is switched off, which is
        // the default and therefore what this suite should see. The expiry
        // behaviour itself is PasswordPolicyTest's subject.
        passwordPolicy = mock(PasswordPolicy.class);
        twoFactor = mock(TotpService.class);
        service = new AuthenticationService(users, encoder, attempts, passwordPolicy, twoFactor);
    }

    private static AuthUserRow row(boolean active) {
        return row(active, 0, null);
    }

    private static AuthUserRow row(boolean active, int failedAttempts, Instant lockedUntil) {
        return new AuthUserRow(7L, "asha.rao", "asha.rao@edunext.test", "Asha Rao",
                STORED_HASH, "DEVELOPER", 3, "Asia/Kolkata", active, false,
                Instant.now(), null, false, failedAttempts, lockedUntil);
    }

    // ── the enumeration oracle this task exists to close ────────────────────

    @Test
    @DisplayName("an unknown username still costs a full hash verification")
    void unknownUserIsVerifiedAgainstTheDecoyHash() {
        when(users.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatExceptionOfType(InvalidCredentialsException.class)
                .isThrownBy(() -> service.authenticate("ghost", "whatever", null));

        // The assertion that matters. An early `return` for a missing user
        // answers in microseconds where a real user costs ~100 ms, and that
        // difference is measurable remotely — it turns the login form into a
        // staff directory.
        verify(encoder).matches("whatever", DECOY_HASH);
    }

    @Test
    @DisplayName("the decoy hash is computed once, at construction, not per attempt")
    void decoyHashIsBuiltOnce() {
        when(users.findByUsername(anyString())).thenReturn(Optional.empty());

        assertThatExceptionOfType(InvalidCredentialsException.class)
                .isThrownBy(() -> service.authenticate("ghost", "a", null));
        assertThatExceptionOfType(InvalidCredentialsException.class)
                .isThrownBy(() -> service.authenticate("spectre", "b", null));

        // encode() is the expensive direction of Argon2id. Doing it per request
        // would double the cost of every failed login and hand an attacker a
        // cheap denial-of-service against the login endpoint.
        verify(encoder, times(1)).encode(anyString());
    }

    // ── every failure is indistinguishable ──────────────────────────────────

    @Test
    @DisplayName("a wrong password fails exactly as an unknown user does")
    void wrongPasswordFailsIdentically() {
        when(users.findByUsername("asha.rao")).thenReturn(Optional.of(row(true)));
        when(encoder.matches("wrong", STORED_HASH)).thenReturn(false);

        assertThatExceptionOfType(InvalidCredentialsException.class)
                .isThrownBy(() -> service.authenticate("asha.rao", "wrong", null))
                .withMessage("Invalid credentials");
    }

    @Test
    @DisplayName("a deactivated account fails identically, even with the right password")
    void deactivatedAccountFailsIdentically() {
        when(users.findByUsername("asha.rao")).thenReturn(Optional.of(row(false)));
        when(encoder.matches("right", STORED_HASH)).thenReturn(true);

        assertThatExceptionOfType(InvalidCredentialsException.class)
                .isThrownBy(() -> service.authenticate("asha.rao", "right", null))
                .withMessage("Invalid credentials");

        // Verified before the active check, not skipped because of it: rejecting
        // an inactive user without hashing would say "this account exists but is
        // disabled" in the timing, which names a real employee.
        verify(encoder).matches("right", STORED_HASH);
    }

    @Test
    @DisplayName("no failure path loads permissions, projects or reportees")
    void failedAttemptsDoNoScopeWork() {
        when(users.findByUsername("asha.rao")).thenReturn(Optional.of(row(true)));
        when(encoder.matches(anyString(), eq(STORED_HASH))).thenReturn(false);

        assertThatExceptionOfType(InvalidCredentialsException.class)
                .isThrownBy(() -> service.authenticate("asha.rao", "wrong", null));

        // Three extra queries per guess is free amplification for anyone
        // spraying the endpoint, and none of it is needed to say "no".
        verify(users, never()).findPermissionCodesByRoleId(anyInt());
        verify(users, never()).findProjectIdsByUserId(anyLong());
        verify(users, never()).findReporteeIdsByManagerId(anyLong());
    }

    // ── the success path ────────────────────────────────────────────────────

    @Test
    @DisplayName("a valid login resolves the §10.1 claim set")
    void validLoginResolvesScope() {
        when(users.findByUsername("asha.rao")).thenReturn(Optional.of(row(true)));
        when(encoder.matches("right", STORED_HASH)).thenReturn(true);
        when(users.findPermissionCodesByRoleId(3)).thenReturn(List.of("ticket.read", "ticket.update"));
        when(users.findProjectIdsByUserId(7L)).thenReturn(List.of(11L, 12L));
        when(users.findReporteeIdsByManagerId(7L)).thenReturn(List.of());

        AuthenticatedUser user = service.authenticate("asha.rao", "right", null);

        assertThat(user.id()).isEqualTo(7L);
        assertThat(user.username()).isEqualTo("asha.rao");
        assertThat(user.fullName()).isEqualTo("Asha Rao");
        assertThat(user.roleCode()).isEqualTo("DEVELOPER");
        assertThat(user.timezone()).isEqualTo("Asia/Kolkata");
        assertThat(user.permissions()).containsExactly("ticket.read", "ticket.update");
        assertThat(user.projectIds()).containsExactly(11L, 12L);
        assertThat(user.reporteeIds()).isEmpty();
    }

    // ── A-021 · lockout ─────────────────────────────────────────────────────

    @Test
    @DisplayName("a wrong password against a real user is counted")
    void wrongPasswordIsRecorded() {
        AuthUserRow user = row(true);
        when(users.findByUsername("asha.rao")).thenReturn(Optional.of(user));
        when(encoder.matches("wrong", STORED_HASH)).thenReturn(false);

        assertThatExceptionOfType(InvalidCredentialsException.class)
                .isThrownBy(() -> service.authenticate("asha.rao", "wrong", null));

        verify(attempts).recordFailure(eq(user), any(Instant.class));
    }

    @Test
    @DisplayName("an unknown username is never counted — there is no row, and counting one would leak existence")
    void unknownUserIsNotRecorded() {
        when(users.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatExceptionOfType(InvalidCredentialsException.class)
                .isThrownBy(() -> service.authenticate("ghost", "whatever", null));

        verify(attempts, never()).recordFailure(any(), any());
    }

    @Test
    @DisplayName("a deactivated account is not counted towards a lock it cannot benefit from")
    void deactivatedAccountIsNotRecorded() {
        when(users.findByUsername("asha.rao")).thenReturn(Optional.of(row(false)));
        when(encoder.matches("right", STORED_HASH)).thenReturn(true);

        assertThatExceptionOfType(InvalidCredentialsException.class)
                .isThrownBy(() -> service.authenticate("asha.rao", "right", null));

        verify(attempts, never()).recordFailure(any(), any());
    }

    @Test
    @DisplayName("a locked account with the CORRECT password gets 423, carrying the expiry")
    void lockedAccountWithCorrectPasswordIsReported() {
        Instant lockedUntil = Instant.now().plus(Duration.ofMinutes(10));
        when(users.findByUsername("asha.rao")).thenReturn(Optional.of(row(true, 0, lockedUntil)));
        when(encoder.matches("right", STORED_HASH)).thenReturn(true);

        assertThatExceptionOfType(AccountLockedException.class)
                .isThrownBy(() -> service.authenticate("asha.rao", "right", null))
                .satisfies(e -> assertThat(e.lockedUntil()).isEqualTo(lockedUntil));
    }

    @Test
    @DisplayName("a locked account with the WRONG password is still just 401 — the lock is never revealed")
    void lockedAccountWithWrongPasswordStaysGeneric() {
        Instant lockedUntil = Instant.now().plus(Duration.ofMinutes(10));
        when(users.findByUsername("asha.rao")).thenReturn(Optional.of(row(true, 3, lockedUntil)));
        when(encoder.matches("wrong", STORED_HASH)).thenReturn(false);

        // Revealing the lock here would confirm the account exists to someone
        // who has not proved they own it — the enumeration oracle again.
        assertThatExceptionOfType(InvalidCredentialsException.class)
                .isThrownBy(() -> service.authenticate("asha.rao", "wrong", null));
    }

    @Test
    @DisplayName("a lapsed lock lets the user straight back in, with no job needed to clear it")
    void expiredLockDoesNotBlock() {
        Instant expired = Instant.now().minus(Duration.ofMinutes(1));
        when(users.findByUsername("asha.rao")).thenReturn(Optional.of(row(true, 0, expired)));
        when(encoder.matches("right", STORED_HASH)).thenReturn(true);
        when(users.findPermissionCodesByRoleId(3)).thenReturn(List.of());
        when(users.findProjectIdsByUserId(7L)).thenReturn(List.of());
        when(users.findReporteeIdsByManagerId(7L)).thenReturn(List.of());

        assertThat(service.authenticate("asha.rao", "right", null).id()).isEqualTo(7L);
    }

    @Test
    @DisplayName("a successful login clears the counter")
    void successClearsTheCounter() {
        AuthUserRow user = row(true, 3, null);
        when(users.findByUsername("asha.rao")).thenReturn(Optional.of(user));
        when(encoder.matches("right", STORED_HASH)).thenReturn(true);
        when(users.findPermissionCodesByRoleId(3)).thenReturn(List.of());
        when(users.findProjectIdsByUserId(7L)).thenReturn(List.of());
        when(users.findReporteeIdsByManagerId(7L)).thenReturn(List.of());

        service.authenticate("asha.rao", "right", null);

        verify(attempts).recordSuccess(user);
    }

    @Test
    @DisplayName("the password hash never reaches the result")
    void resultCarriesNoHash() {
        when(users.findByUsername("asha.rao")).thenReturn(Optional.of(row(true)));
        when(encoder.matches("right", STORED_HASH)).thenReturn(true);
        when(users.findPermissionCodesByRoleId(3)).thenReturn(List.of());
        when(users.findProjectIdsByUserId(7L)).thenReturn(List.of());
        when(users.findReporteeIdsByManagerId(7L)).thenReturn(List.of());

        AuthenticatedUser user = service.authenticate("asha.rao", "right", null);

        // AuthenticatedUser has no hash field; toString() is the realistic leak,
        // because that is what ends up in a log line or an error report.
        assertThat(user.toString()).doesNotContain(STORED_HASH);
    }

    // ── A-029 · where the TOTP challenge sits in the sequence ───────────────

    /**
     * <b>The ordering guarantee, and the reason this test exists.</b> A
     * "this account needs a code" answer given before the password verifies
     * confirms both that the account exists and that it is protected — a list of
     * exactly whom to phish. The challenge must never be reached by someone who
     * failed the password.
     */
    @Test
    @DisplayName("a wrong password never reaches the two-factor challenge")
    void aWrongPasswordNeverReachesTheChallenge() {
        when(users.findByUsername("asha.rao")).thenReturn(Optional.of(row(true)));
        when(encoder.matches("wrong", STORED_HASH)).thenReturn(false);

        assertThatExceptionOfType(InvalidCredentialsException.class)
                .isThrownBy(() -> service.authenticate("asha.rao", "wrong", "123456"));

        verify(twoFactor, never()).verifyForLogin(any(), anyString());
    }

    /** Nor an unknown username — there is no account whose protection to reveal. */
    @Test
    @DisplayName("an unknown username never reaches the two-factor challenge")
    void anUnknownUserNeverReachesTheChallenge() {
        when(users.findByUsername(anyString())).thenReturn(Optional.empty());

        assertThatExceptionOfType(InvalidCredentialsException.class)
                .isThrownBy(() -> service.authenticate("ghost", "whatever", "123456"));

        verify(twoFactor, never()).verifyForLogin(any(), anyString());
    }

    /**
     * A-021's lockout is checked before the second factor, so a locked account
     * reports {@code account-locked} rather than asking for a code it will
     * refuse anyway.
     */
    @Test
    @DisplayName("a locked account is refused before the two-factor challenge")
    void aLockedAccountIsRefusedBeforeTheChallenge() {
        when(users.findByUsername("asha.rao"))
                .thenReturn(Optional.of(row(true, 5, Instant.now().plusSeconds(600))));
        when(encoder.matches("right", STORED_HASH)).thenReturn(true);

        assertThatExceptionOfType(AccountLockedException.class)
                .isThrownBy(() -> service.authenticate("asha.rao", "right", "123456"));

        verify(twoFactor, never()).verifyForLogin(any(), anyString());
    }

    @Test
    @DisplayName("a correct password reaches the challenge, carrying the submitted code")
    void aCorrectPasswordReachesTheChallenge() {
        when(users.findByUsername("asha.rao")).thenReturn(Optional.of(row(true)));
        when(encoder.matches("right", STORED_HASH)).thenReturn(true);
        when(users.findPermissionCodesByRoleId(3)).thenReturn(List.of());
        when(users.findProjectIdsByUserId(7L)).thenReturn(List.of());
        when(users.findReporteeIdsByManagerId(7L)).thenReturn(List.of());

        service.authenticate("asha.rao", "right", "123456");

        verify(twoFactor).verifyForLogin(any(AuthUserRow.class), eq("123456"));
    }

    /**
     * <b>The challenge runs before {@code recordSuccess}.</b> Clearing the
     * failure counter on a correct password with a wrong code would let anyone
     * holding the password hold the lockout permanently open while grinding the
     * six digits.
     */
    @Test
    @DisplayName("a failed two-factor challenge does not clear the failure counter")
    void aFailedChallengeDoesNotClearTheLockoutCounter() {
        when(users.findByUsername("asha.rao")).thenReturn(Optional.of(row(true)));
        when(encoder.matches("right", STORED_HASH)).thenReturn(true);
        org.mockito.Mockito.doThrow(new InvalidTotpCodeException())
                .when(twoFactor).verifyForLogin(any(), anyString());

        assertThatExceptionOfType(InvalidTotpCodeException.class)
                .isThrownBy(() -> service.authenticate("asha.rao", "right", "000000"));

        verify(attempts, never()).recordSuccess(any());
    }

    /**
     * A refused challenge must not produce a session either — the token is minted
     * from what this method returns, so leaking past the challenge is a login
     * with no second factor at all.
     */
    @Test
    @DisplayName("a missing code stops the login before any session is resolved")
    void aMissingCodeStopsTheLogin() {
        when(users.findByUsername("asha.rao")).thenReturn(Optional.of(row(true)));
        when(encoder.matches("right", STORED_HASH)).thenReturn(true);
        org.mockito.Mockito.doThrow(new TwoFactorRequiredException())
                .when(twoFactor).verifyForLogin(any(), any());

        assertThatExceptionOfType(TwoFactorRequiredException.class)
                .isThrownBy(() -> service.authenticate("asha.rao", "right", null));

        verify(users, never()).findPermissionCodesByRoleId(anyInt());
    }
}
