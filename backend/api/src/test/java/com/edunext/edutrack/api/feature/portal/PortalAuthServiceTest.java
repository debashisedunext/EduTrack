package com.edunext.edutrack.api.feature.portal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A-130 · verifying a portal login.
 *
 * <p>Two of these tests are about work the service must <em>do</em> rather than
 * about an answer it returns — the decoy verification for an unknown username,
 * and the ordering of the lock check against the hash comparison. Both are
 * invisible in the response and both are the whole security argument, so an
 * implementation that skipped them would pass every obvious test.
 */
class PortalAuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-09T10:00:00Z");
    private static final String PASSWORD = "Correct-Horse-1!";

    /** A real encoder, not a mock: the decoy has to be a genuine verification. */
    private final PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    private ClientAccountRepository accounts;
    private PortalAuthService service;
    private String hashOfPassword;

    @BeforeEach
    void setUp() {
        accounts = mock(ClientAccountRepository.class);
        hashOfPassword = encoder.encode(PASSWORD);
        service = new PortalAuthService(accounts, encoder, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ClientAccountRow account(boolean active, int failedAttempts, Instant lockedUntil) {
        return new ClientAccountRow(42L, "ACME.ravi", hashOfPassword, 5L, 9L,
                "Ravi Shah", "ravi@acme.example", active, false, failedAttempts, lockedUntil, null);
    }

    private void signedUpAs(ClientAccountRow row) {
        when(accounts.findByUsername("ACME.ravi")).thenReturn(Optional.of(row));
    }

    @Test
    @DisplayName("signs in a live account and clears its counters")
    void happyPath() {
        signedUpAs(account(true, 0, null));

        ClientAccountRow result = service.authenticate("ACME.ravi", PASSWORD);

        assertThat(result.id()).isEqualTo(42L);
        verify(accounts).recordSuccessfulLogin(42L, NOW);
    }

    /** Whitespace from an autofill is the ordinary case, not an attack. */
    @Test
    @DisplayName("trims the username before looking it up")
    void trimsUsername() {
        signedUpAs(account(true, 0, null));

        assertThat(service.authenticate("  ACME.ravi  ", PASSWORD)).isNotNull();
    }

    /**
     * The enumeration oracle this service exists to close. An unknown username
     * must still cost a full Argon2id verification, or the response time says
     * which of our customers have portal access.
     */
    @Test
    @DisplayName("an unknown username still costs a verification")
    void unknownUsernameVerifiesADecoy() {
        when(accounts.findByUsername(anyString())).thenReturn(Optional.empty());
        PasswordEncoder counting = mock(PasswordEncoder.class);
        when(counting.encode(anyString())).thenReturn(hashOfPassword);
        when(counting.matches(anyString(), anyString())).thenReturn(false);

        PortalAuthService counted =
                new PortalAuthService(accounts, counting, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> counted.authenticate("nobody", PASSWORD))
                .isInstanceOf(PortalAuthExceptions.InvalidPortalCredentials.class);

        verify(counting).matches(anyString(), anyString());
    }

    @Test
    @DisplayName("an unknown username records no failure against anybody")
    void unknownUsernameRecordsNothing() {
        when(accounts.findByUsername(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.authenticate("nobody", PASSWORD))
                .isInstanceOf(PortalAuthExceptions.InvalidPortalCredentials.class);

        verify(accounts, never()).incrementFailedAttempts(anyLong());
    }

    @Test
    @DisplayName("a wrong password counts towards the lock")
    void wrongPasswordCounts() {
        signedUpAs(account(true, 0, null));

        assertThatThrownBy(() -> service.authenticate("ACME.ravi", "not-the-password"))
                .isInstanceOf(PortalAuthExceptions.InvalidPortalCredentials.class);

        verify(accounts).incrementFailedAttempts(42L);
        verify(accounts, never()).applyLock(anyLong(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("the fifth wrong password locks the account")
    void fifthFailureLocks() {
        signedUpAs(account(true, PortalAuthService.MAX_FAILED_ATTEMPTS - 1, null));

        assertThatThrownBy(() -> service.authenticate("ACME.ravi", "not-the-password"))
                .isInstanceOf(PortalAuthExceptions.InvalidPortalCredentials.class);

        verify(accounts).applyLock(42L, NOW.plus(PortalAuthService.LOCK_DURATION));
    }

    /**
     * A deactivated account is refused, and is <b>not</b> counted towards a lock
     * it can never benefit from — the same distinction the staff service draws.
     */
    @Test
    @DisplayName("a deactivated account is refused with the same words, and counts nothing")
    void deactivated() {
        signedUpAs(account(false, 0, null));

        assertThatThrownBy(() -> service.authenticate("ACME.ravi", PASSWORD))
                .isInstanceOf(PortalAuthExceptions.InvalidPortalCredentials.class);

        verify(accounts, never()).incrementFailedAttempts(anyLong());
        verify(accounts, never()).recordSuccessfulLogin(anyLong(), org.mockito.ArgumentMatchers.any());
    }

    /**
     * The ordering rule. A locked account with the <em>right</em> password is
     * told it is locked; a locked account with the wrong one is told only that
     * the credentials are wrong. Checking the lock first would report
     * account-locked to somebody who never proved they knew the password, which
     * confirms the account exists.
     */
    @Test
    @DisplayName("reports the lock only after the password verifies")
    void lockIsReportedOnlyAfterTheHash() {
        Instant until = NOW.plusSeconds(600);
        signedUpAs(account(true, 0, until));

        assertThatThrownBy(() -> service.authenticate("ACME.ravi", PASSWORD))
                .isInstanceOf(PortalAuthExceptions.PortalAccountLocked.class)
                .extracting(caught -> ((PortalAuthExceptions.PortalAccountLocked) caught).lockedUntil())
                .isEqualTo(until);

        assertThatThrownBy(() -> service.authenticate("ACME.ravi", "not-the-password"))
                .isInstanceOf(PortalAuthExceptions.InvalidPortalCredentials.class);
    }

    @Test
    @DisplayName("a lapsed lock lets the account back in")
    void lapsedLock() {
        signedUpAs(account(true, 0, NOW.minusSeconds(1)));

        assertThat(service.authenticate("ACME.ravi", PASSWORD)).isNotNull();
        verify(accounts, times(1)).recordSuccessfulLogin(42L, NOW);
    }
}
