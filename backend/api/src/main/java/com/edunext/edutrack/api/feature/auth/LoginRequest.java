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
 * @param totpCode     the six-digit code from an authenticator. Consulted from
 *                     A-029; the contract has defined the field since A-020.
 * @param recoveryCode a single-use recovery code, for the user whose
 *                     authenticator is lost.
 *                     <p><b>A separate field rather than reusing
 *                     {@code totpCode}, and that is a deliberate choice.</b> The
 *                     contract constrains {@code totpCode} to
 *                     {@code ^\d{6}$}, so a recovery code sent through it is
 *                     refused by validation before any service sees it — which
 *                     is exactly what {@code TwoFactorIT} caught. Relaxing that
 *                     pattern would have worked and would have weakened a
 *                     constraint four streams reviewed; adding a field leaves
 *                     the reviewed one untouched.
 *                     <p><b>Contract deviation, flagged for Debashis</b> (Stream
 *                     D owns {@code contracts/openapi.yaml}): recovery codes are
 *                     not in the contract at all, so this field is an addition
 *                     rather than an implementation. Documented springdoc-side
 *                     meanwhile.
 */
record LoginRequest(

        @NotBlank
        @Size(max = 150)
        @Schema(description = "The user's login name or email address — S-01 accepts either in "
                + "one field. Matching is case-insensitive. An identifier containing '@' is "
                + "resolved against the email column, anything else against the username.")
        String username,

        @NotBlank
        @Schema(description = "Plain password. Verified against an Argon2id hash; never logged or stored.")
        String password,

        @Pattern(regexp = "^\\d{6}$")
        @Schema(description = "Six-digit TOTP. Required once the account has 2FA enabled (A-029).")
        String totpCode,

        @Size(max = 32)
        @Schema(description = "A single-use recovery code, used in place of totpCode when the "
                + "authenticator is unavailable. Formatted XXXXX-XXXXX; hyphens, spaces and "
                + "lower case are all accepted.", example = "A1B2C-D3E4F")
        String recoveryCode
) {

    /**
     * Whichever second factor the caller supplied.
     *
     * <p>{@code totpCode} wins when both are present — it is the ordinary path,
     * and a recovery code is precious enough that it should not be silently
     * spent because a form posted both fields.
     */
    String secondFactor() {
        return totpCode != null && !totpCode.isBlank() ? totpCode : recoveryCode;
    }
}
