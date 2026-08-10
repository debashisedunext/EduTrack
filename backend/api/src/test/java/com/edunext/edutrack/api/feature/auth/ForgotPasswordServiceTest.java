package com.edunext.edutrack.api.feature.auth;

import com.edunext.edutrack.domain.outbox.NewMail;
import com.edunext.edutrack.domain.outbox.OutboxEnqueuer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A-027 · what requesting a reset must and must not do.
 *
 * <p>Almost every assertion here is about an <b>absence</b>, because that is
 * where this endpoint's only real security property lives. A version that
 * returns 404 for an unknown address passes every happy-path test, works
 * perfectly in manual use, and hands anyone on the internet a way to ask "does
 * this person work here?" one address at a time.
 */
class ForgotPasswordServiceTest {

    private static final long USER_ID = 42L;
    private static final String EMAIL = "asha.rao@edunext.test";
    private static final String PASSWORD_HASH = "{argon2id}$hash";

    private AuthUserRepository users;
    private PasswordResetTokenRepository tokens;
    private OutboxEnqueuer outbox;
    private ForgotPasswordService service;

    @BeforeEach
    void setUp() {
        users = mock(AuthUserRepository.class);
        tokens = mock(PasswordResetTokenRepository.class);
        outbox = mock(OutboxEnqueuer.class);
        when(outbox.enqueue(any())).thenReturn(OptionalLong.of(1L));
        service = new ForgotPasswordService(
                users, tokens, new PasswordResetProperties(Duration.ofMinutes(30), null), outbox);
    }

    private static AuthUserRow row(boolean active) {
        return new AuthUserRow(USER_ID, "asha.rao", EMAIL, "Asha Rao",
                PASSWORD_HASH, "DEVELOPER", 3, "Asia/Kolkata",
                active, false, 0, null);
    }

    // ── the happy path ──────────────────────────────────────────────────────

    @Test
    @DisplayName("a known active address gets a token row and a queued mail")
    void issuesATokenAndQueuesMail() {
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(row(true)));

        service.requestReset(EMAIL);

