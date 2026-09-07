package com.edunext.edutrack.api.feature.onboarding.escalations;

/**
 * C-115 · no {@code ob_escalations} row for the given id, <b>or</b> one exists
 * outside the caller's A-112 scope — one indistinguishable answer, {@code
 * ObModuleGated}'s own reasoning: a row-scoped 404 must not confirm that an
 * id belongs to somebody else.
 */
class EscalationNotFoundException extends RuntimeException {

    EscalationNotFoundException(long escalationId) {
        super("no escalation " + escalationId + " visible to this caller");
    }
}
