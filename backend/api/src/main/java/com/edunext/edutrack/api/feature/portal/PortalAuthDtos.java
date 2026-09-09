package com.edunext.edutrack.api.feature.portal;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * C-121 · the wire shapes for {@code /portal/auth/**}, matching {@code
 * contracts/openapi.yaml}'s {@code Portal}-prefixed schemas (plan §9 —
 * "separate portal DTO serializers, never staff DTOs with fields hidden
 * client-side").
 */
final class PortalAuthDtos {

    private PortalAuthDtos() {
    }

    // ── requests ──────────────────────────────────────────────────────

    record PortalLoginRequest(
            @NotBlank @Size(max = 50) String username,
            @NotBlank @Size(max = 128) String password) {
    }

    record PortalRedeemRequest(@NotBlank @Size(max = 200) String token) {
    }

    /**
     * No {@code currentPassword}. {@code ClientCredentialTokens}' own note
     * explains why: the account is created with a hash of 32 random bytes
     * "never returned, never logged and immediately discarded" — nobody,
     * including the client, ever knows it. What proves this call's right to
     * set a new one is the CLIENT-typed access token minted by {@code
     * /redeem}, not a password the client cannot possibly supply.
     */
    record PortalSetPasswordRequest(@NotBlank @Size(min = 8, max = 128) String newPassword) {
    }

    // ── responses ─────────────────────────────────────────────────────

    /**
     * {@code PortalMe} — the module chooser's whole source of truth (CP-02).
     *
     * @param hasTicketing  a card for the ticketing module renders when true.
     * @param hasOnboarding a card for the onboarding module renders when true.
     *                      {@link ClientPrincipal}'s own doc: "at least one of
     *                      the two is non-null", so at least one is always true.
     */
    record PortalMe(long accountId, String displayName, String email,
                    boolean hasTicketing, boolean hasOnboarding) {

        static PortalMe from(ClientAccountRow account) {
            return new PortalMe(account.id(), account.displayName(), account.email(),
                    account.clientId() != null, account.obClientId() != null);
        }
    }

    /**
     * {@code PortalSession} — {@code Session}'s shape, one principal type
     * over. No {@code role}, no {@code landingRoute}: a portal caller has no
     * role, and CP-02's module chooser — not a role table — decides where
     * this session lands.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record PortalSession(String accessToken, Integer expiresIn, boolean mustChangePassword, PortalMe user) {

        static PortalSession issue(ClientAccountRow account, PortalAccessToken token) {
            return new PortalSession(token.value(), token.expiresInSeconds(),
                    account.mustChangePassword(), PortalMe.from(account));
        }
    }

    record PortalSessionResponse(PortalSession data) {
    }
}