        verify(tokens).insert(anyLong(), anyString(), any(Instant.class));
        verify(outbox).enqueue(any(NewMail.class));
    }

    /**
     * The token is a bearer credential that grants an account without a
     * password. Storing it as written would mean anyone reading a backup, a
     * replica or this table over someone's shoulder holds live account access.
     */
    @Test
    @DisplayName("only a SHA-256 digest is persisted, never the token itself")
    void persistsOnlyADigest() {
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(row(true)));

        service.requestReset(EMAIL);

        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(tokens).insert(anyLong(), stored.capture(), any(Instant.class));
        assertThat(stored.getValue())
                .as("SHA-256 hex is 64 characters; a base64url token is 43")
                .hasSize(64)
                .matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("the token expires 30 minutes out, per §10.3")
    void expiresInThirtyMinutes() {
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(row(true)));
        Instant before = Instant.now();

        service.requestReset(EMAIL);

        ArgumentCaptor<Instant> expiry = ArgumentCaptor.forClass(Instant.class);
        verify(tokens).insert(anyLong(), anyString(), expiry.capture());
        assertThat(expiry.getValue())
                .isBetween(before.plus(Duration.ofMinutes(29)), before.plus(Duration.ofMinutes(31)));
    }

    /**
     * Two requests must never produce the same token — otherwise one person's
     * link opens another person's account.
     */
    @Test
    @DisplayName("every request mints a different token")
    void mintsAFreshTokenEveryTime() {
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(row(true)));

        service.requestReset(EMAIL);
        service.requestReset(EMAIL);

        ArgumentCaptor<String> hashes = ArgumentCaptor.forClass(String.class);
        verify(tokens, org.mockito.Mockito.times(2))
                .insert(anyLong(), hashes.capture(), any(Instant.class));
        assertThat(hashes.getAllValues().get(0)).isNotEqualTo(hashes.getAllValues().get(1));
    }

    @Test
    @DisplayName("the mail goes to the address on the account, not to whatever was submitted")
    void mailsTheAddressOnRecord() {
        when(users.findByEmail(anyString())).thenReturn(Optional.of(row(true)));

        service.requestReset("ASHA.RAO@edunext.test");

        ArgumentCaptor<NewMail> mail = ArgumentCaptor.forClass(NewMail.class);
        verify(outbox).enqueue(mail.capture());
        assertThat(mail.getValue().toEmail()).isEqualTo(EMAIL);
        assertThat(mail.getValue().toUserId()).isEqualTo(USER_ID);
    }

    /**
     * {@code email_log} has no body column and D-029/D-030's template rendering
     * does not exist, so the subject is the only channel this mail has for the
     * one thing that makes it useful — the link. Losing this is a silent outage:
     * every other assertion in this class still passes, the mail still queues,
     * and the user simply has nothing to click.
     */
    @Test
    @DisplayName("the queued mail carries a working link, not just a label")
    void theMailCarriesTheLink() {
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(row(true)));

        service.requestReset(EMAIL);

        ArgumentCaptor<NewMail> mail = ArgumentCaptor.forClass(NewMail.class);
        verify(outbox).enqueue(mail.capture());
        assertThat(mail.getValue().subject())
                .as("the reset base URL and a token= query parameter must both be present")
                .contains("http://localhost:8080/reset-password")
                .contains("?token=");
    }

    /**
     * The link is not just present, it is the <i>right</i> one — the same value
     * whose digest was written to {@code password_reset_tokens}. A mismatch here
     * would mint a working token nobody can reach and mail a decoy that redeems
     * nothing.
     */
    @Test
    @DisplayName("the token in the link is the one that was actually stored")
    void theLinkedTokenMatchesTheStoredHash() {
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(row(true)));

        service.requestReset(EMAIL);

        ArgumentCaptor<String> storedHash = ArgumentCaptor.forClass(String.class);
        verify(tokens).insert(anyLong(), storedHash.capture(), any(Instant.class));

        ArgumentCaptor<NewMail> mail = ArgumentCaptor.forClass(NewMail.class);
        verify(outbox).enqueue(mail.capture());
        String subject = mail.getValue().subject();
        String linkedToken = subject.substring(subject.indexOf("?token=") + "?token=".length());

        assertThat(Digests.sha256Hex(linkedToken)).isEqualTo(storedHash.getValue());
    }

    // ── the enumeration guarantee ───────────────────────────────────────────

    /**
     * The single most important test in this class. An unknown address must
     * produce no exception, no token and no mail — and the caller cannot tell
     * that from a successful request.
     */
    @Test
    @DisplayName("an unknown address is accepted silently — no exception, no token, no mail")
    void anUnknownAddressIsSilent() {
        when(users.findByEmail(anyString())).thenReturn(Optional.empty());

        assertThatNoException().isThrownBy(() -> service.requestReset("nobody@edunext.test"));

        verify(tokens, never()).insert(anyLong(), anyString(), any());
        verify(outbox, never()).enqueue(any());
    }

    /**
     * A deactivated account must not be recoverable by whoever used to hold it —
     * that is the entire point of deactivating it. Silent, so the refusal is
     * indistinguishable from every other outcome.
     */
    @Test
    @DisplayName("a deactivated account is accepted silently and issues nothing")
    void aDeactivatedAccountIsSilent() {
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(row(false)));

        assertThatNoException().isThrownBy(() -> service.requestReset(EMAIL));

        verify(tokens, never()).insert(anyLong(), anyString(), any());
        verify(outbox, never()).enqueue(any());
    }

    /**
     * Whatever goes wrong, the method returns normally. An exception escaping
     * here would become a distinguishable response — a 500 for unknown addresses
     * and a 202 for real ones is the same oracle wearing a different hat.
     */
    @Test
    @DisplayName("no input produces an exception the caller could tell apart")
    void neverThrows() {
        when(users.findByEmail(anyString())).thenReturn(Optional.empty());

        assertThatNoException().isThrownBy(() -> service.requestReset(""));
        assertThatNoException().isThrownBy(() -> service.requestReset("not-an-address"));
    }
}
