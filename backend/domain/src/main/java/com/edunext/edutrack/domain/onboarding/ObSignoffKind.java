package com.edunext.edutrack.domain.onboarding;

/**
 * {@code ob_signoffs.kind} (A-107). {@code STEP} names one flagged service;
 * {@code GO_LIVE} is the journey-wide sign-off at Live-Green and names no
 * step. A {@code CHECK} constraint keeps {@link ObSignoff#getStepId()} in
 * agreement with this column.
 */
public enum ObSignoffKind {
    STEP,
    GO_LIVE
}
