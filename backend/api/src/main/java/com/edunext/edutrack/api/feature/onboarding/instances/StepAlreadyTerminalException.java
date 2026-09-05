package com.edunext.edutrack.api.feature.onboarding.instances;

import com.edunext.edutrack.domain.onboarding.ObJourneyStepStatus;

/**
 * C-107 · 422 {@code ob-step-terminal} — a {@code DONE} or {@code SKIPPED}
 * step cannot be skipped again. Its own exception rather than reusing {@link
 * InvalidStepTransitionException}: {@code contracts/openapi.yaml} names this
 * exact problem code on both {@code skip} and OB-06's {@code PATCH} (C-111),
 * because the reason is the same one in both places — a closed step's
 * recorded TAT is what the reports are built on, and neither route may
 * disturb it.
 */
class StepAlreadyTerminalException extends RuntimeException {

    StepAlreadyTerminalException(long stepId, ObJourneyStepStatus status) {
        super("journey step " + stepId + " is already " + status + " and cannot be skipped");
    }
}
