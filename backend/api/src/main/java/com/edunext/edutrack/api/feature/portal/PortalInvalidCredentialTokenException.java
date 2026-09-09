package com.edunext.edutrack.api.feature.portal;

/**
 * C-121 · the redemption token is unknown, expired or already used —
 * deliberately one refusal for all three, {@code InvalidResetTokenException}'s
 * reasoning: telling them apart would let anyone holding a token learn
 * whether it was ever real, from a route that requires no authentication.
 */
class PortalInvalidCredentialTokenException extends RuntimeException {

    PortalInvalidCredentialTokenException() {
        super("invalid or spent portal credential token", null, false, false);
    }
}
