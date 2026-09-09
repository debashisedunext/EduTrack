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
            Instant credentialSentAt
    ) {
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
