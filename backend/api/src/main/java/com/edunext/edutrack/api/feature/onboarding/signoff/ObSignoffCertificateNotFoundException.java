package com.edunext.edutrack.api.feature.onboarding.signoff;

/**
 * B-116 · no such sign-off, one outside the caller's A-112 scope, one not yet
 * {@code SIGNED}, or one whose archived object is gone. All four answer 404
 * and this exception cannot tell them apart, on {@code ObClientNotFoundException}'s
 * own reasoning: a 403 (or a distinct 404) on any of the first three would
 * confirm the sign-off exists, which is the existence leak CLAUDE.md refuses.
 * The contract's own words make the fourth case a 404 too — "a certificate
 * for a decision nobody has made does not exist".
 */
class ObSignoffCertificateNotFoundException extends RuntimeException {

    ObSignoffCertificateNotFoundException(long signoffId) {
        super("no certificate for sign-off " + signoffId);
    }
}
