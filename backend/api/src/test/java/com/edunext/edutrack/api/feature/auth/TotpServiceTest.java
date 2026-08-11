package com.edunext.edutrack.api.feature.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A-029 · enrolment, verification and disable.
 *
 * <p>The assertions that matter are about ordering and refusal: an enrolment
 * that enables before confirming, or a disable that leaves the secret behind,
 * both pass a happy-path test and both leave a real hole.
 *
 * <p>A real {@link TotpGenerator} is used throughout rather than a mock — it is
 * pure, fast, and already proved correct against the RFC's own vectors, so
 * stubbing it would only test that this class calls a collaborator.
 */
class TotpServiceTest {

    private static final long USER_ID = 42L;
    private static final String PLAINTEXT_SECRET = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";
    private static final String ENCRYPTED_SECRET = "{enc}stored-ciphertext";

    private AuthUserRepository users;
    private TotpSecretCipher cipher;
    private TotpReplayGuard replayGuard;
    private RecoveryCodeService recoveryCodes;
    private TotpGenerator generator;
    private TotpService service;

    @BeforeEach
    void setUp() {
        users = mock(AuthUserRepository.class);
        cipher = mock(TotpSecretCipher.class);
        replayGuard = mock(TotpReplayGuard.class);
        recoveryCodes = mock(RecoveryCodeService.class);
        generator = new TotpGenerator();

        when(cipher.encrypt(anyString())).thenReturn(ENCRYPTED_SECRET);
        when(cipher.decrypt(ENCRYPTED_SECRET)).thenReturn(PLAINTEXT_SECRET);
        when(replayGuard.claim(anyLong(), anyLong(), any())).thenReturn(true);

        service = new TotpService(users, generator, cipher, replayGuard, recoveryCodes,
                new TotpProperties(null, null, null, null));
    }

    private static AuthUserRow row(boolean active, String totpSecret, boolean totpEnabled) {
        return new AuthUserRow(USER_ID, "asha.rao", "asha.rao@edunext.test", "Asha Rao",
                "{argon2id}$pw", "DEVELOPER", 3, "Asia/Kolkata",
                active, false, Instant.now(), totpSecret, totpEnabled, 0, null);
    }

    private String currentCode() {
        return generator.codeFor(PLAINTEXT_SECRET, generator.timeStepAt(Instant.now()));
    }

    // ── enrolment step one ──────────────────────────────────────────────────

    /**
     * <b>The single most important assertion in this class.</b> Enabling at
     * setup locks out anyone whose QR did not scan — and locks them out of the
     * account they were protecting.
     */
    @Test
    @DisplayName("setup stores a secret and does NOT enable 2FA")
    void setupDoesNotEnable() {
        when(users.findById(USER_ID)).thenReturn(Optional.of(row(true, null, false)));

        service.beginEnrolment(USER_ID);

        verify(users).startTotpEnrolment(eq(USER_ID), anyString());
        verify(users, never()).confirmTotp(anyLong());
    }

    @Test
    @DisplayName("the stored secret is ciphertext, never the plaintext")
    void theStoredSecretIsEncrypted() {
        when(users.findById(USER_ID)).thenReturn(Optional.of(row(true, null, false)));

        TotpService.Enrolment enrolment = service.beginEnrolment(USER_ID);

        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(users).startTotpEnrolment(eq(USER_ID), stored.capture());
        assertThat(stored.getValue()).isEqualTo(ENCRYPTED_SECRET);
        assertThat(stored.getValue())
                .as("the value an authenticator would read must not reach the database")
                .isNotEqualTo(enrolment.secret());
    }

    /**
     * The URI has to carry the issuer twice — as the label prefix and as a query
     * parameter — because apps disagree about which they read. Omitting either
     * lists the entry under the wrong name or as a bare username.
     */
    @Test
    @DisplayName("the otpauth URI carries everything an authenticator needs")
    void theOtpauthUriIsComplete() {
        when(users.findById(USER_ID)).thenReturn(Optional.of(row(true, null, false)));

        TotpService.Enrolment enrolment = service.beginEnrolment(USER_ID);

        assertThat(enrolment.otpauthUri())
                .startsWith("otpauth://totp/EduTrack:asha.rao?")
                .contains("secret=" + enrolment.secret())
                .contains("issuer=EduTrack")
                .contains("algorithm=SHA1")
                .contains("digits=6")
                .contains("period=30");
    }

    /**
     * Silently re-issuing would let anyone holding a stolen fifteen-minute token
     * replace the second factor — turning a short theft into a permanent
     * foothold, through the feature meant to prevent exactly that.
     */
    @Test
    @DisplayName("setup is refused once 2FA is already enabled")
    void setupIsRefusedWhenAlreadyEnabled() {
        when(users.findById(USER_ID)).thenReturn(Optional.of(row(true, ENCRYPTED_SECRET, true)));

        assertThatExceptionOfType(TwoFactorAlreadyEnabledException.class)
                .isThrownBy(() -> service.beginEnrolment(USER_ID));

        verify(users, never()).startTotpEnrolment(anyLong(), anyString());
    }

