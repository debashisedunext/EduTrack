package com.edunext.edutrack.api.feature.portal.onboarding;

/**
 * C-121 · 404 — this caller's token carries no onboarding client at all, or
 * the row addressed belongs to a different one. One exception for both,
 * on CONVENTIONS §7's no-existence-leak rule: a caller who owns no onboarding
 * client must not be able to tell "you have none" from "that one is not
 * yours" from a probed id.
 */
class PortalOnboardingNotFoundException extends RuntimeException {

    PortalOnboardingNotFoundException() {
        super("no onboarding data for this portal account");
    }
}
