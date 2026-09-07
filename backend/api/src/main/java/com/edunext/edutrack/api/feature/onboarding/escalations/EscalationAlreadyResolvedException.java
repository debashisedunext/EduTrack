package com.edunext.edutrack.api.feature.onboarding.escalations;

/** C-115 · 422 — acknowledging or resolving a rung that is already resolved. */
class EscalationAlreadyResolvedException extends RuntimeException {

    EscalationAlreadyResolvedException(long escalationId) {
        super("escalation " + escalationId + " is already resolved");
    }
}
