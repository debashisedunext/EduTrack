package com.edunext.edutrack.api.feature.portal.onboarding;

/**
 * C-121 · 422 — this client currently has no active primary SPOC, so a
 * portal comment/submission/upload has no {@code ob_client_contacts} row to
 * be attributed to. See {@link PortalPrimaryContactReader}'s class note for
 * why that column is required and how it is normally resolved. Rare: a
 * client only reaches this if their primary contact was deactivated after
 * their portal login was issued.
 */
class PortalNoPrimaryContactException extends RuntimeException {

    PortalNoPrimaryContactException() {
        super("this client has no active primary contact to attribute a portal action to");
    }
}
