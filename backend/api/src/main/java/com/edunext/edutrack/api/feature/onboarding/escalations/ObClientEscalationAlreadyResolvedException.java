package com.edunext.edutrack.api.feature.onboarding.escalations;

/** C-126 · 422 — resolving a client escalation that is already resolved. */
class ObClientEscalationAlreadyResolvedException extends RuntimeException {

    ObClientEscalationAlreadyResolvedException(long escalationId) {
        super("client escalation " + escalationId + " is already resolved");
    }
}
