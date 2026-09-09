package com.edunext.edutrack.api.feature.portal;

import com.edunext.edutrack.api.feature.onboarding.clients.ObClientScope;
import com.edunext.edutrack.domain.onboarding.outbox.ObChannel;
import com.edunext.edutrack.domain.onboarding.outbox.ObNotification;
import com.edunext.edutrack.domain.onboarding.outbox.ObNotificationEvent;
import com.edunext.edutrack.domain.onboarding.outbox.ObOutboxEnqueuer;
import com.edunext.edutrack.domain.onboarding.outbox.ObRecipient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * B-126 · create, reset and disable a client's portal login.
 *
 * <h2>Explicit, which is the whole task</h2>
 *
 * <p>The backlog line is "<b>explicit</b> create/reset/disable", and the word is
 * carrying the design. B-102 already refused {@code createPortalLogin: true}
 * rather than ignoring it, and gave the reason: ignoring the flag means "a
 * boarder ticks the box, sees a 201, tells the client their credentials are
 * coming, and nothing was ever sent — a failure the client discovers days later
 * and that looks, from our side, exactly like a bounced mail". Every operation
 * here answers with what it did, and every one of them either issues a link or
 * refuses out loud.
 *
 * <h2>Three operations, one credential path</h2>
 *
 * <p>Create and reset both end in the same place: retire whatever link was
 * outstanding, mint a new one, and queue a mail to the primary SPOC. They
 * differ in the event they queue and in whether a row is inserted first. Disable
 * issues nothing — it is the one operation that must not put a new way in.
 *
 * <h2>The mail carries a link, never a password</h2>
 *
 * <p>B-111's ruling, restated where it is cashed in:
 * {@code CLIENT_LOGIN_CREATED} declares no password variable because
 * "the payload is JSON on a row that outlives the send, so a temporary password
 * in it is a live credential in the database indefinitely". See
 * {@link ClientCredentialTokens} for the account's own placeholder password and
 * why it exists.
 *
 * <h2>What this task does not build, said out loud</h2>
 *
 * <p><b>The page the link lands on does not exist yet.</b> The portal's route
 * tree is A-126 and its screens are C-121's CP-01..CP-04; neither is on
 * {@code develop}, and there is no portal login route in the contract. So a
 * credential mail queued today points at a path nothing serves. That is stated
 * here rather than discovered, and it is deliberately not worked around by
 * mailing a password instead — the wrong fix to a temporary problem, and one
 * that would leave a live credential in {@code ob_notification_outbox} for
 * good. B-112 made the same call in reverse, shipping its page early so the
 * digest's links had somewhere to land; here the page is not this stream's to
 * write. Raised on the PR.
 *
 * <h2>Audited without writing an audit</h2>
 *
 * <p>{@code AuditInterceptor} records every mutating request by method and route
 * pattern, so all three operations are audited by being routes. A bespoke audit
 * call here would be a second record of the same event, and the two would
 * disagree the first time one of them was moved.
 */
@Service
public class ClientAccountAdminService {

