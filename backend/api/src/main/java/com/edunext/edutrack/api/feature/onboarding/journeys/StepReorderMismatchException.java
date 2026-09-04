package com.edunext.edutrack.api.feature.onboarding.journeys;

/**
 * C-102 · {@code reorderSteps} was called with a step id list that is not
 * exactly the template's current step set — missing an id, naming one twice,
 * or naming one that belongs to a different template. Refused rather than
 * partially applied: a reorder that silently dropped or duplicated a step
 * would leave {@code sequence} either short of the full set or colliding
 * under {@code uq_ob_journey_template_steps_seq}, and neither is a state
 * worth writing even transiently.
 */
class StepReorderMismatchException extends RuntimeException {

    StepReorderMismatchException(long templateId, String detail) {
        super("cannot reorder steps for journey template " + templateId + ": " + detail);
    }
}
