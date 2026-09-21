package com.edunext.edutrack.api.feature.portal;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * A-130 · the wire shapes for the portal's authentication surface.
 *
 * <p>Separate records rather than the staff {@code Session} and {@code Me},
 * per plan §2.3's "separate portal DTO serializers, never staff DTOs with
 * fields hidden client-side". {@code Me} carries role, permissions, projects,
 * reportees and modules; a client has none of them, and a serializer that
 * nulls five fields is one refactor away from filling one in.
 */
final class PortalAuthDtos {

    private PortalAuthDtos() {
    }

    /**
     * <p>No complexity rule on the way in, exactly as {@code LoginRequest}
     * declines one: policy applies when a password is <i>set</i>, not when one
     * is offered. Rejecting a malformed password here would tell an attacker
     * which candidates are not worth trying, and would lock out any account
     * whose password predates a later policy change.
     */
    record LoginRequest(
            @NotBlank @Size(max = 150)
            @Schema(description = "The username from the credential mail, e.g. ACME.ravi. "
                    + "Matched case-insensitively.")
            String username,

            @NotBlank
            @Schema(description = "Plain password. Verified against an Argon2id hash; never logged or stored.")
            String password) {
    }

    /**
     * <p>{@code expiresIn} is seconds, not an absolute time: a client whose
     * clock disagrees with ours would compute the wrong deadline from a
     * timestamp, and a duration cannot be misread.
     *
     * <p>{@code mustChangePassword} is <b>a report, not the control</b>. It is
     * here so the portal shell can route straight to the change form instead of
     * rendering a page that would only be refused; the refusal itself is
     * {@code PortalPasswordChangeGate}'s, server-side, on every portal route
     * but one. Anybody reading this field as the enforcement has misread it,
     * and A-026 makes the same point for staff in the same words.
     *
     * <p>It is also what {@code PATCH /portal/me/password} answers with, where
     * it is false by construction — see {@link PortalPasswordChangeService} for
     * why that route hands back a session rather than a 204.
     */
    record LoginResponse(String accessToken, int expiresIn,
                         boolean mustChangePassword, Client client) {
    }

    /**
     * The signed-in client, as the portal shell renders it.
     *
     * <p>Two ids and a name. Which trees this login can reach is derivable —
     * a null id means that tree is empty — and stating it as booleans lets the
     * shell decide which cards to draw without inventing a second vocabulary
     * for the same fact.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Client(String username, String displayName,
                  boolean hasTicketing, boolean hasOnboarding) {

        static Client of(ClientAccountRow account) {
            return new Client(account.username(), account.displayName(),
                    account.clientId() != null, account.obClientId() != null);
        }
    }

    /**
     * What the redemption page needs before it can ask for a password.
     *
     * <p>The username is returned because the client has to know what to sign
     * in with afterwards, and it is not a secret — it is in the mail this link
     * came from. Nothing else about the account is: no email, no client name,
     * no ids. Anybody holding the link can read this, and the link is a bearer
     * credential in an inbox we do not control.
     */
    record CredentialLink(String username, String displayName, Instant expiresAt) {
    }

    /**
     * <p>The token travels in the path rather than in this body, so the page
     * can validate a link on load with a {@code GET} — one shape for both
     * calls, and no token in a body that a proxy might log differently from a
     * URL.
     */
    record RedeemRequest(
            @NotBlank
            @Size(max = PortalPasswordRules.MAX_LENGTH,
                    message = "That password is longer than " + PortalPasswordRules.MAX_LENGTH + " characters.")
            @Schema(description = "The password the client is choosing. Must satisfy the portal policy: "
                    + "at least 12 characters with upper case, lower case, a digit and a symbol.")
            String password) {
    }

    /**
     * Changing a password you already hold, as opposed to redeeming a link.
     *
     * <p>{@code currentPassword} carries no policy annotation, exactly as
     * {@code LoginRequest}'s password does not: policy applies when a password
     * is <i>set</i>, not when one is offered, and rejecting a malformed
     * current password here would tell a caller holding a borrowed token which
     * candidates are not worth trying.
     *
     * <p>{@code newPassword} is bounded but not shape-checked here either. The
     * shape is {@link PortalPasswordRules}' job, enforced in the service, so
     * that one policy is stated in one place and the form gets back the single
     * rule it broke rather than a Bean Validation message listing all of them.
     * The length bound is not cosmetic — Argon2id's cost is a function of what
     * it is given, so an unbounded field is a way to spend the server's memory
     * from a route a caller can reach.
     */
    record PasswordChangeRequest(
            @NotBlank
            @Size(max = PortalPasswordRules.MAX_LENGTH)
            @Schema(description = "The password being replaced. For a newly issued login this is the "
                    + "temporary password the client was given.")
            String currentPassword,

            @NotBlank
            @Size(max = PortalPasswordRules.MAX_LENGTH,
                    message = "That password is longer than " + PortalPasswordRules.MAX_LENGTH + " characters.")
            @Schema(description = "The password the client is choosing. Must satisfy the portal policy: "
                    + "at least 12 characters with upper case, lower case, a digit and a symbol, "
                    + "and must differ from the current one.")
            String newPassword) {
    }

    record LoginResponseEnvelope(LoginResponse data) {
    }

    record CredentialLinkEnvelope(CredentialLink data) {
    }
}
