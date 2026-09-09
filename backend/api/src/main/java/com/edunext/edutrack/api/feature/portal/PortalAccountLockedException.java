package com.edunext.edutrack.api.feature.portal;

import java.time.Instant;

/**
 * C-121 · the account is locked, reachable only after the caller has already
 * proved the password — {@code AccountLockedException}'s reasoning: reported
 * only post-verification, or the 423 becomes the enumeration oracle A-020
 * closed for staff.
 */
class PortalAccountLockedException extends RuntimeException {

    private final transient Instant lockedUntil;

    PortalAccountLockedException(Instant lockedUntil) {
        super("portal account locked", null, false, false);
        this.lockedUntil = lockedUntil;
    }

    Instant lockedUntil() {
        return lockedUntil;
    }
}