    private final ClientAccountRepository accounts;
    private final ClientCredentialTokenRepository tokens;
    private final ClientCredentialTokens credentials;
    private final ObPrimaryContactReader clients;
    private final ObOutboxEnqueuer outbox;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    ClientAccountAdminService(ClientAccountRepository accounts,
                              ClientCredentialTokenRepository tokens,
                              ClientCredentialTokens credentials,
                              ObPrimaryContactReader clients,
                              ObOutboxEnqueuer outbox,
                              PasswordEncoder passwordEncoder,
                              Clock clock) {
        this.accounts = accounts;
        this.tokens = tokens;
        this.credentials = credentials;
        this.clients = clients;
        this.outbox = outbox;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    /**
     * The panel's read. Absent is a normal answer — most clients have no portal
     * login — so this is an {@code Optional} rather than a throw, and the
     * controller renders 404 only because the contract's read is addressed at
     * the account rather than at the client.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<ClientAccountAdminDtos.Account> find(ObClientScope scope, long obClientId) {
        if (clients.find(scope, obClientId).isEmpty()) {
            return java.util.Optional.empty();
        }
        return accounts.findByObClientId(obClientId).map(this::toDto);
    }

    /**
     * Creates the login and mails the primary SPOC a link.
     *
     * @throws ClientAccountAlreadyExistsException the client already has one.
     *         Refused rather than replaced: {@code uq_client_accounts_ob_client}
     *         allows one per client precisely so that there are never two
     *         credentials to revoke, and "create" silently becoming "reset"
     *         would issue a new link to somebody who asked for a new account.
     * @throws NoPrimaryContactException the client has no active primary SPOC,
     *         so the mail has nowhere to go. Refused before the row is written,
     *         so the operator does not end up with an account nobody was told
     *         about — B-102's own argument, applied one step earlier.
     */
    @Transactional
    public ClientAccountAdminDtos.Account create(ObClientScope scope, long obClientId, Long actorUserId) {
        ObPrimaryContactReader.ClientAndPrimary client = requireClient(scope, obClientId);
        if (!client.hasPrimary()) {
            throw new NoPrimaryContactException(obClientId);
        }
        if (accounts.findByObClientId(obClientId).isPresent()) {
            throw new ClientAccountAlreadyExistsException(obClientId);
        }

        String username = PortalUsernames.generate(
                usernamePrefix(client.clientName()), client.contactName(), accounts::usernameExists);

        long accountId = accounts.insert(
                username,
                passwordEncoder.encode(ClientCredentialTokens.unguessablePlaceholderPassword()),
                obClientId,
                client.contactName(),
                client.contactEmail(),
                actorUserId);

        issueCredential(accountId, obClientId, client, username,
                ClientCredentialTokens.PURPOSE_INITIAL,
                ObNotificationEvent.CLIENT_LOGIN_CREATED, actorUserId);

        return find(scope, obClientId).orElseThrow(() -> new ClientAccountNotFoundException(obClientId));
    }

    /**
     * Issues a fresh link and retires the last one.
     *
     * <p>Works on a disabled account, deliberately. Resetting and re-enabling
     * are two decisions and an operator may reasonably make the first while
     * still thinking about the second; folding them together would mean a reset
     * silently switched a login back on.
     */
    @Transactional
    public ClientAccountAdminDtos.Account resetPassword(ObClientScope scope, long obClientId, Long actorUserId) {
        ObPrimaryContactReader.ClientAndPrimary client = requireClient(scope, obClientId);
        if (!client.hasPrimary()) {
            throw new NoPrimaryContactException(obClientId);
        }
        ClientAccountRow account = accounts.findByObClientId(obClientId)
                .orElseThrow(() -> new ClientAccountNotFoundException(obClientId));

        issueCredential(account.id(), obClientId, client, account.username(),
                ClientCredentialTokens.PURPOSE_RESET,
                ObNotificationEvent.CLIENT_PASSWORD_RESET, actorUserId);

        return find(scope, obClientId).orElseThrow(() -> new ClientAccountNotFoundException(obClientId));
    }

    /**
     * Enable or disable, as a stated value.
     *
     * <p><b>Disabling retires every outstanding link.</b> Without that, a
     * credential mail sent yesterday would still be redeemable against an
     * account somebody has just switched off — and the operator pressing
     * "disable" believes they have closed the door. Nothing is mailed either
     * way: a client does not need to be told the moment their access is
     * suspended by a mail they cannot act on, and re-enabling restores the
     * account rather than issuing a new one.
     */
    @Transactional
    public ClientAccountAdminDtos.Account setActive(ObClientScope scope, long obClientId, boolean active) {
        requireClient(scope, obClientId);
        ClientAccountRow account = accounts.findByObClientId(obClientId)
                .orElseThrow(() -> new ClientAccountNotFoundException(obClientId));

        accounts.setActive(account.id(), active);
        if (!active) {
            tokens.retireOutstanding(account.id(), clock.instant());
        }
        return find(scope, obClientId).orElseThrow(() -> new ClientAccountNotFoundException(obClientId));
    }

    /**
     * <p>Out of scope answers exactly as "no such client" does — the
     * 404-not-403 rule, which matters here even though the caller is staff: a
     * Sales user who may only see clients they created should not learn that
     * client 412 exists by being told they may not touch its login.
     */
    private ObPrimaryContactReader.ClientAndPrimary requireClient(ObClientScope scope, long obClientId) {
        return clients.find(scope, obClientId)
                .orElseThrow(() -> new ClientAccountNotFoundException(obClientId));
    }

    private void issueCredential(long accountId, long obClientId,
                                 ObPrimaryContactReader.ClientAndPrimary client,
                                 String username, String purpose,
                                 ObNotificationEvent event, Long actorUserId) {

        ClientCredentialTokens.Minted minted = credentials.mint(accountId, purpose, actorUserId);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("client_name", client.clientName());
        // `portal_username` only on CLIENT_LOGIN_CREATED — CLIENT_PASSWORD_RESET's
        // own catalogue entry declares client_name and action_url and nothing
        // else, and its javadoc says "the link is the whole mail". Sending a
        // variable no template declares would be silently dropped, which is a
        // worse failure than not sending it.
        if (event == ObNotificationEvent.CLIENT_LOGIN_CREATED) {
            payload.put("portal_username", username);
        }
        payload.put("action_url", credentialUrl(minted.token()));

        outbox.enqueue(new ObNotification(
                event.name(),
                ObChannel.EMAIL,
                new ObRecipient.Client(client.contactId()),
                obClientId,
                null,
                null,
                payload,
                // The token is part of the dedupe key so that two resets a
                // minute apart are two mails rather than one. They carry
                // different links and the second is the one that works.
                event.name() + ":" + accountId + ":" + minted.expiresAt().toEpochMilli()));
    }

    /**
     * <p>A relative path, on {@code ObMailLinks}' own convention: the origin is
     * the deployment's and belongs to whatever renders the mail, not to a
     * service that has no idea what host it is reachable on.
     */
    private static String credentialUrl(String token) {
        return "/portal/set-password?token=" + token;
    }

    /**
     * The username prefix, derived from the client's name.
     *
     * <p><b>{@code ob_clients} has no code column</b> — it carries a name and
     * nothing shorter — whereas {@code PortalUsernames} was written against the
     * ticketing master's {@code code} and says the prefix "is already unique and
     * already upper-case". Neither holds here, and both are worth being explicit
     * about rather than papering over.
     *
     * <p>Uniqueness is not lost, because it was never carried by the prefix
     * alone: {@code PortalUsernames} disambiguates with a counter against
     * {@code usernameExists}, and {@code uq_client_accounts_username} is the
     * backstop. Two clients whose names both reduce to {@code NORTHWIND} get
     * {@code NORTHWIND.meena} and {@code NORTHWIND.meena2}, which is the same
     * outcome as two people called Meena at one client — slightly less
     * informative, never wrong.
     *
     * <p>Deliberately <b>not</b> resolved through the ticketing master's code
     * even when the same organisation exists there. V20260905_1630's header is
     * explicit that nothing in the schema links the two, so matching them by
     * name would be inventing a correspondence nobody established — and putting
     * it in a login name, where it would look authoritative.
     */
    private static String usernamePrefix(String clientName) {
        String reduced = (clientName == null ? "" : clientName)
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]", "");
        if (reduced.isEmpty()) {
            // A name that is entirely non-Latin reduces to nothing. `CLIENT` is
            // PortalUsernames' own `user` fallback applied to the other half:
            // honest, still disambiguated by the counter, and it does not
            // transliterate an organisation's name into something nobody there
            // would recognise.
            return "CLIENT";
        }
        return reduced.length() > 12 ? reduced.substring(0, 12) : reduced;
    }

    private ClientAccountAdminDtos.Account toDto(ClientAccountRow row) {
        return new ClientAccountAdminDtos.Account(
                row.id(),
                row.username(),
                row.displayName(),
                row.email(),
                row.active(),
                row.mustChangePassword(),
                row.lastLoginAt(),
                row.lockedUntil(),
                tokens.lastIssuedAt(row.id()).orElse(null));
    }
}
