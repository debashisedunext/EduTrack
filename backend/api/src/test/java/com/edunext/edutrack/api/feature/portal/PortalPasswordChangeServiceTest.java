package com.edunext.edutrack.api.feature.portal;

import com.edunext.edutrack.api.security.jwt.JwtProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Changing your own portal password.
 *
 * <p>Three of these are about properties that are invisible in the response and
 * are the whole point of the mechanism: that a wrong {@code currentPassword} is
 * refused before the new one is even looked at, that the temporary password
 * cannot be chosen as its own replacement, and that the session handed back
 * carries no must-change claim. An implementation that got any of them wrong
 * would still pass a test that only checked "the password was written".
 */
class PortalPasswordChangeServiceTest {

    private static final long ACCOUNT = 41L;
    private static final String TEMPORARY = "Ed-abc23xyz9kp4";
    private static final String CHOSEN = "Monsoon-River-42!";

    /** A real encoder, not a mock: `currentPassword` has to genuinely verify. */
    private final PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    private ClientAccountRepository accounts;
    private PortalPasswordChangeService service;
    private JwtDecoder decoder;
    private String hashOfTemporary;

    @BeforeEach
    void setUp() {
        accounts = mock(ClientAccountRepository.class);
        hashOfTemporary = encoder.encode(TEMPORARY);

        byte[] secret = "portal-change-password-test-secret-long-enough-for-hs256"
                .getBytes(StandardCharsets.UTF_8);
        SecretKeySpec key = new SecretKeySpec(secret, "HmacSHA256");
        JwtEncoder jwtEncoder = new NimbusJwtEncoder(
                new com.nimbusds.jose.jwk.source.ImmutableSecret<>(key));
        decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();

        JwtProperties properties = mock(JwtProperties.class);
        when(properties.issuer()).thenReturn("https://edutrack");
        when(properties.accessTokenTtl()).thenReturn(Duration.ofMinutes(15));

        service = new PortalPasswordChangeService(accounts, new PortalPasswordRules(), encoder,
                new ClientAccessTokenIssuer(jwtEncoder, properties));
    }

    private ClientAccountRow row(boolean active, boolean mustChangePassword) {
        return new ClientAccountRow(ACCOUNT, "DEMO-101", hashOfTemporary, null, 9L,
                "Demo School", "demo@example.test", active, mustChangePassword, 0, null, null);
    }

    /** The row before the change, then the row after it — what the service reads twice. */
    private void accountChangesFrom(ClientAccountRow before, ClientAccountRow after) {
        when(accounts.findById(ACCOUNT)).thenReturn(Optional.of(before), Optional.of(after));
    }

    @Test
    @DisplayName("writes the chosen password and clears the must-change flag")
    void writesTheChosenPassword() {
        accountChangesFrom(row(true, true), row(true, false));

        service.change(ACCOUNT, TEMPORARY, CHOSEN);

        // setPassword clears the flag in the same statement; setTemporaryPassword
        // would keep it set and strand the client on the form for ever.
        verify(accounts).setPassword(eq(ACCOUNT), anyString());
        verify(accounts, never()).setTemporaryPassword(anyLong(), anyString());
    }

    /**
     * The token in the client's hand still says must-change; the portal has no
     * refresh route to trade it in. Without a successor here the client changes
     * their password successfully and is then locked out by the flag they just
     * cleared, with no way back but a password the form told them was replaced.
     */
    @Test
    @DisplayName("hands back a session with no must-change claim, or the change locks the client out")
    void returnsASettledSession() {
        accountChangesFrom(row(true, true), row(true, false));

        PortalAuthDtos.LoginResponse session = service.change(ACCOUNT, TEMPORARY, CHOSEN);

        assertThat(session.mustChangePassword()).isFalse();
        // Object, not the inferred Predicate — `getClaim` is generic, and
        // AssertJ's IntPredicate/Predicate overloads are both candidates.
        Object claim = decoder.decode(session.accessToken())
                .getClaim(ClientPrincipal.MUST_CHANGE_PASSWORD_CLAIM);
        assertThat(claim).isNull();
    }

