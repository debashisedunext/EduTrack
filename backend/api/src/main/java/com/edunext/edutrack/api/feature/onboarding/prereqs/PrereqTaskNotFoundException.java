package com.edunext.edutrack.api.feature.onboarding.prereqs;

/** B-125 · no {@code ob_client_prereq_tasks} row for the given id. */
class PrereqTaskNotFoundException extends RuntimeException {

    PrereqTaskNotFoundException(long prereqTaskId) {
        super("prerequisite task " + prereqTaskId + " does not exist");
    }
}
