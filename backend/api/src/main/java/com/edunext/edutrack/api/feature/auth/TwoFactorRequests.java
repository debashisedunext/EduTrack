package com.edunext.edutrack.api.feature.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A-029 · the request and response bodies for two-factor enrolment.
 *
 * <p>Grouped in one file because each is a handful of lines and they are only
 * ever read together; splitting five records across five files would make the
 * flow harder to follow, not easier.
 */
final class TwoFactorRequests {

    private TwoFactorRequests() {
    }

    /**
     * What {@code POST /me/2fa/setup} returns.
     *
     * <p><b>The secret is in the body exactly once, and 2FA is not on yet.</b>
     * That is safe precisely because it is not on: the value is inert until
     * confirmed, so an intercepted setup response is a secret for a factor that
     * protects nothing. Once confirmed it is never returned again by any
     * endpoint.
     */
    @Schema(description = "What is needed to add this account to an authenticator app.")
    record SetupResponse(

            @Schema(description = "The Base32 shared secret, for typing in by hand when a QR "
                    + "code cannot be scanned.")
            String secret,

            @Schema(description = "The otpauth:// URI to render as a QR code. Contains the same "
                    + "secret; rendered client-side so it never becomes a cacheable image.")
            String otpauthUri
    ) {
    }

    /** The body of {@code POST /me/2fa/confirm}. */
    record ConfirmRequest(

            @NotBlank
            @Pattern(regexp = "^\\d{6}$", message = "A six-digit code is required.")
            @Schema(description = "The current 6-digit code from the authenticator, proving the "
                    + "secret was added successfully.", example = "123456")
            String code
    ) {
    }

    /**
     * What confirmation returns — the recovery codes, in plaintext, for the one
     * and only time they are ever shown.
     */
    @Schema(description = "Single-use recovery codes. Shown once and unrecoverable afterwards.")
    record RecoveryCodesResponse(

            @Schema(description = "Store these somewhere safe. Each works once, and they are the "
                    + "only way back in if the authenticator is lost.")
            java.util.List<String> recoveryCodes
    ) {
    }

    /**
     * The body of {@code POST /me/2fa/disable}.
     *
     * <p><b>The password is required, and that is the whole security of this
     * endpoint.</b> Disabling the second factor is the first thing somebody
     * holding a stolen access token would do, and a fifteen-minute token must
     * not be sufficient to strip the protection it was layered under. Asking for
     * the password means the caller has to hold the first factor too.
     */
    record DisableRequest(

            @NotBlank
            @Size(max = 128)
            @Schema(description = "The account password. Required so that a stolen access token "
                    + "alone cannot remove the second factor.")
            String password
    ) {
    }
}
