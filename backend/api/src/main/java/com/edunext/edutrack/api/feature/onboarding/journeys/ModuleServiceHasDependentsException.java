package com.edunext.edutrack.api.feature.onboarding.journeys;

import java.util.List;

/**
 * C-124 · deleting a Module Service that another service declares a
 * dependency on — {@code StepHasDependentsException}'s argument, one level up.
 *
 * <p>{@code fk_ob_journey_templates_depends_on} is {@code RESTRICT} rather
 * than {@code CASCADE} on purpose ({@code V20260903_1420}: "a service other
 * services depend on cannot be deleted out from under them"), so without this
 * check the delete surfaces as {@code ERROR 1451} naming a constraint on a
 * table the caller never mentioned. Checked first so the refusal names the
 * services holding the reference, which are the ones an admin has to re-point.
 */
class ModuleServiceHasDependentsException extends RuntimeException {

    private final List<String> dependentServiceNames;

    ModuleServiceHasDependentsException(String serviceName, List<String> dependentServiceNames) {
        super("\"" + serviceName + "\" cannot be deleted — " + dependentServiceNames
                + " depend on it; clear their \"Service depends on\" first");
        this.dependentServiceNames = dependentServiceNames;
    }

    List<String> dependentServiceNames() {
        return dependentServiceNames;
    }
}
