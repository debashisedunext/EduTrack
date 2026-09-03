package com.edunext.edutrack.api.feature.onboarding.journeys;

import java.util.List;

/**
 * The migration's {@code fk_ob_journey_template_steps_depends_on} is
 * {@code RESTRICT}, so deleting a step other steps depend on fails in the
 * database with an error naming a constraint, not the step the caller
 * actually asked about. Checked here first so the refusal names the
 * dependents instead.
 */
class StepHasDependentsException extends RuntimeException {

    private final List<Long> dependentStepIds;

    StepHasDependentsException(long stepId, List<Long> dependentStepIds) {
        super("journey template step " + stepId + " cannot be deleted — steps "
                + dependentStepIds + " depend on it; re-point them first");
        this.dependentStepIds = dependentStepIds;
    }

    List<Long> dependentStepIds() {
        return dependentStepIds;
    }
}
