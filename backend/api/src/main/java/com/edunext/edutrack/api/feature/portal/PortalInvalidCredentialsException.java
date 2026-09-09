package com.edunext.edutrack.api.feature.portal;

/**
 * C-121 · wrong username, wrong password, unknown username and a deactivated
 * account, all one refusal — {@code InvalidCredentialsException}'s reasoning,
 * unchanged for the portal: a login form must not be a directory of which
 * client usernames exist.
 */
class PortalInvalidCredentialsException extends RuntimeException {

    PortalInvalidCredentialsException() {
        super("invalid portal credentials", null, false, false);
    }
}
