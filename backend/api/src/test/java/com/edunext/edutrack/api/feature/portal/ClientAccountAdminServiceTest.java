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
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
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

    /**
     * The service under one setting of the temporary-password properties.
     *
     * <p>A factory rather than a mutable field, so a test that changes the
     * setting cannot leave it changed for the next one. {@code new
     * PortalTemporaryPasswordProperties(null, null)} is the shipped shape — the
     * compact constructor supplies the defaults, which are <b>on</b> with a
     * per-account generated password — and is what every test that does not
     * mention the setting gets.
     */
    private ClientAccountAdminService serviceWith(PortalTemporaryPasswordProperties temporaryPasswords) {
        return new ClientAccountAdminService(accounts, tokens, credentials, clients, outbox,
                encoder, new PortalPasswordRules(), temporaryPasswords,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @BeforeEach
    void setUp() {
        accounts = mock(ClientAccountRepository.class);
        tokens = mock(ClientCredentialTokenRepository.class);
        clients = mock(ObPrimaryContactReader.class);
        outbox = mock(ObOutboxEnqueuer.class);
        encoder = mock(PasswordEncoder.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        credentials = new ClientCredentialTokens(tokens, clock);
        service = serviceWith(new PortalTemporaryPasswordProperties(null, null));

        when(encoder.encode(anyString())).thenReturn("$argon2id$fake");
        when(clients.find(any(), eq(OB_CLIENT))).thenReturn(Optional.of(
                new ObPrimaryContactReader.ClientAndPrimary(
                        "Northwind Technologies Pvt Ltd", "NWT-001", CONTACT, "Meena Raghavan",
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
        @DisplayName("the username is the client's own code")
        void mintsUsername() {
            when(accounts.findByObClientId(OB_CLIENT))
                    .thenReturn(Optional.empty(), Optional.of(existingAccount(true)));

            service.create(ADMIN, OB_CLIENT, ACTOR);

            ArgumentCaptor<String> username = ArgumentCaptor.forClass(String.class);
            verify(accounts).insert(username.capture(), anyString(), eq(OB_CLIENT),
                    eq("Meena Raghavan"), eq("meena@northwind.example"), eq(ACTOR));
            // The code, unchanged — hyphen included. A login is one per client
            // and the code is what operations file them under, so the two are
            // deliberately the same string. See PortalUsernames#fromClientCode.
            assertThat(username.getValue()).isEqualTo("NWT-001");
        }

        /**
         * The counter that {@code uq_ob_clients_client_code} does not make
         * redundant: {@code client_accounts} is one table and the ticketing
         * master mints into it from a separate code column of its own.
         */
        @Test
        @DisplayName("a code already held in client_accounts gets the counter, not a constraint violation")
        void disambiguatesACodeAlreadyTaken() {
            when(accounts.findByObClientId(OB_CLIENT))
                    .thenReturn(Optional.empty(), Optional.of(existingAccount(true)));
            when(accounts.usernameExists("NWT-001")).thenReturn(true);

            service.create(ADMIN, OB_CLIENT, ACTOR);

            ArgumentCaptor<String> username = ArgumentCaptor.forClass(String.class);
            verify(accounts).insert(username.capture(), anyString(), eq(OB_CLIENT),
                    anyString(), anyString(), any());
            assertThat(username.getValue()).isEqualTo("NWT-0012");
        }

        /**
         * {@code client_code} arrived in V20260911_1800 and is nullable, so the
         * clients boarded before it still have none. Refusing them a login over
         * a field nobody ever asked them for would be the wrong answer; they
         * fall back to the name-and-SPOC scheme these accounts were minted with
         * to begin with.
         */
        @Test
        @DisplayName("a client with no code falls back to the name and the primary SPOC")
        void fallsBackForAClientWithNoCode() {
            when(clients.find(any(), eq(OB_CLIENT))).thenReturn(Optional.of(
                    new ObPrimaryContactReader.ClientAndPrimary(
                            "Northwind Technologies Pvt Ltd", null, CONTACT, "Meena Raghavan",
                            "meena@northwind.example")));
            when(accounts.findByObClientId(OB_CLIENT))
                    .thenReturn(Optional.empty(), Optional.of(existingAccount(true)));

            service.create(ADMIN, OB_CLIENT, ACTOR);

            ArgumentCaptor<String> username = ArgumentCaptor.forClass(String.class);
            verify(accounts).insert(username.capture(), anyString(), eq(OB_CLIENT),
                    anyString(), anyString(), any());
            // "Northwind Technologies Pvt Ltd" reduces to
            // NORTHWINDTECHNOLOGIESPVTLTD and is cut at twelve.
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

            // The placeholder, then the temporary password that replaces it.
            verify(encoder, atLeastOnce()).encode(anyString());
            // Account is a ten-field record and the only credential among them
            // is `temporaryPassword`, which is present on this response and on
            // reset's, and null on every read — see ClientAccountAdminDtos,
            // where `withoutCredential` is what makes that structural rather
            // than a habit. The count is the guard: a new field here fails this
            // test, so nothing joins the response without somebody deciding it
            // may. It last read nine, before the credential was added.
            assertThat(ClientAccountAdminDtos.Account.class.getRecordComponents()).hasSize(10);
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
                    new ObPrimaryContactReader.ClientAndPrimary("Northwind", "NWT-001", null, null, null)));

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

    /**
     * {@code edutrack.portal.temporary-password} — how a client's first
     * password comes into existence.
     *
     * <p>The first test here is the one that matters most, and it is about the
     * default. This was a development-only switch that defaulted to off; it now
     * defaults to <b>on</b>, because the link-only flow it replaced created
     * accounts nobody could sign in to wherever the mail did not arrive. A
     * regression to the old default is a silent return to that, so it is
     * asserted rather than left implied.
     */
    @Nested
    @DisplayName("the temporary password")
    class TemporaryPassword {

        @Test
        @DisplayName("on by default: a readable password is set and returned once")
        void onByDefault() {
            when(accounts.findByObClientId(OB_CLIENT))
                    .thenReturn(Optional.empty(), Optional.of(row(true)));

            ClientAccountAdminDtos.Account account = service.create(ADMIN, OB_CLIENT, ACTOR);

            assertThat(account.temporaryPassword()).isNotBlank();
            verify(accounts).setTemporaryPassword(eq(ACCOUNT), anyString());
        }

        /**
         * The half that makes exposing a credential acceptable.
         *
         * <p>{@code setTemporaryPassword} keeps {@code must_change_password}
         * set; {@code setPassword} clears it. Calling the wrong one leaves a
         * password an operator read off a screen sitting on a live account
         * with nothing ever asking for it to be changed — which is not a
         * failure any other test in this file would notice.
         */
        @Test
        @DisplayName("issues it as temporary, so the client is still made to change it")
        void keepsTheMustChangeFlag() {
            when(accounts.findByObClientId(OB_CLIENT))
                    .thenReturn(Optional.empty(), Optional.of(row(true)));

            service.create(ADMIN, OB_CLIENT, ACTOR);

            verify(accounts).setTemporaryPassword(eq(ACCOUNT), anyString());
            verify(accounts, never()).setPassword(anyLong(), anyString());
        }

        @Test
        @DisplayName("off: no password is set and none is returned")
        void offSetsNothing() {
            when(accounts.findByObClientId(OB_CLIENT))
                    .thenReturn(Optional.empty(), Optional.of(row(true)));
            ClientAccountAdminService off =
                    serviceWith(new PortalTemporaryPasswordProperties(false, null));

            ClientAccountAdminDtos.Account account = off.create(ADMIN, OB_CLIENT, ACTOR);

            assertThat(account.temporaryPassword()).isNull();
            // The placeholder is still encoded on insert; what must not happen
            // is the second write that replaces it with something knowable.
            verify(accounts, never()).setTemporaryPassword(anyLong(), anyString());
        }

        @Test
        @DisplayName("a generated password satisfies the portal's own rules")
        void generatedPasswordIsAccepted() {
            // The failure this pins is an account created with a password its
            // own login screen refuses — a dead end reachable if the generator
            // ever loses its shape.
            for (int i = 0; i < 200; i++) {
                assertThatNoException().isThrownBy(
                        () -> new PortalPasswordRules().enforce(
                                ClientCredentialTokens.readableTemporaryPassword()));
            }
        }

        @Test
        @DisplayName("generated passwords differ per account, so one login discloses no other")
        void generatedPasswordsDiffer() {
            // The property PortalTemporaryPasswordConfig refuses to start
            // without outside a development profile. If the generator ever
            // became deterministic, every client created would share a
            // password and one issued login would open all of them.
            assertThat(ClientCredentialTokens.readableTemporaryPassword())
                    .isNotEqualTo(ClientCredentialTokens.readableTemporaryPassword());
        }

        @Test
        @DisplayName("a configured password is shared, so a demo types one thing")
        void fixedPasswordIsUsed() {
            when(accounts.findByObClientId(OB_CLIENT))
                    .thenReturn(Optional.empty(), Optional.of(row(true)));
            ClientAccountAdminService fixed =
                    serviceWith(new PortalTemporaryPasswordProperties(true, "Demo-Passw0rd!"));

            assertThat(fixed.create(ADMIN, OB_CLIENT, ACTOR).temporaryPassword())
                    .isEqualTo("Demo-Passw0rd!");
        }

        @Test
        @DisplayName("a configured password the portal would refuse fails the request, not startup")
        void weakFixedPasswordIsRefused() {
            when(accounts.findByObClientId(OB_CLIENT)).thenReturn(Optional.empty());
            ClientAccountAdminService fixed =
                    serviceWith(new PortalTemporaryPasswordProperties(true, "short"));

            assertThatExceptionOfType(PortalAuthExceptions.WeakPortalPassword.class)
                    .isThrownBy(() -> fixed.create(ADMIN, OB_CLIENT, ACTOR));
        }

        @Test
        @DisplayName("the credential link is still minted and mailed, password or no password")
        void credentialPathStillRuns() {
            when(accounts.findByObClientId(OB_CLIENT))
                    .thenReturn(Optional.empty(), Optional.of(row(true)));

            service.create(ADMIN, OB_CLIENT, ACTOR);

            // The link is the recovery path now rather than the only way in,
            // and it has to keep working: a client who never received the
            // password needs a route that is not a phone call.
            verify(tokens).insert(anyLong(), anyString(), anyString(), any(), any());
            verify(outbox).enqueue(any());
        }

        @Test
        @DisplayName("the mail still carries no password, only the link")
        void mailCarriesNoPassword() {
            when(accounts.findByObClientId(OB_CLIENT))
                    .thenReturn(Optional.empty(), Optional.of(row(true)));
            ClientAccountAdminService fixed =
                    serviceWith(new PortalTemporaryPasswordProperties(true, "Demo-Passw0rd!"));

            fixed.create(ADMIN, OB_CLIENT, ACTOR);

            // B-111's ruling, and the reason the credential goes on the
            // response instead: ob_notification_outbox keeps its payload after
            // sending, so a password in it is a live credential in the
            // database indefinitely.
            ArgumentCaptor<ObNotification> mail = ArgumentCaptor.forClass(ObNotification.class);
            verify(outbox).enqueue(mail.capture());
            assertThat(mail.getValue().payload().values())
                    .noneMatch(v -> String.valueOf(v).contains("Demo-Passw0rd!"));
        }

        @Test
        @DisplayName("reset issues one too, or a client who lost theirs can never recover")
        void resetAlsoIssuesOne() {
            when(accounts.findByObClientId(OB_CLIENT)).thenReturn(Optional.of(row(true)));

            assertThat(service.resetPassword(ADMIN, OB_CLIENT, ACTOR).temporaryPassword())
                    .isNotBlank();
            verify(accounts).setTemporaryPassword(eq(ACCOUNT), anyString());
        }

        private ClientAccountRow row(boolean mustChangePassword) {
            return new ClientAccountRow(ACCOUNT, "NORTHWIND.meena", "$argon2id$fake", null,
                    OB_CLIENT, "Meena Raghavan", "meena@northwind.example", true,
                    mustChangePassword, 0, null, null);
        }
    }
}
