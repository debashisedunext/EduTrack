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
 * <p><b>Only the contract's length bounds on {@code newPassword}, not the whole
 * policy.</b> The {@code Password} schema's description names upper, lower,
 * digit and symbol, and no-reuse-of-the-last-three — but it carries no
 * {@code pattern}, and the password history table does not exist. Those are
 * A-028. Until then a reset can set a weak-but-long password; the gap is named
 * here rather than left to be found.
 */
record ResetPasswordRequest(

        @NotBlank
        @Size(min = 32, max = 100)
        @Schema(description = "The single-use token from the reset mail. Valid for 30 minutes.")
        String token,

        @NotBlank
        @Size(min = 8, max = 128)
        @Schema(description = "The new password.")
        String newPassword
) {
}
