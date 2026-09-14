package com.edunext.edutrack.api.feature.onboarding.journeys;

/**
 * An edit would make a step wait, directly or through a chain, on itself.
 *
 * <p>{@code addStep} cannot produce this and says so in its own javadoc: a new
 * step always takes the highest sequence in the template, so everything it
 * could name is already earlier than it. {@code updateStep} has no such
 * guarantee — it re-points a step that other steps may already hang off — and
 * the composite foreign key does not care, because every row involved belongs
 * to the same template. So the cycle has to be refused here, before the
 * designer's tree walker meets a chain with no root.
 */
class StepDependencyCycleException extends RuntimeException {

    StepDependencyCycleException(String stepName, String dependencyName) {
        super("\"" + stepName + "\" cannot wait for \"" + dependencyName
                + "\" — that step already waits for this one, directly or through a chain");
    }
}
