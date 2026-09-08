package com.edunext.edutrack.api.feature.onboarding.prereqs;

/** B-124 · no {@code ob_prereq_template_tasks} row for the given id. */
class PrereqTemplateTaskNotFoundException extends RuntimeException {

    PrereqTemplateTaskNotFoundException(long templateTaskId) {
        super("prerequisite template task " + templateTaskId + " does not exist");
    }
}
