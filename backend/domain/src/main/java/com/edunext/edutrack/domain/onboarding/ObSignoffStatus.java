package com.edunext.edutrack.domain.onboarding;

/**
 * {@code ob_signoffs.status} (A-107). Only {@link #SIGNED} satisfies the
 * C-106 completion gate on a step that requires sign-off — {@code PENDING}
 * (link sent, not yet answered), {@code OBJECTED} (reverts the step per
 * §8), {@code EXPIRED} and {@code CANCELLED} all leave it unsatisfied.
 */
public enum ObSignoffStatus {
    PENDING,
    SIGNED,
    OBJECTED,
    EXPIRED,
    CANCELLED
}
