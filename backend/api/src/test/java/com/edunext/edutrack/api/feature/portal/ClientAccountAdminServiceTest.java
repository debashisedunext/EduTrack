package com.edunext.edutrack.api.feature.portal;

import com.edunext.edutrack.api.feature.onboarding.clients.ObClientScope;
import com.edunext.edutrack.domain.onboarding.outbox.ObNotification;
import com.edunext.edutrack.domain.onboarding.outbox.ObNotificationEvent;
import com.edunext.edutrack.domain.onboarding.outbox.ObOutboxEnqueuer;
import com.edunext.edutrack.domain.onboarding.outbox.ObRecipient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-126 · what the panel issues, what it refuses, and the one thing it must
 * never put on the wire.
 *
 * <p>The clock is fixed on {@code ObSignoffTokensTest}'s precedent: an expiry
 * cannot be asserted against a clock that only moves forwards.
 */
class ClientAccountAdminServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-09T14:00:00Z");
    private static final long OB_CLIENT = 9L;
    private static final long ACCOUNT = 55L;
    private static final long CONTACT = 42L;
    private static final long ACTOR = 7L;

    private static final ObClientScope ADMIN = new ObClientScope("OB_ADMIN", ACTOR);
    private static final ObClientScope SALES = new ObClientScope("OB_SALES", ACTOR);

    private ClientAccountRepository accounts;
    private ClientCredentialTokenRepository tokens;
    private ClientCredentialTokens credentials;
    private ObPrimaryContactReader clients;
    private ObOutboxEnqueuer outbox;
    private PasswordEncoder encoder;
    private ClientAccountAdminService service;

    @BeforeEach
    void setUp() {
        accounts = mock(ClientAccountRepository.class);
        tokens = mock(ClientCredentialTokenRepository.class);
        clients = mock(ObPrimaryContactReader.class);
        outbox = mock(ObOutboxEnqueuer.class);
        encoder = mock(PasswordEncoder.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        credentials = new ClientCredentialTokens(tokens, clock);
        service = new ClientAccountAdminService(accounts, tokens, credentials, clients, outbox,
                encoder, clock);

        when(encoder.encode(anyString())).thenReturn("$argon2id$fake");
        when(clients.find(any(), eq(OB_CLIENT))).thenReturn(Optional.of(
                new ObPrimaryContactReader.ClientAndPrimary(
                        "Northwind Technologies Pvt Ltd", CONTACT, "Meena Raghavan",
                        "meena@northwind.example")));
        when(accounts.insert(anyString(), anyString(), anyLong(), anyString(), anyString(), any()))
                .thenReturn(ACCOUNT);
        when(tokens.lastIssuedAt(ACCOUNT)).thenReturn(Optional.of(NOW));
    }

    private ClientAccountRow existingAccount(boolean active) {
        return new ClientAccountRow(ACCOUNT, "NORTHWIND.meena", "$argon2id$fake",
                null, OB_CLIENT, "Meena Raghavan", "meena@northwind.example",
                active, true, 0, null, null);
    }

    private ObNotification captureMail() {
        ArgumentCaptor<ObNotification> captor = ArgumentCaptor.forClass(ObNotification.class);
        verify(outbox).enqueue(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("mints a username from the client name and the primary SPOC")
        void mintsUsername() {
            when(accounts.findByObClientId(OB_CLIENT))
                    .thenReturn(Optional.empty(), Optional.of(existingAccount(true)));

            service.create(ADMIN, OB_CLIENT, ACTOR);

            ArgumentCaptor<String> username = ArgumentCaptor.forClass(String.class);
            verify(accounts).insert(username.capture(), anyString(), eq(OB_CLIENT),
                    eq("Meena Raghavan"), eq("meena@northwind.example"), eq(ACTOR));
            // ob_clients has no code column, so the prefix is derived from the
            // name — see ClientAccountAdminService#usernamePrefix. "Northwind
            // Technologies Pvt Ltd" reduces to NORTHWINDTECHNOLOGIESPVTLTD and
            // is cut at twelve, which is the length that keeps the whole
            // username inside VARCHAR(50) with a real given name after it.
            assertThat(username.getValue()).isEqualTo("NORTHWINDTEC.meena");
        }

        @Test
        @DisplayName("queues a CLIENT_LOGIN_CREATED mail to the primary SPOC carrying a link")
        void queuesTheCredentialMail() {
            when(accounts.findByObClientId(OB_CLIENT))
                    .thenReturn(Optional.empty(), Optional.of(existingAccount(true)));

            service.create(ADMIN, OB_CLIENT, ACTOR);

            ObNotification mail = captureMail();
            assertThat(mail.eventKey()).isEqualTo(ObNotificationEvent.CLIENT_LOGIN_CREATED.name());
            assertThat(mail.recipient()).isEqualTo(new ObRecipient.Client(CONTACT));
            assertThat(mail.payload()).containsKeys("client_name", "portal_username", "action_url");
        }

        @Test
        @DisplayName("the mail carries no password — B-111's ruling, and the payload outlives the send")
        void neverMailsAPassword() {
            when(accounts.findByObClientId(OB_CLIENT))
                    .thenReturn(Optional.empty(), Optional.of(existingAccount(true)));

            service.create(ADMIN, OB_CLIENT, ACTOR);

            ObNotification mail = captureMail();
            assertThat(mail.payload()).doesNotContainKeys("password", "temporary_password", "otp_code");
            // ob_notification_outbox keeps its payload after sending. Anything
            // here is readable for as long as the row exists.
            assertThat(mail.payload().values())
                    .noneMatch(v -> String.valueOf(v).contains("$argon2id$"));
        }

        @Test
        @DisplayName("the account's own password is hashed and never returned")
        void placeholderPasswordIsHashed() {
            when(accounts.findByObClientId(OB_CLIENT))
                    .thenReturn(Optional.empty(), Optional.of(existingAccount(true)));

            ClientAccountAdminDtos.Account account = service.create(ADMIN, OB_CLIENT, ACTOR);

            verify(encoder).encode(anyString());
            // Account is a nine-field record and none of them is a credential.
            assertThat(ClientAccountAdminDtos.Account.class.getRecordComponents()).hasSize(9);
            // Read back off the row, so this asserts the response carries the
            // stored username rather than re-deriving it.
            assertThat(account.username()).isEqualTo("NORTHWIND.meena");
        }

        @Test
        @DisplayName("refuses a client that already has a login rather than resetting it")
        void refusesADuplicate() {
            when(accounts.findByObClientId(OB_CLIENT)).thenReturn(Optional.of(existingAccount(true)));

            assertThatExceptionOfType(ClientAccountAlreadyExistsException.class)
                    .isThrownBy(() -> service.create(ADMIN, OB_CLIENT, ACTOR));
            verify(accounts, never()).insert(anyString(), anyString(), anyLong(), anyString(),
                    anyString(), any());
            verify(outbox, never()).enqueue(any());
        }

        @Test
        @DisplayName("refuses a client with no active primary SPOC, before writing anything")
        void refusesWithNoPrimary() {
            when(clients.find(any(), eq(OB_CLIENT))).thenReturn(Optional.of(
                    new ObPrimaryContactReader.ClientAndPrimary("Northwind", null, null, null)));

            assertThatExceptionOfType(NoPrimaryContactException.class)
                    .isThrownBy(() -> service.create(ADMIN, OB_CLIENT, ACTOR));
            // The account is not created. B-102's argument one step earlier: an
            // account nobody was told about is worse than a refusal.
            verify(accounts, never()).insert(anyString(), anyString(), anyLong(), anyString(),
                    anyString(), any());
            verify(outbox, never()).enqueue(any());
        }

        @Test
        @DisplayName("a client outside the caller's scope is 404, not 403")
        void outOfScopeIs404() {
            when(clients.find(any(), eq(OB_CLIENT))).thenReturn(Optional.empty());

            assertThatExceptionOfType(ClientAccountNotFoundException.class)
                    .isThrownBy(() -> service.create(SALES, OB_CLIENT, ACTOR));
        }
    }

    @Nested
    @DisplayName("reset")
    class Reset {

        @Test
        @DisplayName("retires the outstanding link before minting a new one")
        void retiresTheOldLink() {
            when(accounts.findByObClientId(OB_CLIENT)).thenReturn(Optional.of(existingAccount(true)));

            service.resetPassword(ADMIN, OB_CLIENT, ACTOR);

            // A mail from three months ago is otherwise a second way in that
            // nobody remembers exists — the opposite of what "reset" asks for.
            verify(tokens).retireOutstanding(ACCOUNT, NOW);
            verify(tokens).insert(eq(ACCOUNT), anyString(), eq("RESET"), any(), eq(ACTOR));
        }

        @Test
        @DisplayName("queues CLIENT_PASSWORD_RESET, which declares no username variable")
        void queuesTheResetMail() {
            when(accounts.findByObClientId(OB_CLIENT)).thenReturn(Optional.of(existingAccount(true)));

            service.resetPassword(ADMIN, OB_CLIENT, ACTOR);

            ObNotification mail = captureMail();
            assertThat(mail.eventKey()).isEqualTo(ObNotificationEvent.CLIENT_PASSWORD_RESET.name());
            // That event's catalogue entry declares client_name and action_url
            // and nothing else — "the link is the whole mail". A variable no
            // template declares is silently dropped, which is a worse failure
            // than not sending it.
            assertThat(mail.payload()).containsOnlyKeys("client_name", "action_url");
        }

        @Test
        @DisplayName("works on a disabled account without re-enabling it")
        void doesNotReEnable() {
            when(accounts.findByObClientId(OB_CLIENT)).thenReturn(Optional.of(existingAccount(false)));

            service.resetPassword(ADMIN, OB_CLIENT, ACTOR);

            verify(accounts, never()).setActive(anyLong(), eq(true));
        }

        @Test
        @DisplayName("a client with no login is 404")
        void noAccountIs404() {
            when(accounts.findByObClientId(OB_CLIENT)).thenReturn(Optional.empty());

            assertThatExceptionOfType(ClientAccountNotFoundException.class)
                    .isThrownBy(() -> service.resetPassword(ADMIN, OB_CLIENT, ACTOR));
            verify(outbox, never()).enqueue(any());
        }
    }

    @Nested
    @DisplayName("enable and disable")
    class Status {

        @Test
        @DisplayName("disabling retires every outstanding credential link")
        void disablingClosesTheDoor() {
            when(accounts.findByObClientId(OB_CLIENT)).thenReturn(Optional.of(existingAccount(true)));

            service.setActive(ADMIN, OB_CLIENT, false);

            verify(accounts).setActive(ACCOUNT, false);
            // Without this a mail sent yesterday is still redeemable against an
            // account somebody has just switched off.
            verify(tokens).retireOutstanding(ACCOUNT, NOW);
        }

        @Test
        @DisplayName("enabling issues nothing and mails nobody")
        void enablingRestoresRatherThanReissues() {
            when(accounts.findByObClientId(OB_CLIENT)).thenReturn(Optional.of(existingAccount(false)));

            service.setActive(ADMIN, OB_CLIENT, true);

            verify(accounts).setActive(ACCOUNT, true);
            verify(tokens, never()).retireOutstanding(anyLong(), any());
            verify(tokens, never()).insert(anyLong(), anyString(), anyString(), any(), any());
            verify(outbox, never()).enqueue(any());
        }

        @Test
        @DisplayName("a client outside the caller's scope is 404 before anything is written")
        void outOfScopeIs404() {
            when(clients.find(any(), eq(OB_CLIENT))).thenReturn(Optional.empty());

            assertThatExceptionOfType(ClientAccountNotFoundException.class)
                    .isThrownBy(() -> service.setActive(SALES, OB_CLIENT, false));
            verify(accounts, never()).setActive(anyLong(), any(Boolean.class));
        }
    }
}