    /**
     * The re-read is what makes the above true. Minting from the row loaded
     * before the UPDATE would stamp the claim the change just cleared.
     */
    @Test
    @DisplayName("mints the successor from the row after the write, not the one before it")
    void mintsFromThePostChangeRow() {
        accountChangesFrom(row(true, true), row(true, false));

        service.change(ACCOUNT, TEMPORARY, CHOSEN);

        verify(accounts, org.mockito.Mockito.times(2)).findById(ACCOUNT);
    }

    @Test
    @DisplayName("refuses a wrong current password without writing anything")
    void refusesAWrongCurrentPassword() {
        when(accounts.findById(ACCOUNT)).thenReturn(Optional.of(row(true, true)));

        assertThatExceptionOfType(PortalAuthExceptions.InvalidPortalCredentials.class)
                .isThrownBy(() -> service.change(ACCOUNT, "not-the-password", CHOSEN));

        verify(accounts, never()).setPassword(anyLong(), anyString());
    }

    /**
     * A weak replacement must not be reported to somebody who cannot prove who
     * they are — that would let a caller with a borrowed token probe the policy,
     * and would report a policy error on a request that was never going to be
     * honoured.
     */
    @Test
    @DisplayName("checks the current password before the new one, so the policy is not a probe")
    void currentPasswordIsCheckedFirst() {
        when(accounts.findById(ACCOUNT)).thenReturn(Optional.of(row(true, true)));

        assertThatExceptionOfType(PortalAuthExceptions.InvalidPortalCredentials.class)
                .isThrownBy(() -> service.change(ACCOUNT, "not-the-password", "short"));
    }

    /**
     * The whole point of a forced change. Accepting the same password back
     * would clear the flag while leaving in place the exact credential a staff
     * member read off a screen.
     */
    @Test
    @DisplayName("refuses the temporary password as its own replacement")
    void refusesAnUnchangedPassword() {
        when(accounts.findById(ACCOUNT)).thenReturn(Optional.of(row(true, true)));

        assertThatExceptionOfType(PortalAuthExceptions.PortalPasswordUnchanged.class)
                .isThrownBy(() -> service.change(ACCOUNT, TEMPORARY, TEMPORARY));

        verify(accounts, never()).setPassword(anyLong(), anyString());
    }

    @Test
    @DisplayName("enforces the portal's own strength policy on the replacement")
    void enforcesThePolicy() {
        when(accounts.findById(ACCOUNT)).thenReturn(Optional.of(row(true, true)));

        assertThatExceptionOfType(PortalAuthExceptions.WeakPortalPassword.class)
                .isThrownBy(() -> service.change(ACCOUNT, TEMPORARY, "alllowercase123"));
    }

    /**
     * The token is up to an access-token lifetime old. An account deactivated
     * in that window must not be able to set a new password and walk back in.
     */
    @Test
    @DisplayName("refuses a deactivated account, so a withdrawn login cannot re-password itself")
    void refusesADeactivatedAccount() {
        when(accounts.findById(ACCOUNT)).thenReturn(Optional.of(row(false, true)));

        assertThatExceptionOfType(PortalAuthExceptions.InvalidPortalCredentials.class)
                .isThrownBy(() -> service.change(ACCOUNT, TEMPORARY, CHOSEN));

        verify(accounts, never()).setPassword(anyLong(), anyString());
    }

    /**
     * Deliberately not charged to {@code client_accounts.failed_attempts}: an
     * attacker holding a stolen token could otherwise spend five wrong guesses
     * locking the real client out of the sign-in screen, turning a protective
     * control into a denial of service delivered on demand.
     */
    @Test
    @DisplayName("a wrong guess does not count towards the login lockout")
    void doesNotTouchTheLoginLockout() {
        when(accounts.findById(ACCOUNT)).thenReturn(Optional.of(row(true, true)));

        assertThatExceptionOfType(PortalAuthExceptions.InvalidPortalCredentials.class)
                .isThrownBy(() -> service.change(ACCOUNT, "not-the-password", CHOSEN));

        verify(accounts, never()).incrementFailedAttempts(anyLong());
        verify(accounts, never()).applyLock(anyLong(), org.mockito.ArgumentMatchers.any());
    }
}
