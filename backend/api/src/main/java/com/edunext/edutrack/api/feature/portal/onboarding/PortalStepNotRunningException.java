package com.edunext.edutrack.api.feature.portal.onboarding;

/**
 * C-126 · 422 — escalating a step that is not currently {@code IN_PROGRESS}.
 *
 * <p>Plan §4/§9 frame the control as "escalate on any running service" and
 * CP-03's own stub was disabled on every status but {@code IN_PROGRESS}
 * (C-121's own comment on the frontend stub says so). This is the server-side
 * half of the same rule: the button being disabled in one client is UI, not
 * authorization, so the route refuses the request on its own rather than
 * trusting the caller left the button alone.
 */
class PortalStepNotRunningException extends RuntimeException {

    PortalStepNotRunningException(long stepId) {
        super("step " + stepId + " is not currently running and cannot be escalated");
    }
}
