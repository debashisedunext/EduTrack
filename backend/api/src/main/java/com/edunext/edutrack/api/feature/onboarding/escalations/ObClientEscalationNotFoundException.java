package com.edunext.edutrack.api.feature.onboarding.escalations;

/**
 * C-126 · no {@code ob_client_escalations} row for the given id, <b>or</b>
 * one exists outside the caller's A-112 scope — {@code
 * EscalationNotFoundException}'s own reasoning, one table over: a row-scoped
 * 404 must not confirm that an id belongs to somebody else.
 */
class ObClientEscalationNotFoundException extends RuntimeException {

    ObClientEscalationNotFoundException(long escalationId) {
        super("no client escalation " + escalationId + " visible to this caller");
    }
}
