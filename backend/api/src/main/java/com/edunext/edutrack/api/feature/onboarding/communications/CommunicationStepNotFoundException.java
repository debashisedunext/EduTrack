package com.edunext.edutrack.api.feature.onboarding.communications;

/**
 * C-112 · no {@code ob_journey_steps} row for the given id, <b>or</b> one
 * exists outside the caller's A-112 scope — one indistinguishable answer,
 * {@code ObModuleGated}'s own reasoning: a row-scoped 404 must not confirm
 * that a step belongs to somebody else.
 *
 * <p>Only the append raises it. The per-step <em>read</em> answers an empty
 * timeline for the same three cases (unknown step, out-of-scope step, step
 * with nothing said about it yet), because a list has an honest empty answer
 * and a write does not.
 */
class CommunicationStepNotFoundException extends RuntimeException {

    CommunicationStepNotFoundException(long stepId) {
        super("no service " + stepId + " visible to this caller");
    }
}
