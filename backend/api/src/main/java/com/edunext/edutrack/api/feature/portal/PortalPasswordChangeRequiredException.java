package com.edunext.edutrack.api.feature.portal;

/**
 * C-121 · the account still carries {@code must_change_password}, so every
 * onboarding-portal route but the password-set one refuses — {@code
 * PasswordChangeGate}'s rule, one principal type over.
 *
 * <p>Public, and so is {@link PortalPasswordChangeGate}: both are called from
 * {@code feature.portal.onboarding}'s controller, which is a sibling package
 * of this one rather than the same one, on the split the whole task's
 * boundary note asks for — login/session pieces here, onboarding reads there.
 */
public class PortalPasswordChangeRequiredException extends RuntimeException {

    public PortalPasswordChangeRequiredException() {
        super("this account must set a new password before continuing", null, false, false);
    }
}
