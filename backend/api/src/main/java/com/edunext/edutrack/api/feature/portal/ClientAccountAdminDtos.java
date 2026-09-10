package com.edunext.edutrack.api.feature.portal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * B-126 · the wire shapes for the OB-05/OB-08 client-account panel.
 *
 * <p><b>Nothing here carries a password, a hash, or a token.</b> The panel is
 * read by staff, and the one secret in this feature — the credential link —
 * goes to the client's own mailbox and nowhere else. A "here is the link, send
 * it yourself" field on this response would put a live credential on a staff
 * screen, in a browser cache and in whatever the reader pastes it into, and
 * would make the audit answer to "who could have used this login" everyone who
 * has ever opened the client.
 *
 * <p><b>The one exception is {@code devPassword}, and it is null in the
 * product.</b> It is populated only when {@link PortalDevCredentialProperties}
 * is switched on, which {@code PortalDevCredentialConfig} permits only under a
 * development profile. Everything in the paragraph above is still true of it —
 * that is precisely why it is gated rather than added. The field exists so a
 * demo can log in as a client it just invented, against a database where the
 * mail transport is {@code logging} and the credential link therefore arrives
 * nowhere.
 */
final class ClientAccountAdminDtos {

    private ClientAccountAdminDtos() {
    }

    /**
     * The panel's read model.
     *
     * <p>{@code lastLoginAt} and {@code lockedUntil} are here because they are
     * the two questions support actually asks — "has this client ever logged
     * in" and "are they locked out right now" — and both are answerable from
     * {@code client_accounts} without a second call.
     */
    @Schema(name = "ObClientAccount")
    record Account(
            long id,
            String username,
            String displayName,
            String email,
            boolean isActive,
            boolean mustChangePassword,
            Instant lastLoginAt,
            Instant lockedUntil,
            Instant credentialSentAt,
            @Schema(description = "Development builds only; null in the product. See the schema note.")
            String devPassword
    ) {
        /**
         * The read model's shape: an account as the panel lists it, with no
         * credential.
         *
         * <p>A factory rather than a null literal at each call site, so that
         * "this response carries no password" is a thing the code says once. A
         * reader auditing this file for credential exposure has two
         * constructors to check instead of every {@code new Account(...)} in
         * the service.
         */
        static Account withoutCredential(long id, String username, String displayName, String email,
                                         boolean isActive, boolean mustChangePassword,
                                         Instant lastLoginAt, Instant lockedUntil,
                                         Instant credentialSentAt) {
            return new Account(id, username, displayName, email, isActive, mustChangePassword,
                    lastLoginAt, lockedUntil, credentialSentAt, null);
        }

        /**
         * The same account, carrying the password a development deployment
         * just set on it.
         *
         * <p>Reached only from {@code ClientAccountAdminService} under
         * {@link PortalDevCredentialProperties#issuesReadablePassword()}.
         */
        Account withDevPassword(String password) {
            return new Account(id, username, displayName, email, isActive, mustChangePassword,
                    lastLoginAt, lockedUntil, credentialSentAt, password);
        }
    }

    record AccountResponse(Account data) {
    }

    /**
     * Enable or disable, stated rather than toggled.
     *
     * <p>A toggle route would make the outcome depend on what the caller
     * believed the current state to be, which on a two-person support desk is
     * how a login gets switched back on by the person who did not know it had
     * been switched off. {@code PATCH} with the state you want is the same call
     * {@code setUserStatus} makes for staff and for the same reason.
     */
    @Schema(name = "ObClientAccountStatusRequest")
    record StatusRequest(
            @NotNull
            Boolean isActive
    ) {
    }
}
