package com.edunext.edutrack.api.feature.onboarding.dashboard;

/**
 * B-127 · the {@code {cardKey}} path segment did not match any token
 * {@link ObDashboardCardKey#fromWire} recognises.
 *
 * <p>The contract is explicit that this is a 400, not a fallback to a default
 * card: "which would show a caller who mistyped a list that looked correct".
 */
class UnrecognisedCardKeyException extends RuntimeException {

    UnrecognisedCardKeyException(String token) {
        super("no such dashboard card: " + token);
    }
}