    @Test
    @DisplayName("a deactivated account cannot enrol")
    void aDeactivatedAccountCannotEnrol() {
        when(users.findById(USER_ID)).thenReturn(Optional.of(row(false, null, false)));

        assertThatExceptionOfType(InvalidAccessTokenException.class)
                .isThrownBy(() -> service.beginEnrolment(USER_ID));
    }

    // ── enrolment step two ──────────────────────────────────────────────────

    @Test
    @DisplayName("a correct code enables 2FA and returns recovery codes")
    void confirmEnablesAndIssuesRecoveryCodes() {
        when(users.findById(USER_ID)).thenReturn(Optional.of(row(true, ENCRYPTED_SECRET, false)));
        when(users.confirmTotp(USER_ID)).thenReturn(true);
        when(recoveryCodes.regenerateFor(USER_ID)).thenReturn(List.of("AAAAA-BBBBB", "CCCCC-DDDDD"));

        List<String> codes = service.confirmEnrolment(USER_ID, currentCode());

        verify(users).confirmTotp(USER_ID);
        assertThat(codes).containsExactly("AAAAA-BBBBB", "CCCCC-DDDDD");
    }

    /**
     * Codes issued for an enrolment that was never completed would be live
     * credentials for an account with no second factor — strictly worse than
     * having none.
     */
    @Test
    @DisplayName("recovery codes are issued only after 2FA is actually on")
    void recoveryCodesComeAfterEnabling() {
        when(users.findById(USER_ID)).thenReturn(Optional.of(row(true, ENCRYPTED_SECRET, false)));
        when(users.confirmTotp(USER_ID)).thenReturn(true);

        service.confirmEnrolment(USER_ID, currentCode());

        var order = inOrder(users, recoveryCodes);
        order.verify(users).confirmTotp(USER_ID);
        order.verify(recoveryCodes).regenerateFor(USER_ID);
    }

    @Test
    @DisplayName("a wrong code does not enable 2FA")
    void aWrongCodeDoesNotEnable() {
        when(users.findById(USER_ID)).thenReturn(Optional.of(row(true, ENCRYPTED_SECRET, false)));

        assertThatExceptionOfType(InvalidTotpCodeException.class)
                .isThrownBy(() -> service.confirmEnrolment(USER_ID, "000000"));

        verify(users, never()).confirmTotp(anyLong());
        verify(recoveryCodes, never()).regenerateFor(anyLong());
    }

    @Test
    @DisplayName("confirming with no enrolment in progress is refused")
    void confirmWithoutSetupIsRefused() {
        when(users.findById(USER_ID)).thenReturn(Optional.of(row(true, null, false)));

        assertThatExceptionOfType(TwoFactorNotEnrolledException.class)
                .isThrownBy(() -> service.confirmEnrolment(USER_ID, "123456"));
    }

    /**
     * A disable racing a confirm. Enabling against a secret that has just been
     * cleared would leave an account demanding codes nothing can generate.
     */
    @Test
    @DisplayName("losing the race against a disable is refused, not retried")
    void losingTheRaceAgainstDisableIsRefused() {
        when(users.findById(USER_ID)).thenReturn(Optional.of(row(true, ENCRYPTED_SECRET, false)));
        when(users.confirmTotp(USER_ID)).thenReturn(false);

        assertThatExceptionOfType(TwoFactorNotEnrolledException.class)
                .isThrownBy(() -> service.confirmEnrolment(USER_ID, currentCode()));

        verify(recoveryCodes, never()).regenerateFor(anyLong());
    }

    // ── the login challenge ─────────────────────────────────────────────────

