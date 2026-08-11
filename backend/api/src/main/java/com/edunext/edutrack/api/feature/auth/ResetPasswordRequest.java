package com.edunext.edutrack.api.feature.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A-027 · the body of {@code POST /auth/reset-password}.
 *
 * <p>Bounds mirror the contract: {@code token} at least 32 characters,
 * {@code newPassword} the {@code Password} schema's 8–128.
 *
 * <p><b>The token's minimum length is a cheap filter, not a security control.</b>
 * A real token is 43 characters of base64url over 256 random bits; the check
 * rejects obvious junk before it costs a database round trip, and nothing more.
 * What actually protects the endpoint is that the presented value must hash to a
 * row that exists, is unexpired and is unredeemed — see
 * {@link ResetPasswordService}.
 *
 * <p><b>A-028 completed the policy.</b> This previously carried only the
 * contract's length bounds, with the gap named rather than hidden.
 * {@link ValidPassword} now enforces the four character classes, and
 * {@link PasswordPolicy} the no-reuse rule — the same two rules, through the
 * same two mechanisms, as {@link ChangePasswordRequest}. Setting a password by
 * recovering an account and setting one from inside a session must not be able
 * to differ in what they accept, or the weaker path becomes the way in.
 */
record ResetPasswordRequest(

        @NotBlank
        @Size(min = 32, max = 100)
        @Schema(description = "The single-use token from the reset mail. Valid for 30 minutes.")
        String token,

        @NotBlank
        @Size(min = 8, max = 128)
        @ValidPassword
        @Schema(description = "The new password. Must not match one of the last few used, and must "
                + "contain an upper-case letter, a lower-case letter, a digit and a symbol.")
        String newPassword
) {
}
