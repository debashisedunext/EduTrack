package com.edunext.edutrack.api.feature.onboarding.settings;

/**
 * B-113 · 400 — the submitted escalation ladder is not a ladder.
 *
 * <p>Two ways to get there and both are 400 rather than 422: the request is
 * malformed against a rule the contract states, not a request that is
 * well-formed and unsatisfiable in the current state.
 */
class InvalidLadderException extends RuntimeException {

    InvalidLadderException(String message) {
        super(message);
    }
}
