package com.edunext.edutrack.api.feature.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A-020 · {@code LoginRequest} in {@code contracts/openapi.yaml}.
 *
 * <p>The Bean Validation annotations are not belt-and-braces — per PLAN.md §2.2
 * (deviation D-4) they <b>are</b> the schema. springdoc turns them into
 * {@code required}/{@code minLength}/{@code pattern}, orval turns that into Zod,
 * and the browser and the server end up enforcing one rule written once. Changing
 * a constraint here changes both sides.
 *
 * <p>Bounds mirror the contract: username 1–100, password non-empty. There is
 * deliberately <b>no password complexity rule on the way in</b> — policy applies
 * when a password is <i>set</i> (A-028), not when one is offered. Rejecting a
 * malformed password at login with a validation error would tell an attacker
 * which candidates are not worth trying, and would lock out every user whose
 * existing password predates a later policy change.
 *
 * @param totpCode carried because the contract defines it; the 2FA challenge
 *                 itself is A-029 and this field is not yet consulted.
 */
record LoginRequest(

        @NotBlank
        @Size(max = 100)
        @Schema(description = "The user's login name. Matching is case-insensitive.")
        String username,

        @NotBlank
        @Schema(description = "Plain password. Verified against an Argon2id hash; never logged or stored.")
        String password,

        @Pattern(regexp = "^\\d{6}$")
        @Schema(description = "Six-digit TOTP. Required once the account has 2FA enabled (A-029).")
        String totpCode
) {
}
