package com.edunext.edutrack.api.feature.onboarding.signoff;

import com.edunext.edutrack.domain.onboarding.ObSignoffStatus;

/**
 * 422 — resend or cancel was called on a sign-off that is no longer open.
 *
 * <p>A decision is not re-asked. {@code SIGNED} and {@code OBJECTED} are the
 * client's answer and are terminal on both operations; {@code CANCELLED} is
 * terminal for resend as well, since reviving a withdrawn request would make the
 * withdrawal unprovable. {@code EXPIRED} and {@code CANCELLED} are what a fresh
 * {@code requestObSignoff} is for, and the panel offers exactly that on those
 * two.
 */
class ObSignoffSettledException extends RuntimeException {

    ObSignoffSettledException(long signoffId, ObSignoffStatus status, String attempted) {
        super("Sign-off " + signoffId + " is " + status.name().toLowerCase()
                + " and cannot be " + attempted + ". Request a new one instead.");
    }
}
