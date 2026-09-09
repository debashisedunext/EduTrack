package com.edunext.edutrack.api.feature.portal;

import com.edunext.edutrack.api.feature.auth.Digests;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A-130 · redeeming the credential link.
 *
 * <p>The cases worth having are the ones where the obvious implementation is
 * wrong in a way that reads as correct: a link that is refused but stays spent,
 * a weak password that burns the link, and two redemptions that both succeed.
 * Each is a support call rather than a stack trace, which is why they are
 * pinned here rather than left to an integration test to notice.
 */
class PortalCredentialServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-09T10:00:00Z");
    private static final String TOKEN = "a-plaintext-token";
    private static final String GOOD_PASSWORD = "Correct-Horse-1!";

    private ClientCredentialTokenRepository tokens;
    private ClientAccountRepository accounts;
    private PasswordEncoder encoder;
    private PortalCredentialService service;

    @BeforeEach
    void setUp() {
        tokens = mock(ClientCredentialTokenRepository.class);
        accounts = mock(ClientAccountRepository.class);
        encoder = mock(PasswordEncoder.class);
        when(encoder.encode(anyString())).thenReturn("$argon2id$hashed");

        service = new PortalCredentialService(tokens, accounts, new PortalPasswordRules(), encoder,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private CredentialTokenRow token(Instant expiresAt, Instant usedAt) {
        return new CredentialTokenRow(7L, 42L, "INITIAL", expiresAt, usedAt);
    }

    private ClientAccountRow account(boolean active) {
        return new ClientAccountRow(42L, "ACME.ravi", "$argon2id$placeholder", null, 9L,
                "Ravi Shah", "ravi@acme.example", active, true, 0, null, null);
    }

    private void given(CredentialTokenRow row, ClientAccountRow account) {
        when(tokens.findByHash(Digests.sha256Hex(TOKEN))).thenReturn(Optional.of(row));
        when(accounts.findById(42L)).thenReturn(Optional.ofNullable(account));
        when(tokens.markUsed(eq(7L), any())).thenReturn(true);
    }

    private static Instant any() {
        return org.mockito.ArgumentMatchers.any(Instant.class);
    }

    @Nested
    @DisplayName("a link that cannot be redeemed")
    class Refusals {

        @Test
        @DisplayName("an unknown token is refused without touching the account")
        void unknownToken() {
            when(tokens.findByHash(anyString())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.redeem(TOKEN, GOOD_PASSWORD))
                    .isInstanceOf(PortalAuthExceptions.InvalidCredentialLink.class);

            verify(accounts, never()).setPassword(anyLong(), anyString());
        }

        @Test
        @DisplayName("an expired link is refused, and the same way an unknown one is")
        void expired() {
            given(token(NOW.minusSeconds(1), null), account(true));

            assertThatThrownBy(() -> service.redeem(TOKEN, GOOD_PASSWORD))
                    .isInstanceOf(PortalAuthExceptions.InvalidCredentialLink.class);

            verify(accounts, never()).setPassword(anyLong(), anyString());
        }

        /** Expiry is inclusive: a link is dead at its expiry instant, not after it. */
        @Test
        @DisplayName("a link expiring exactly now is already gone")
        void expiringNow() {
            given(token(NOW, null), account(true));

            assertThatThrownBy(() -> service.redeem(TOKEN, GOOD_PASSWORD))
                    .isInstanceOf(PortalAuthExceptions.InvalidCredentialLink.class);
        }

        @Test
        @DisplayName("a spent link cannot be spent twice")
        void alreadyUsed() {
            given(token(NOW.plusSeconds(3600), NOW.minusSeconds(60)), account(true));

            assertThatThrownBy(() -> service.redeem(TOKEN, GOOD_PASSWORD))
                    .isInstanceOf(PortalAuthExceptions.InvalidCredentialLink.class);

            verify(accounts, never()).setPassword(anyLong(), anyString());
        }

        /**
         * Refused at the link rather than at the next screen. Somebody whose
         * access was withdrawn between the mail being sent and being read should
         * not choose a password and only then be told it was pointless.
         */
        @Test
        @DisplayName("a deactivated account cannot redeem")
        void deactivatedAccount() {
            given(token(NOW.plusSeconds(3600), null), account(false));

            assertThatThrownBy(() -> service.redeem(TOKEN, GOOD_PASSWORD))
                    .isInstanceOf(PortalAuthExceptions.InvalidCredentialLink.class);

            verify(accounts, never()).setPassword(anyLong(), anyString());
        }
    }

    @Nested
    @DisplayName("redeeming")
    class Redeeming {

        @Test
        @DisplayName("sets the password and spends the link")
        void happyPath() {
            given(token(NOW.plusSeconds(3600), null), account(true));

            service.redeem(TOKEN, GOOD_PASSWORD);

            verify(tokens).markUsed(7L, NOW);
            verify(accounts).setPassword(42L, "$argon2id$hashed");
        }

        /**
         * The race the {@code used_at IS NULL} predicate exists for. Both
         * requests read a live row; only one update changes anything, and the
         * loser must not go on to set a password — otherwise the winner's user
         * is handed a password that silently stops working.
         */
        @Test
        @DisplayName("the redemption that loses the race sets no password")
        void concurrentRedemption() {
            given(token(NOW.plusSeconds(3600), null), account(true));
            when(tokens.markUsed(eq(7L), any())).thenReturn(false);

            assertThatThrownBy(() -> service.redeem(TOKEN, GOOD_PASSWORD))
                    .isInstanceOf(PortalAuthExceptions.InvalidCredentialLink.class);

            verify(accounts, never()).setPassword(anyLong(), anyString());
        }

        /**
         * A typo must not cost the client their link. The password is validated
         * before anything is spent, so the form can be resubmitted.
         */
        @Test
        @DisplayName("a weak password leaves the link usable")
        void weakPasswordDoesNotBurnTheLink() {
            given(token(NOW.plusSeconds(3600), null), account(true));

            assertThatThrownBy(() -> service.redeem(TOKEN, "short"))
                    .isInstanceOf(PortalAuthExceptions.WeakPortalPassword.class);

            verify(tokens, never()).markUsed(anyLong(), any());
            verify(accounts, never()).setPassword(anyLong(), anyString());
        }

        /** The plaintext is hashed before it is looked up; the table never sees it. */
        @Test
        @DisplayName("looks the token up by its SHA-256, never by the plaintext")
        void looksUpByHash() {
            given(token(NOW.plusSeconds(3600), null), account(true));

            service.redeem(TOKEN, GOOD_PASSWORD);

            verify(tokens).findByHash(Digests.sha256Hex(TOKEN));
            verify(tokens, never()).findByHash(TOKEN);
        }
    }

    @Nested
    @DisplayName("describing a link")
    class Describing {

        @Test
        @DisplayName("names the username to sign in with, and nothing else about the account")
        void describes() {
            given(token(NOW.plusSeconds(3600), null), account(true));

            PortalAuthDtos.CredentialLink link = service.describe(TOKEN);

            assertThat(link.username()).isEqualTo("ACME.ravi");
            assertThat(link.displayName()).isEqualTo("Ravi Shah");
            assertThat(link.expiresAt()).isEqualTo(NOW.plusSeconds(3600));
        }

        /** A GET must not be able to spend a link — mail clients prefetch URLs. */
        @Test
        @DisplayName("does not spend the link")
        void doesNotSpend() {
            given(token(NOW.plusSeconds(3600), null), account(true));

            service.describe(TOKEN);

            verify(tokens, never()).markUsed(anyLong(), any());
        }
    }
}
