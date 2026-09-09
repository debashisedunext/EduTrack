package com.edunext.edutrack.api.feature.portal;

/**
 * C-121 · missing, unknown, expired refresh cookie, or the account has since
 * been deactivated — {@code InvalidRefreshTokenException}'s reasoning, one
 * principal type over. No reuse-detection variant, because {@link
 * PortalRefreshTokenStore} does not implement family tracking; see its class
 * note.
 */
class PortalInvalidRefreshTokenException extends RuntimeException {

    PortalInvalidRefreshTokenException() {
        super("invalid portal refresh token", null, false, false);
    }
}
