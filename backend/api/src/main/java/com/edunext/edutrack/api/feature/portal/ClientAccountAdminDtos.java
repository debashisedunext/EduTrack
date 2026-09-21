package com.edunext.edutrack.api.feature.portal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * B-126 · the wire shapes for the OB-05/OB-08 client-account panel.
 *
 * <p><b>The panel's read carries no credential.</b> Staff read this screen,
 * and a "here is the link, send it yourself" field on it would put a live
 * credential in a browser cache and in whatever the reader pastes it into, and
 * would make the audit answer to "who could have used this login" everyone who
 * has ever opened the client.
 *
 * <p><b>{@code temporaryPassword} is the one exception, and it is present only
 * on the response to the request that created it.</b> {@link Account#withoutCredential}
 * is what every read uses and it cannot carry one; only
 * {@code ClientAccountAdminService}'s create and reset reach
 * {@link Account#withTemporaryPassword}. So the credential is on exactly one
 * response — the single moment the operator who asked for the login is entitled
 * to read it — and on no subsequent {@code GET}.
 *
 * <p>Everything in the first paragraph is still true of that field. It is an
 * accepted cost rather than a refused one, for the reason
 * {@link PortalTemporaryPasswordProperties} gives: the flow that refused it
 * produced accounts nobody could ever sign in to wherever the mail did not
 * arrive. What bounds the cost is that the password opens exactly one session
 * and that session can do nothing but change it —
 * {@code PortalPasswordChangeGate} is the half that makes that true.
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
            @Schema(description = "The temporary password just issued, on the create/reset response only. "
                    + "Null on every read. The client must change it at first sign-in.")
            String temporaryPassword
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
         * The same account, carrying the temporary password just set on it.
         *
         * <p>Reached only from {@code ClientAccountAdminService}'s create and
         * reset, under {@link PortalTemporaryPasswordProperties#issuesTemporaryPassword()}.
         * Never from {@link #withoutCredential}'s callers, which is what keeps
         * the credential off every read.
         */
        Account withTemporaryPassword(String password) {
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
