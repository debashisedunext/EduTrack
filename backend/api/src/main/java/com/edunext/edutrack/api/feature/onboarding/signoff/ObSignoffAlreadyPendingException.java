package com.edunext.edutrack.api.feature.onboarding.signoff;

/**
 * 409 — this step, or this journey's go-live, already has a {@code PENDING}
 * sign-off.
 *
 * <p>Refused rather than quietly superseded, in the contract's own words: "a
 * second live token for one decision means two links that both work and a
 * record that cannot say which one was clicked". The caller who wants a fresh
 * link wants {@code resendObSignoff}, which mints one and kills the old one in
 * the same transaction.
 */
class ObSignoffAlreadyPendingException extends RuntimeException {

    private final long existingId;

    ObSignoffAlreadyPendingException(long existingId) {
        super("sign-off " + existingId + " is still awaiting this client's decision. "
                + "Send a fresh link with resend, or withdraw it first.");
        this.existingId = existingId;
    }

    long existingId() {
        return existingId;
    }
}