    @Test
    @DisplayName("an account without 2FA passes straight through")
    void noTwoFactorMeansNoChallenge() {
        assertThatNoException()
                .isThrownBy(() -> service.verifyForLogin(row(true, null, false), null));

        verify(replayGuard, never()).claim(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("an enabled account with no code submitted is told a code is required")
    void anEnabledAccountDemandsACode() {
        AuthUserRow user = row(true, ENCRYPTED_SECRET, true);

        assertThatExceptionOfType(TwoFactorRequiredException.class)
                .isThrownBy(() -> service.verifyForLogin(user, null));
        assertThatExceptionOfType(TwoFactorRequiredException.class)
                .isThrownBy(() -> service.verifyForLogin(user, "  "));
    }

    @Test
    @DisplayName("a correct code is accepted")
    void aCorrectCodeIsAccepted() {
        AuthUserRow user = row(true, ENCRYPTED_SECRET, true);

        assertThatNoException().isThrownBy(() -> service.verifyForLogin(user, currentCode()));
    }

    @Test
    @DisplayName("a wrong code is refused")
    void aWrongCodeIsRefused() {
        AuthUserRow user = row(true, ENCRYPTED_SECRET, true);
        String wrong = currentCode().equals("000000") ? "111111" : "000000";

        assertThatExceptionOfType(InvalidTotpCodeException.class)
                .isThrownBy(() -> service.verifyForLogin(user, wrong));
    }

    /**
     * RFC 6238 §5.2. Without this, a code observed in a phishing proxy or over a
     * shoulder stays usable for the rest of its window — which is the attack a
     * second factor exists to stop.
     */
    @Test
    @DisplayName("a replayed code is refused even though it verifies")
    void aReplayedCodeIsRefused() {
        AuthUserRow user = row(true, ENCRYPTED_SECRET, true);
        when(replayGuard.claim(anyLong(), anyLong(), any())).thenReturn(false);

        assertThatExceptionOfType(InvalidTotpCodeException.class)
                .isThrownBy(() -> service.verifyForLogin(user, currentCode()));
    }

    @Test
    @DisplayName("the replay guard is claimed against the matched step, not the current one")
    void theGuardClaimsTheMatchedStep() {
        AuthUserRow user = row(true, ENCRYPTED_SECRET, true);
        long previousStep = generator.timeStepAt(Instant.now()) - 1;
        String slightlyLateCode = generator.codeFor(PLAINTEXT_SECRET, previousStep);

        service.verifyForLogin(user, slightlyLateCode);

        // Claiming the current step would leave the previous one unclaimed and
        // therefore replayable — the guard has to record what actually matched.
        verify(replayGuard).claim(eq(USER_ID), eq(previousStep), any(Duration.class));
    }

    /**
     * The guard entry must outlive the whole window the step can still be
     * accepted in, or the code becomes replayable the moment the guard forgets.
     */
    @Test
    @DisplayName("the replay entry outlives the acceptance window")
    void theReplayEntryOutlivesTheWindow() {
        AuthUserRow user = row(true, ENCRYPTED_SECRET, true);

        service.verifyForLogin(user, currentCode());

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(replayGuard).claim(anyLong(), anyLong(), ttl.capture());
        // window=1 → the step is acceptable across 3 steps; the TTL must exceed that.
        assertThat(ttl.getValue())
                .isGreaterThanOrEqualTo(Duration.ofSeconds(TotpGenerator.PERIOD_SECONDS * 3L));
    }

    // ── recovery codes on the login path ────────────────────────────────────

    /**
     * Decided by shape: six digits is a TOTP code, anything else can only be a
     * recovery code. Trying both against both would spend an Argon2id
     * verification per recovery code on every ordinary login.
     */
    @Test
    @DisplayName("a non-six-digit value is tried as a recovery code")
    void aRecoveryCodeIsAccepted() {
        AuthUserRow user = row(true, ENCRYPTED_SECRET, true);
        when(recoveryCodes.redeem(USER_ID, "AAAAA-BBBBB")).thenReturn(true);

        assertThatNoException().isThrownBy(() -> service.verifyForLogin(user, "AAAAA-BBBBB"));

        verify(recoveryCodes).redeem(USER_ID, "AAAAA-BBBBB");
    }

    @Test
    @DisplayName("an unrecognised recovery code is refused")
    void anUnknownRecoveryCodeIsRefused() {
        AuthUserRow user = row(true, ENCRYPTED_SECRET, true);
        when(recoveryCodes.redeem(anyLong(), anyString())).thenReturn(false);

        assertThatExceptionOfType(InvalidTotpCodeException.class)
                .isThrownBy(() -> service.verifyForLogin(user, "ZZZZZ-YYYYY"));
    }

    @Test
    @DisplayName("an ordinary six-digit login never touches the recovery codes")
    void aTotpLoginDoesNotSpendArgon2OnRecoveryCodes() {
        AuthUserRow user = row(true, ENCRYPTED_SECRET, true);

        service.verifyForLogin(user, currentCode());

        verify(recoveryCodes, never()).redeem(anyLong(), anyString());
    }

    // ── disable ─────────────────────────────────────────────────────────────

    /**
     * Leaving the secret would mean re-enabling silently resurrects a secret the
     * user may have disabled precisely because their authenticator was
     * compromised.
     */
    @Test
    @DisplayName("disabling clears the secret and every recovery code")
    void disableClearsEverything() {
        service.disable(USER_ID);

        verify(users).disableTotp(USER_ID);
        verify(recoveryCodes).deleteAllFor(USER_ID);
    }
}
